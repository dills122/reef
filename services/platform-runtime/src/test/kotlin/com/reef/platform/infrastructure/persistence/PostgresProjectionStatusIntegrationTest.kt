package com.reef.platform.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresProjectionStatusIntegrationTest {
    @Test
    fun indexedStatusPreservesSparsePartitionsMissingWatermarksFiltersAndErrorSentinel() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val canonical = RuntimeDataSources.dataSource(url, user, password, "status-canonical")
        val projection = RuntimeDataSources.dataSource(url, user, password, "status-projection")
        val schema = "status_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            val api = PostgresRuntimePersistence(canonical, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat, projection)
            val sameStore = PostgresRuntimePersistence(canonical, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
            canonical.connection.use { c ->
                c.exec(Files.readString(Path.of("../../scripts/dev/db/migrations/runtime/0058_canonical_status_covering_index.sql")).replace("runtime.", "$schema."))
                c.createStatement().use { statement ->
                    statement.executeQuery("SELECT indnkeyatts,indnatts FROM pg_index WHERE indexrelid='$schema.idx_canonical_command_outcomes_partition_seq'::regclass").use { rows ->
                        rows.next()
                        assertEquals(2, rows.getInt(1))
                        assertEquals(3, rows.getInt(2))
                    }
                }
                c.exec("INSERT INTO $schema.projection_watermarks(projection_name,partition_id,last_partition_seq,last_error) VALUES ('source',0,10,''),('source',4,99,''),('source',-1,0,'failed')")
                for ((partition, sequence, type) in listOf(Triple(0,10,"SubmitOrder"), Triple(0,30,"CancelOrder"), Triple(0,90,"Other"), Triple(7,5,"ModifyOrder"), Triple(7,40,"SubmitOrder"), Triple(9,8,"Other"))) {
                    c.exec("INSERT INTO $schema.canonical_command_outcomes(command_id,batch_id,shard_id,partition_id,command_stream,event_stream,stream_sequence,delivered_count,command_type,payload_hash,instrument_id,order_id,result_status,reject_code) VALUES ('$partition-$sequence','$partition','shard',$partition,'commands','events',$sequence,1,'$type','hash','instrument','order','ACCEPTED','')")
                }
                c.exec("INSERT INTO $schema.submit_results(command_id,result_type,event_id,order_id,engine_order_id,code,reason,occurred_at) VALUES ('one','ACCEPTED','','','','','',''),('two','ACCEPTED','','','','','','')")
            }
            val status = api.projectionStatus("source", source = "venue-event-batch")
            assertEquals(2, status.projectedCount)
            assertEquals(3, status.lag)
            assertEquals(listOf(-1,0,4,7), status.watermarks.map { it.partitionId })
            assertEquals("failed", status.watermarks.first().lastError)
            val rows = status.watermarks.associateBy { it.partitionId }
            assertEquals(30, rows.getValue(0).canonicalMaxPartitionSequence)
            assertEquals(1, rows.getValue(0).lag)
            assertEquals(0, rows.getValue(4).canonicalMaxPartitionSequence)
            assertEquals(0, rows.getValue(4).lag)
            assertEquals(40, rows.getValue(7).canonicalMaxPartitionSequence)
            assertEquals(2, rows.getValue(7).lag)
            val filtered = api.projectionStatus("source", listOf(7), "venue-event-batch")
            assertEquals(listOf(-1,7), filtered.watermarks.map { it.partitionId })
            assertEquals(2, filtered.lag)
            val absent = api.projectionStatus("source", listOf(2), "venue-event-batch")
            assertEquals(listOf(-1,2), absent.watermarks.map { it.partitionId })
            assertEquals(0, absent.lag)
            val emptySource = api.projectionStatus("absent", source = "canonical-submit")
            assertEquals(emptyList(), emptySource.watermarks)
            assertEquals(0, emptySource.lag)
            assertEquals(ProjectionLag("source", status.lag), api.projectionLag("source", source = "venue-event-batch"))
            assertEquals(ProjectionLag("source", status.lag), sameStore.projectionLag("source", source = "venue-event-batch"))
            for (persistence in listOf(api, sameStore)) {
                assertEquals(ProjectionLag("source", 2, isLowerBound = true), persistence.projectionLagUpTo("source", emptyList(), "venue-event-batch", 2))
                assertEquals(ProjectionLag("source", 3), persistence.projectionLagUpTo("source", emptyList(), "venue-event-batch", 4))
                assertEquals(ProjectionLag("source", 1), persistence.projectionLagUpTo("source", listOf(0), "venue-event-batch", 2))
                assertEquals(ProjectionLag("source", 0), persistence.projectionLagUpTo("source", listOf(2), "venue-event-batch", 2))
            }
            canonical.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.exec("LOCK TABLE $schema.submit_results IN ACCESS EXCLUSIVE MODE")
                val executor = Executors.newSingleThreadExecutor()
                try {
                    for (persistence in listOf(api, sameStore)) {
                        val statusWithoutCount = executor.submit<ProjectionStatus> {
                            persistence.projectionStatusWithoutCount("source", source = "venue-event-batch")
                        }
                        assertEquals(status.lag, statusWithoutCount.get(5, TimeUnit.SECONDS).lag)
                        val lag = executor.submit<ProjectionLag> {
                            persistence.projectionLag("source", source = "venue-event-batch")
                        }
                        assertEquals(ProjectionLag("source", status.lag), lag.get(5, TimeUnit.SECONDS))
                        val refresh = executor.submit<Long> {
                            persistence.refreshMarketDataSnapshots("market", "source")
                        }
                        assertEquals(0L, refresh.get(5, TimeUnit.SECONDS))
                        val depth = executor.submit<MarketDataDepthSnapshot?> {
                            persistence.marketDataDepthSnapshot("missing", sourceProjectionName = "source")
                        }
                        assertEquals(null, depth.get(5, TimeUnit.SECONDS))
                    }
                } finally {
                    blocker.rollback()
                    executor.shutdownNow()
                }
            }
        } finally {
            canonical.connection.use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            (projection as? AutoCloseable)?.close()
            (canonical as? AutoCloseable)?.close()
        }
    }
    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
