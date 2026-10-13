package com.reef.platform.calcify.finance

import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/** The launcher's cut-before-start property on a real broker: a refused restore cut starts nothing and touches no database. */
@Tag("broker")
class FiniteFinanceLauncherBrokerTest {
    @Test fun aRefusedRestoreCutStartsNoAppAndNoProjection() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin ->
                F.createTopics(admin)
                val producer = F.producer(rp.bootstrap)
                F.send(producer, F.DECISIONS, byteArrayOf(1, 2, 3))                       // a decision log that cannot be decoded
                val projectorRequested = AtomicInteger()
                val launcher = FiniteFinanceLauncher(F.config, Properties().apply { put("bootstrap.servers", rp.bootstrap) }, Properties().apply { put("bootstrap.servers", rp.bootstrap) },
                    Files.createTempDirectory("finance-launcher-state").toString(), { projectorRequested.incrementAndGet(); error("projector must not be built") }, ownerEpoch = 1)
                val refused = assertFailsWith<FinanceRestoreRefused> { launcher.start() }
                assertEquals(RestoreRefusal.FINANCE_DECISION_LOG_INVALID, refused.code)
                assertEquals(0, projectorRequested.get(), "no database access before the cut verifies")
                assertEquals(null, F.committed(admin, F.CAPTURE), "the Streams app never joined its group")
                assertTrue(admin.listConsumerGroups().all().get().none { it.groupId() == F.APP_ID }, "the app never created or joined its consumer group")
                producer.close()
            }
        }
    }

    @Test fun aCleanGenesisCutStartsTheAppAndTheProjectionLoop() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { F.createTopics(it) }
            val failures = AtomicInteger()
            val launcher = FiniteFinanceLauncher(F.config, Properties().apply { put("bootstrap.servers", rp.bootstrap) }, Properties().apply { put("bootstrap.servers", rp.bootstrap) },
                Files.createTempDirectory("finance-launcher-state").toString(), { failures.incrementAndGet(); error("no database in this test") }, ownerEpoch = 1)
            // The cut passes at genesis, so the launcher proceeds to build the projector; the stand-in fails there, before the app exists.
            val e = assertFailsWith<IllegalStateException> { launcher.start().close() }
            assertEquals("no database in this test", e.message)
            assertEquals(1, failures.get())
            rp.admin().use { admin -> assertTrue(admin.listConsumerGroups().all().get().none { it.groupId() == F.APP_ID }, "a failed start leaves no running app behind") }
        }
    }

    /** A clean stop leaves the consumer group, so a restart is not delayed by the previous member's 45 s session timeout (measured 43 s before, 0.5 s after). */
    @Test fun aCleanStopLeavesTheGroupSoARestartIsNotDelayed() {
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { F.createTopics(it) }
            val stateDir = Files.createTempDirectory("finance-restart-state").toString()
            // Production properties on purpose: the shared fixture lowers session.timeout.ms to 6 s (for SIGKILL tests), which would hide the delay this test guards.
            fun start() = org.apache.kafka.streams.KafkaStreams(FinanceTopology.build(F.config, persistent = true), FinanceTopology.streamsProperties(F.APP_ID, rp.bootstrap, stateDir)).also { s ->
                s.start()
                val deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos()
                while (s.state() != org.apache.kafka.streams.KafkaStreams.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(50)
                assertEquals(org.apache.kafka.streams.KafkaStreams.State.RUNNING, s.state(), "the app reaches RUNNING")
            }
            val first = start()
            FinanceTopology.closeLeavingGroup(first, java.time.Duration.ofSeconds(30))
            val t0 = System.nanoTime()
            val second = start()
            val seconds = (System.nanoTime() - t0) / 1e9
            FinanceTopology.closeLeavingGroup(second, java.time.Duration.ofSeconds(30))
            assertTrue(seconds < 20, "restart after a clean stop took $seconds s; waiting out the session timeout takes about 43 s")
        }
    }
}
