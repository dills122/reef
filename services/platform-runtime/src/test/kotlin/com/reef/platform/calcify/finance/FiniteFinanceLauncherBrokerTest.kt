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
}
