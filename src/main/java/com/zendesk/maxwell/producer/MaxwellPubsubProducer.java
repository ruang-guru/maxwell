package com.zendesk.maxwell.producer;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.batching.BatchingSettings;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.monitoring.Metrics;
import com.zendesk.maxwell.replication.Position;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.schema.ddl.DDLMap;
import com.zendesk.maxwell.util.StoppableTask;
import com.zendesk.maxwell.util.StoppableTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.bp.Duration;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeoutException;

class PubsubCallback implements ApiFutureCallback<String> {
  public static final Logger LOGGER = LoggerFactory.getLogger(PubsubCallback.class);

  private final AbstractAsyncProducer.CallbackCompleter cc;
  private final Position position;
  private final String json;
  private MaxwellContext context;

  private Counter succeededMessageCount;
  private Counter failedMessageCount;
  private Meter succeededMessageMeter;
  private Meter failedMessageMeter;

  public PubsubCallback(AbstractAsyncProducer.CallbackCompleter cc,
                        Position position, String json,
                        Counter producedMessageCount, Counter failedMessageCount,
                        Meter succeededMessageMeter, Meter failedMessageMeter,
                        MaxwellContext context) {
    this.cc = cc;
    this.position = position;
    this.json = json;
    this.succeededMessageCount = producedMessageCount;
    this.failedMessageCount = failedMessageCount;
    this.succeededMessageMeter = succeededMessageMeter;
    this.failedMessageMeter = failedMessageMeter;
    this.context = context;
  }

  @Override
  public void onSuccess(String messageId) {
    this.succeededMessageCount.inc();
    this.succeededMessageMeter.mark();

    if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Successfully published message with ID: {} @ position: {}", messageId, this.position);
        LOGGER.debug("Message content: {}", this.json);
    }

    cc.markCompleted();
  }

  @Override
  public void onFailure(Throwable t) {
    this.failedMessageCount.inc();
    this.failedMessageMeter.mark();

    LOGGER.error("Failed to publish message @ position: {}. Error: {}", position, t.getMessage());
    LOGGER.error("Failed message content: {}", this.json);
    LOGGER.error("Detailed error: ", t);

    if (!this.context.getConfig().ignoreProducerError) {
        this.context.terminate(new RuntimeException(t));
        return;
    }

    cc.markCompleted();
  }
}

public class MaxwellPubsubProducer extends AbstractProducer {
  public static final Logger LOGGER = LoggerFactory.getLogger(MaxwellPubsubProducer.class);

  private final ArrayBlockingQueue<RowMap> queue;
  private final MaxwellPubsubProducerWorker worker;

  public MaxwellPubsubProducer(MaxwellContext context, String pubsubProjectId, String pubsubEndpoint,
                               String pubsubTopic, String ddlPubsubTopic)
                               throws IOException {
    super(context);
    this.queue = new ArrayBlockingQueue<>(100);
    this.worker = new MaxwellPubsubProducerWorker(context, pubsubProjectId, pubsubEndpoint,
                                                  pubsubTopic, ddlPubsubTopic,
                                                  this.queue);

    Thread thread = new Thread(this.worker, "maxwell-pubsub-worker");
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public void push(RowMap r) throws Exception {
    this.queue.put(r);
  }

  @Override
  public StoppableTask getStoppableTask() {
    return this.worker;
  }
}

class MaxwellPubsubProducerWorker
    extends AbstractAsyncProducer implements Runnable, StoppableTask {
  static final Logger LOGGER = LoggerFactory.getLogger(MaxwellPubsubProducerWorker.class);

  private final String projectId;
  private final String endpoint;
  private Publisher pubsub;
  private final ProjectTopicName topic;
  private final ProjectTopicName ddlTopic;
  private Publisher ddlPubsub;
  private final ArrayBlockingQueue<RowMap> queue;
  private Thread thread;
  private StoppableTaskState taskState;

  public MaxwellPubsubProducerWorker(MaxwellContext context,
                                     String pubsubProjectId, String pubsubEndpoint, String pubsubTopic,
                                     String ddlPubsubTopic,
                                     ArrayBlockingQueue<RowMap> queue)
                                     throws IOException {
    super(context);

    // Publish request get triggered based on request size, messages count & time since last publish
    BatchingSettings batchingSettings =
    BatchingSettings.newBuilder()
        .setElementCountThreshold(context.getConfig().pubsubMessageCountBatchSize)
        .setRequestByteThreshold(context.getConfig().pubsubRequestBytesThreshold)
        .setDelayThreshold(context.getConfig().pubsubPublishDelayThreshold)
        .build();
    
    // Retry settings control how the publisher handles retryable failures
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setInitialRetryDelay(context.getConfig().pubsubRetryDelay)
            .setRetryDelayMultiplier(context.getConfig().pubsubRetryDelayMultiplier)
            .setMaxRetryDelay(context.getConfig().pubsubMaxRetryDelay)
            .setInitialRpcTimeout(context.getConfig().pubsubInitialRpcTimeout)
            .setRpcTimeoutMultiplier(context.getConfig().pubsubRpcTimeoutMultiplier)
            .setMaxRpcTimeout(context.getConfig().pubsubMaxRpcTimeout)
            .setTotalTimeout(context.getConfig().pubsubTotalTimeout)
            .build();
        
    this.projectId = pubsubProjectId;
    this.endpoint = pubsubEndpoint;
    this.topic = ProjectTopicName.of(pubsubProjectId, pubsubTopic);

    Publisher.Builder builder = Publisher.newBuilder(this.topic)
        .setBatchingSettings(batchingSettings)
        .setRetrySettings(retrySettings);

    if (context.getConfig().pubsubEnableCompression) {
        LOGGER.info("Enabling compression with threshold of {} bytes", context.getConfig().pubsubCompressionBytesThreshold);
        builder.setEnableCompression(true)
               .setCompressionBytesThreshold(context.getConfig().pubsubCompressionBytesThreshold);
    }

    if (endpoint != null && !endpoint.isEmpty()) {
        LOGGER.info("Using custom endpoint: {}", endpoint);
        TransportChannelProvider channelProvider =
            InstantiatingGrpcChannelProvider.newBuilder()
                .setEndpoint(endpoint)
                .build();
        builder.setChannelProvider(channelProvider);
    }

    LOGGER.debug("Initializing Pub/Sub publisher for topic: {}", this.topic.getTopic());
    this.pubsub = builder.build();
    LOGGER.debug("Pub/Sub publisher initialized successfully");

    if ( context.getConfig().outputConfig.outputDDL == true &&
         ddlPubsubTopic != pubsubTopic ) {
      this.ddlTopic = ProjectTopicName.of(pubsubProjectId, ddlPubsubTopic);
      this.ddlPubsub = Publisher.newBuilder(this.ddlTopic).build();
      LOGGER.debug("Initializing DDL Pub/Sub publisher for topic: {}", this.ddlTopic.getTopic());
      LOGGER.debug("DDL Pub/Sub publisher initialized successfully");
    } else {
      this.ddlTopic = this.topic;
      this.ddlPubsub = this.pubsub;
    }

    Metrics metrics = context.getMetrics();

    this.queue = queue;
    this.taskState = new StoppableTaskState("MaxwellPubsubProducerWorker");
  }

  @Override
  public void run() {
    this.thread = Thread.currentThread();
    while ( true ) {
      try {
        RowMap row = queue.take();
        if ( !taskState.isRunning() ) {
          taskState.stopped();
          return;
        }
        this.push(row);
      } catch ( Exception e ) {
        taskState.stopped();
        context.terminate(e);
        return;
      }
    }
  }

  @Override
  public void sendAsync(RowMap r, AbstractAsyncProducer.CallbackCompleter cc)
      throws Exception {
    String message = r.toJSON(outputConfig);
    
    if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Attempting to publish message: {}", message);
    }
    
    ByteString data = ByteString.copyFromUtf8(message);
    PubsubMessage pubsubMessage = PubsubMessage.newBuilder().setData(data).build();

    try {
        if (r instanceof DDLMap) {
            LOGGER.debug("Publishing DDL message to topic: {}", ddlTopic.getTopic());
            ApiFuture<String> apiFuture = ddlPubsub.publish(pubsubMessage);
            LOGGER.debug("DDL message publish initiated with message size: {} bytes", data.size());
            PubsubCallback callback = new PubsubCallback(cc, r.getNextPosition(), message,
                    this.succeededMessageCount, this.failedMessageCount, this.succeededMessageMeter, this.failedMessageMeter, this.context);

            ApiFutures.addCallback(apiFuture, callback, MoreExecutors.directExecutor());
        } else {
            LOGGER.debug("Publishing message to topic: {} with size: {} bytes", 
                       topic.getTopic(), data.size());
            ApiFuture<String> apiFuture = pubsub.publish(pubsubMessage);
            PubsubCallback callback = new PubsubCallback(cc, r.getNextPosition(), message,
                    this.succeededMessageCount, this.failedMessageCount, this.succeededMessageMeter, this.failedMessageMeter, this.context);

            ApiFutures.addCallback(apiFuture, callback, MoreExecutors.directExecutor());
        }
    } catch (Exception e) {
        LOGGER.error("Error publishing message: {}", e.getMessage(), e);
        throw e;
    }
  }

  @Override
  public void requestStop() throws Exception {
    taskState.requestStop();
    pubsub.shutdown();

    if ( ddlPubsub != null ) {
      ddlPubsub.shutdown();
    }
  }

  @Override
  public void awaitStop(Long timeout) throws TimeoutException {
    taskState.awaitStop(thread, timeout);
  }

  @Override
  public StoppableTask getStoppableTask() {
    return this;
  }
}
