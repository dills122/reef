package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.DownstreamProjectionCallerStats
import com.reef.platform.api.DownstreamProjectionCoverageMetrics
import com.reef.platform.api.DownstreamProjectionStage
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresCommittedProjectionFrontierIntegrationTest {
    @Test
    fun continuouslyNonemptyQueuesProveOlderPrefixOnlyAfterBothCommittedStages() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "busy-frontier-test")
        val schema = "busy_frontier_${UUID.randomUUID().toString().replace("-", "")}"
        val names = PostgresRuntimeSqlNames(runtimeSchema = schema)
        try {
            val persistence = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            source.connection.use { c ->
                c.exec("CREATE TABLE ${names.orderLifecycleDirty}(order_id TEXT PRIMARY KEY,dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                c.exec("CREATE TABLE ${names.marketDataSnapshotDirty}(instrument_id TEXT PRIMARY KEY,dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                c.exec("INSERT INTO ${names.projectionWatermarks}(projection_name,partition_id,last_partition_seq) VALUES ('prefix',0,41)")
                c.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) VALUES ('old')")
            }
            DownstreamProjectionCoverageMetrics.resetForTests()
            fun sample(stage: DownstreamProjectionStage) = DownstreamProjectionCoverageMetrics.observe(
                stage, persistence.committedProjectionFrontier("prefix", listOf(0)),
                persistence.projectionDirtyQueueMarkerStats(), DownstreamProjectionCallerStats.empty())
            assertNull(sample(DownstreamProjectionStage.OrderLifecycle))
            assertNull(sample(DownstreamProjectionStage.MarketData))
            source.connection.use { c ->
                c.autoCommit = false
                c.exec("DELETE FROM ${names.orderLifecycleDirty} WHERE order_id='old'")
                c.exec("INSERT INTO ${names.marketDataSnapshotDirty}(instrument_id) VALUES ('old-market')")
                c.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) VALUES ('new')")
                c.exec("UPDATE ${names.projectionWatermarks} SET last_partition_seq=42 WHERE projection_name='prefix'")
                assertNull(sample(DownstreamProjectionStage.OrderLifecycle), "uncommitted queue replacement cannot cover old work")
                c.commit()
            }
            assertEquals(41, assertNotNull(sample(DownstreamProjectionStage.OrderLifecycle)).sourceWatermarks.single().lastPartitionSequence)
            assertNull(sample(DownstreamProjectionStage.MarketData), "lifecycle commit alone cannot cover newly emitted market work")
            source.connection.use { c ->
                c.autoCommit = false
                c.exec("DELETE FROM ${names.marketDataSnapshotDirty} WHERE instrument_id='old-market'")
                c.exec("INSERT INTO ${names.marketDataSnapshotDirty}(instrument_id) VALUES ('new-market')")
                assertNull(sample(DownstreamProjectionStage.MarketData), "uncommitted market replacement cannot cover old work")
                c.commit()
            }
            val queues = persistence.projectionDirtyQueueStats()
            assertEquals(1, queues.orderLifecyclePending)
            assertEquals(1, queues.marketDataPending)
            source.connection.use { c ->
                c.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) VALUES ('another-new')")
                c.exec("INSERT INTO ${names.marketDataSnapshotDirty}(instrument_id) VALUES ('another-market')")
            }
            val markers = persistence.projectionDirtyQueueMarkerStats()
            assertEquals(2, persistence.projectionDirtyQueueStats().orderLifecyclePending)
            assertEquals(1, markers.orderLifecyclePending, "marker read reports occupancy, not exact count")
            assertEquals(1, markers.marketDataPending)
            assertTrue(markers.orderLifecycleOldestDirtiedAt.isNotBlank())
            assertTrue(markers.marketDataOldestDirtiedAt.isNotBlank())
            assertEquals(41, assertNotNull(sample(DownstreamProjectionStage.MarketData)).sourceWatermarks.single().lastPartitionSequence)
        } finally {
            source.connection.use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun committedPrefixRequiresAtomicQueueDrainAndSurvivesNewerConcurrentWork() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "committed-frontier-test")
        val schema = "frontier_${UUID.randomUUID().toString().replace("-", "")}"
        val names = PostgresRuntimeSqlNames(runtimeSchema = schema)
        try {
            val persistence = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            source.connection.use { conn ->
                conn.exec("CREATE TABLE IF NOT EXISTS ${names.orderLifecycleDirty} (order_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                conn.exec("CREATE TABLE IF NOT EXISTS ${names.marketDataSnapshotDirty} (instrument_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                conn.exec("INSERT INTO ${names.projectionWatermarks} (projection_name, partition_id, last_partition_seq) VALUES ('prefix', 0, 41)")
                conn.exec("INSERT INTO ${names.orderLifecycleDirty} (order_id) VALUES ('order-1')")
            }
            DownstreamProjectionCoverageMetrics.resetForTests()
            val prefix = persistence.committedProjectionFrontier("prefix", listOf(0))
            assertEquals(41, prefix.watermarks.single().lastPartitionSequence)
            assertTrue(prefix.databaseGeneration.isNotBlank())
            // An absent watermark is not synthesized as a healthy zero-lag partition.
            assertTrue(persistence.committedProjectionFrontier("absent", listOf(0)).watermarks.isEmpty())

            source.connection.use { lifecycle ->
                lifecycle.autoCommit = false
                lifecycle.exec("DELETE FROM ${names.orderLifecycleDirty} WHERE order_id = 'order-1'")
                lifecycle.exec("INSERT INTO ${names.marketDataSnapshotDirty} (instrument_id) VALUES ('AAPL')")
                val pending = persistence.projectionDirtyQueueStats()
                assertEquals(1, pending.orderLifecyclePending, "uncommitted deletion remains visible")
                assertEquals(0, pending.marketDataPending, "uncommitted next-stage insertion remains invisible")
                assertNull(observe(prefix, pending))
                lifecycle.commit()
            }
            val lifecycleCommitted = persistence.projectionDirtyQueueStats()
            assertEquals(0, lifecycleCommitted.orderLifecyclePending)
            assertEquals(1, lifecycleCommitted.marketDataPending)
            assertNull(observe(prefix, lifecycleCommitted))
            source.connection.use { market ->
                market.autoCommit = false
                market.exec("DELETE FROM ${names.marketDataSnapshotDirty} WHERE instrument_id = 'AAPL'")
                assertEquals(1, persistence.projectionDirtyQueueStats().marketDataPending)
                market.commit()
            }
            assertNotNull(observe(prefix, persistence.projectionDirtyQueueStats()))

            // Newer uncommitted source work cannot erase an already covered committed prefix.
            source.connection.use { newer ->
                newer.autoCommit = false
                newer.exec("UPDATE ${names.projectionWatermarks} SET last_partition_seq = 42 WHERE projection_name = 'prefix' AND partition_id = 0")
                newer.exec("INSERT INTO ${names.orderLifecycleDirty} (order_id) VALUES ('order-1')")
                assertEquals(41, persistence.committedProjectionFrontier("prefix", listOf(0)).watermarks.single().lastPartitionSequence)
                assertNotNull(observe(prefix, persistence.projectionDirtyQueueStats()))
                newer.commit()
            }
            val newerPrefix = persistence.committedProjectionFrontier("prefix", listOf(0))
            assertEquals(42, newerPrefix.watermarks.single().lastPartitionSequence)
            assertNull(observe(newerPrefix, persistence.projectionDirtyQueueStats()))

            // A concurrent re-dirty waits behind the deleting row lock, then survives commit.
            source.connection.use { drain ->
                drain.autoCommit = false
                drain.exec("DELETE FROM ${names.orderLifecycleDirty} WHERE order_id = 'order-1'")
                val started = CountDownLatch(1)
                val redirtyPid = AtomicInteger()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val redirty = executor.submit {
                        source.connection.use { conn ->
                            conn.exec("SET statement_timeout = '5s'")
                            conn.createStatement().use { statement ->
                                statement.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                    rs.next()
                                    redirtyPid.set(rs.getInt(1))
                                }
                            }
                            started.countDown()
                            conn.exec("INSERT INTO ${names.orderLifecycleDirty} (order_id) VALUES ('order-1') ON CONFLICT (order_id) DO NOTHING")
                        }
                    }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    var blocked = false
                    while (!blocked && System.nanoTime() < deadline) {
                        source.connection.use { observer ->
                            observer.createStatement().use { statement ->
                                statement.executeQuery("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ${redirtyPid.get()}").use { rs ->
                                    blocked = rs.next() && rs.getString(1) == "Lock"
                                }
                            }
                        }
                        if (!blocked) Thread.sleep(10)
                    }
                    assertTrue(blocked, "actual ON CONFLICT DO NOTHING re-dirty must wait for deleting transaction")
                    assertEquals(1, persistence.projectionDirtyQueueStats().orderLifecyclePending)
                    drain.commit()
                    redirty.get(5, TimeUnit.SECONDS)
                    assertEquals(1, persistence.projectionDirtyQueueStats().orderLifecyclePending)
                    assertEquals(42, assertNotNull(observe(newerPrefix, persistence.projectionDirtyQueueStats())).sourceWatermarks.single().lastPartitionSequence,
                        "the committed drain covers42; a later invalidation remains pending without invalidating that prefix")
                } finally {
                    drain.rollback()
                    executor.shutdownNow()
                }
            }
            source.connection.use { conn -> conn.exec("DELETE FROM ${names.orderLifecycleDirty}") }
            val final = assertNotNull(observe(newerPrefix, persistence.projectionDirtyQueueStats()))
            assertEquals(42, final.sourceWatermarks.single().lastPartitionSequence)
            assertEquals(prefix.databaseGeneration, final.databaseGeneration)
            source.connection.use { conn ->
                conn.exec("INSERT INTO ${names.projectionWatermarks} (projection_name, partition_id, last_partition_seq, last_error) VALUES ('prefix', -1, 0, 'failed')")
            }
            assertNull(observe(persistence.committedProjectionFrontier("prefix", listOf(0)), persistence.projectionDirtyQueueStats()))
        } finally {
            source.connection.use { conn -> conn.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            DownstreamProjectionCoverageMetrics.resetForTests()
        }
    }

    private fun observe(frontier: CommittedProjectionFrontier, queues: ProjectionDirtyQueueStats) =
        DownstreamProjectionCoverageMetrics.observe(
            DownstreamProjectionStage.MarketData, frontier, queues,
            DownstreamProjectionCallerStats(1, 0, 0, 1, 1, mapOf("newer-active-caller" to 1)),
            Instant.now().plusSeconds(1).toString()
        )

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
