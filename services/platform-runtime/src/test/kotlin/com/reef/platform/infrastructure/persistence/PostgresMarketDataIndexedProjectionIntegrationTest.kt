package com.reef.platform.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresMarketDataIndexedProjectionIntegrationTest {
    @Test
    fun indexedMarketPreserves0052BusinessStateForBestLevelsCurrencyEmptySidesAndReplay() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "market-indexed-${UUID.randomUUID()}")
        val s = "market_indexed_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = s), PostgresBootstrapMode.Compat)
            source.connection.use { c ->
                c.exec("CREATE TABLE $s.market_data_snapshot_dirty(instrument_id TEXT PRIMARY KEY,dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                c.exec("CREATE TABLE $s.order_lifecycle_dirty(order_id TEXT PRIMARY KEY,dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                val indexes = Files.readString(dir.resolve("0028_typed_top_of_book_facts.sql"))
                for (side in listOf("bid", "ask")) {
                    val start = indexes.indexOf("CREATE INDEX IF NOT EXISTS idx_order_lifecycle_state_book_${side}_native")
                    c.exec(indexes.substring(start, indexes.indexOf(';', start) + 1).replace("runtime.", "$s."))
                }
                c.seed(s,"b1","both","BUY","10","2","USD")
                c.seed(s,"b2","both","BUY","10","3","USD")
                c.seed(s,"low","both","BUY","9","100","ZZZ")
                c.seed(s,"a1","both","SELL","12","4","EUR")
                c.seed(s,"a2","both","SELL","12","6","EUR")
                c.seed(s,"high","both","SELL","13","100","USD")
                c.seed(s,"buy","buyOnly","BUY","10","2","USD")
                c.seed(s,"sell","sellOnly","SELL","12","4","EUR")
                c.seed(s,"other","otherOnly","OTHER","5","1","JPY")
                c.seed(s,"empty","empty","BUY","10","0","USD")
                c.seed(s,"scaleA","scaled","BUY","10.0","2.0","USD")
                c.seed(s,"scaleZ","scaled","BUY","10.00","3.00","USD")
                c.exec("INSERT INTO $s.market_data_snapshots(projection_name,source_projection_name,instrument_id) VALUES ('market','old','empty'),('unrelated','old','empty')")
                val ids = listOf("both","buyOnly","sellOnly","otherOnly","empty","missing","scaled")
                parity(c,s,ids)
                assertEquals("10|5|12|10|ZZZ",c.text("SELECT best_bid_price||'|'||best_bid_quantity||'|'||best_ask_price||'|'||best_ask_quantity||'|'||currency FROM $s.market_data_snapshots WHERE instrument_id='both'"))
                assertEquals("10|2||",c.text("SELECT best_bid_price||'|'||best_bid_quantity||'|'||best_ask_price||'|'||best_ask_quantity FROM $s.market_data_snapshots WHERE instrument_id='buyOnly'"))
                assertEquals("||12|4",c.text("SELECT best_bid_price||'|'||best_bid_quantity||'|'||best_ask_price||'|'||best_ask_quantity FROM $s.market_data_snapshots WHERE instrument_id='sellOnly'"))
                assertEquals("|||JPY",c.text("SELECT best_bid_price||'|'||best_bid_quantity||'|'||best_ask_price||'|'||currency FROM $s.market_data_snapshots WHERE instrument_id='otherOnly'"))
                assertEquals("1",c.text("SELECT count(*) FROM $s.market_data_snapshots WHERE instrument_id='empty' AND projection_name='unrelated'"))
                assertEquals("0",c.text("SELECT count(*) FROM $s.market_data_snapshots WHERE projection_name='market' AND instrument_id IN ('empty','missing')"))
                c.exec("UPDATE $s.order_lifecycle_state SET remaining_quantity_units='0',remaining_quantity_units_num=0,status='FILLED' WHERE order_id IN ('b1','b2')")
                parity(c,s,ids)
                assertEquals("9|100|source|123|7",c.text("SELECT best_bid_price||'|'||best_bid_quantity||'|'||source_projection_name||'|'||last_partition_seq||'|'||lag FROM $s.market_data_snapshots WHERE instrument_id='both'"))
                val before = c.rows(s)
                parity(c,s,ids)
                assertEquals(before,c.rows(s),"replayed invalidation preserves business state")
                c.exec("UPDATE $s.order_lifecycle_state SET status='CANCELLED'")
                parity(c,s,ids)
                assertEquals("0",c.text("SELECT count(*) FROM $s.market_data_snapshots WHERE projection_name='market'"))
            }
        } finally {
            source.connection.use { it.exec("DROP SCHEMA IF EXISTS $s CASCADE") }
            (source as? AutoCloseable)?.close()
        }
    }
    @Test
    fun idleMarketRefreshesCaughtUpMetadataOnlyAfterBothQueuesDrain() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "market-metadata-${UUID.randomUUID()}")
        val schema = "market_metadata_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
            source.connection.use { c ->
                c.exec("CREATE TABLE $schema.market_data_snapshot_dirty(instrument_id TEXT PRIMARY KEY,dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                c.exec("CREATE TABLE IF NOT EXISTS $schema.order_lifecycle_dirty(order_id TEXT PRIMARY KEY,dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                val candidate = dir.resolve("0057_market_data_idle_metadata.sql")
                val sql = Files.readString(if (Files.exists(candidate)) candidate else dir.resolve("0055_market_data_indexed_currency.sql"))
                val start = sql.indexOf("CREATE OR REPLACE FUNCTION runtime.runtime_project_market_data_snapshots(")
                c.exec(sql.substring(start, sql.indexOf("$$;", start) + 3).replace("runtime.", "$schema."))
                c.exec("INSERT INTO $schema.market_data_snapshots(projection_name,source_projection_name,instrument_id,last_partition_seq,lag) VALUES ('market','source','unchanged',10,7),('other','source','unchanged',10,7)")
                fun refresh(lag: Int = 0, batch: Int = 500) = c.text("SELECT $schema.runtime_project_market_data_snapshots('market','source',20,$lag,$batch)")
                fun metadata() = c.text("SELECT last_partition_seq||'|'||lag FROM $schema.market_data_snapshots WHERE projection_name='market'")
                assertEquals("0", refresh(batch = 0))
                assertEquals("10|7", metadata(), "disabled batch must not refresh")
                assertEquals("0", refresh(lag = 3))
                assertEquals("10|7", metadata(), "source still behind")
                c.exec("INSERT INTO $schema.order_lifecycle_dirty(order_id) VALUES ('pending')")
                assertEquals("0", refresh())
                assertEquals("10|7", metadata(), "lifecycle still behind")
                c.exec("DELETE FROM $schema.order_lifecycle_dirty")
                c.exec("INSERT INTO $schema.market_data_snapshot_dirty(instrument_id) VALUES ('pending')")
                source.connection.use { lock ->
                    lock.autoCommit = false
                    lock.exec("SELECT instrument_id FROM $schema.market_data_snapshot_dirty FOR UPDATE")
                    assertEquals("0", refresh())
                    assertEquals("10|7", metadata(), "another market caller holds dirty work")
                    lock.rollback()
                }
                c.exec("DELETE FROM $schema.market_data_snapshot_dirty")
                assertEquals("0", refresh(), "metadata refresh is not a processed instrument")
                assertEquals("20|0", metadata(), "idle snapshots must report the caught-up source")
                assertEquals("10|7", c.text("SELECT last_partition_seq||'|'||lag FROM $schema.market_data_snapshots WHERE projection_name='other'"))
                c.exec("UPDATE $schema.market_data_snapshots SET last_partition_seq=30 WHERE projection_name='market'")
                assertEquals("0", refresh())
                assertEquals("30|0", metadata(), "older source observation must not regress metadata")
                c.exec("UPDATE $schema.market_data_snapshots SET source_projection_name='different',last_partition_seq=10,lag=7 WHERE projection_name='market'")
                assertEquals("0", refresh())
                assertEquals("10|7", metadata(), "different source namespace must not be relabeled")
                c.exec("UPDATE $schema.market_data_snapshots SET source_projection_name='source',last_partition_seq=20,lag=0 WHERE projection_name='market'")
                val version = c.text("SELECT xmin::text FROM $schema.market_data_snapshots WHERE projection_name='market'")
                assertEquals("0", refresh())
                assertEquals(version, c.text("SELECT xmin::text FROM $schema.market_data_snapshots WHERE projection_name='market'"), "repeated idle poll must not rewrite")
            }
        } finally {
            source.connection.use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            (source as? AutoCloseable)?.close()
        }
    }
    private val dir = Path.of("../../scripts/dev/db/migrations/runtime")
    private fun parity(c: Connection,s: String,ids: List<String>) {
        fun drain(file: String): List<String> {
            val sql=Files.readString(dir.resolve(file));val start=sql.indexOf("CREATE OR REPLACE FUNCTION runtime.runtime_project_market_data_snapshots(")
            assertTrue(start>=0)
            c.exec(sql.substring(start,sql.indexOf("$$;",start)+3).replace("runtime.","$s."))
            ids.forEach { c.exec("INSERT INTO $s.market_data_snapshot_dirty(instrument_id) VALUES ('$it') ON CONFLICT DO NOTHING") }
            assertEquals(ids.size.toString(),c.text("SELECT $s.runtime_project_market_data_snapshots('market','source',123,7,500)"))
            assertEquals("0",c.text("SELECT count(*) FROM $s.market_data_snapshot_dirty"))
            return c.rows(s)
        }
        val reference=drain("0052_projection_dirty_serialization.sql")
        assertEquals(reference,drain("0053_market_data_indexed_top_of_book.sql"),"every snapshot column equals0052 except updated_at")
        val indexedCurrency = Files.readString(dir.resolve("0055_market_data_indexed_currency.sql"))
        val indexStart = indexedCurrency.indexOf("CREATE INDEX IF NOT EXISTS")
        c.exec(indexedCurrency.substring(indexStart,indexedCurrency.indexOf(';',indexStart)+1).replace("runtime.","$s."))
        assertEquals(reference,drain("0055_market_data_indexed_currency.sql"),"indexed currency preserves all0052 snapshot columns except updated_at")
        assertEquals(reference,drain("0057_market_data_idle_metadata.sql"),"idle metadata fix preserves0052 business state during dirty work")
        assertEquals("0",c.text("SELECT $s.runtime_project_market_data_snapshots('market','source',123,7,500)"))
    }
    private fun Connection.seed(s:String,id:String,instrument:String,side:String,price:String,quantity:String,currency:String)=exec("""
        INSERT INTO $s.order_lifecycle_state(order_id,engine_order_id,instrument_id,participant_id,account_id,side,order_type,
        original_quantity_units,remaining_quantity_units,filled_quantity_units,limit_price,currency,time_in_force,status,
        accepted_at,last_event_at,original_quantity_units_num,remaining_quantity_units_num,filled_quantity_units_num,limit_price_num)
        VALUES ('$id','$id','$instrument','participant','account','$side','LIMIT','$quantity','$quantity','0','$price','$currency','GTC','OPEN',
        '2026-09-24T00:00:00Z','2026-09-24T00:00:00Z',$quantity,$quantity,0,$price)
    """.trimIndent())
    private fun Connection.exec(sql:String)=createStatement().use { it.execute(sql) }
    private fun Connection.text(sql:String):String=createStatement().use { s -> s.executeQuery(sql).use { r -> assertTrue(r.next());r.getString(1) } }
    private fun Connection.rows(s:String):List<String> = createStatement().use { stmt ->
        stmt.executeQuery("SELECT (to_jsonb(t)-'updated_at')::text FROM $s.market_data_snapshots t ORDER BY projection_name,instrument_id").use { r -> buildList { while(r.next())add(r.getString(1)) } }
    }
}
