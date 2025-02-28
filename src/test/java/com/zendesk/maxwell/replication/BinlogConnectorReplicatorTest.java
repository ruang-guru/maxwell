package com.zendesk.maxwell.replication;

import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.TableMapEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.WriteRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.MaxwellTestSupport;
import com.zendesk.maxwell.MysqlIsolatedServer;
import com.zendesk.maxwell.TestWithNameLogging;
import com.zendesk.maxwell.bootstrap.SynchronousBootstrapper;
import com.zendesk.maxwell.monitoring.NoOpMetrics;
import com.zendesk.maxwell.producer.BufferedProducer;
import com.zendesk.maxwell.producer.MaxwellOutputConfig;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.schema.MysqlSchemaStore;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

public class BinlogConnectorReplicatorTest extends TestWithNameLogging {

	private class TestTableMapEventDeserializer extends TableMapEventDataDeserializer {
		public HashMap<Long, TableMapEventData> map;

		public TestTableMapEventDeserializer() {
			this.map = new HashMap<>();
		}

		@Override
		public TableMapEventData deserialize(ByteArrayInputStream inputStream) throws IOException {
			TableMapEventData data = super.deserialize(inputStream);
			map.put(data.getTableId(), data);
			return data;
		}
	}

	public final static int THROWME = 333;
	private class DisconnectingDeserializer	extends WriteRowsEventDataDeserializer {
		private boolean thrown = false;
		public DisconnectingDeserializer(Map<Long, TableMapEventData> tableMapEventByTableId) {
			super(tableMapEventByTableId);
		}

		@Override
		public WriteRowsEventData deserialize(ByteArrayInputStream inputStream) throws IOException {
			WriteRowsEventData d = super.deserialize(inputStream);
			List<Serializable[]> list = d.getRows();
			if ( !thrown && list.get(0)[0] instanceof Integer ) {
				Integer i = (Integer) list.get(0)[0];
				if ( i == THROWME ) {
					thrown = true;
					inputStream.close();
					throw new EOFException();
				}
			}

			return d;
		}
	}

	@Test
	public void testGTIDReconnects() throws Exception {
		assumeTrue(MysqlIsolatedServer.getVersion().atLeast(MysqlIsolatedServer.VERSION_5_6));

		MysqlIsolatedServer server = MaxwellTestSupport.setupServer("--gtid_mode=ON --enforce-gtid-consistency=true");
		MaxwellTestSupport.setupSchema(server, false);

		server.execute("create table test.t ( i int )");
		server.execute("create table test.u ( i int )");
		// prime GTID set
		server.execute("insert into test.t set i = 111");

		Position position = Position.capture(server.getConnection(), true);
		MaxwellContext context = MaxwellTestSupport.buildContext(server.getPort(), position, null);

		BinlogConnectorReplicator replicator = new BinlogConnectorReplicator(
			new MysqlSchemaStore(context, position),
			new BufferedProducer(context, 1),
			null,
			context.getConfig().maxwellMysql,
			333098L,
			"maxwell",
			new NoOpMetrics(),
			position,
			false,
			"maxwell-client",
			new HeartbeatNotifier(),
			// null,
			context.getFilter(),
			new MaxwellOutputConfig(),
			context.getConfig().bufferMemoryUsage
		);

		EventDeserializer eventDeserializer = new EventDeserializer();
		eventDeserializer.setCompatibilityMode(
			EventDeserializer.CompatibilityMode.DATE_AND_TIME_AS_LONG_MICRO,
			EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY,
			EventDeserializer.CompatibilityMode.INVALID_DATE_AND_TIME_AS_MIN_VALUE
		);

		TestTableMapEventDeserializer tmd = new TestTableMapEventDeserializer();
		eventDeserializer.setEventDataDeserializer(EventType.TABLE_MAP, tmd);
		DisconnectingDeserializer dd = (DisconnectingDeserializer) new DisconnectingDeserializer(tmd.map).setMayContainExtraInformation(true);
		eventDeserializer.setEventDataDeserializer(EventType.EXT_WRITE_ROWS, dd);
		replicator.client.setEventDeserializer(eventDeserializer);

		replicator.startReplicator();
		// prime up maxwell, let it capture schema and such.
		server.execute("insert into test.t set i = 111");
		while ( replicator.getRow() != null ) { }


		server.getConnection().setAutoCommit(false);
		server.execute("BEGIN");
		server.execute("insert into test.t set i = 222");
		server.execute("insert into test.u set i = " + THROWME);
		server.execute("COMMIT");

		assertEquals(222L, replicator.getRow().getData().get("i"));
		assertEquals(333L, replicator.getRow().getData().get("i"));
		assertEquals(null, replicator.getRow());
	}
}
