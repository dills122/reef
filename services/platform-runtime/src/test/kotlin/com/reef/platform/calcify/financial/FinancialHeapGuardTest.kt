package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Every numeric fixture is validation-only; no measured heap/capacity evidence. */
class FinancialHeapGuardTest {
    private val json = ObjectMapper()
    private fun bounds() = FinancialHeapBounds(2, 4, 100, 10, 20, 1000, 100)
    private fun guard(used: () -> Long = { 50 }, max: () -> Long = { 1000 }, clock: () -> Long = { 0 },
        sampleAt: () -> Long = clock, estimate: Long = 172, poll: Long = 250): FinancialHeapGuard =
        FinancialHeapGuard(bounds(), estimate, 1000, { FinancialHeapSnapshot(max(), used(), sampleAt()) }, clock, poll)

    @Test fun finiteEstimateCountsTimedAndAgedIdentitiesPlusPendingAndBothReserves() {
        assertEquals(172, bounds().estimate(10, 5, 3))
        assertEquals(800, FinancialHeapBounds.limit(1000))
        assertFailsWith<IllegalArgumentException> { bounds().estimate(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { bounds().estimate(1001, 0, 0) }
        assertFailsWith<IllegalArgumentException> { bounds().estimate(0, 0, 101) }
    }
    @Test fun checkedLongOverflowAndUnknownOrUnauthorizedMaximumRefuse() {
        assertFailsWith<ArithmeticException> { FinancialHeapBounds(Long.MAX_VALUE, 1, 1, 1, 1, Long.MAX_VALUE, 1).estimate(2, 0, 0) }
        assertFailsWith<ArithmeticException> { FinancialHeapBounds(1, 1, 1, 1, 1, Long.MAX_VALUE, 1).estimate(Long.MAX_VALUE, 1, 0) }
        for (max in listOf(0L, -1L, Long.MAX_VALUE, FinancialHeapBounds.AUTHORIZED_MAX + 1))
            assertFailsWith<IllegalArgumentException> { FinancialHeapBounds.limit(max) }
        assertFailsWith<IllegalArgumentException> { FinancialHeapBounds(0, 1, 1, 1, 1, 1, 1) }
    }
    @Test fun jsonNumbersCannotBeStringsDecimalsNegativeUnsafeOrUnknown() {
        for (value in listOf("null", "\"1\"", "1.5", "-1", "0", "9007199254740992", "9223372036854775808"))
            assertFailsWith<IllegalArgumentException>(value) { FinancialHeapBounds.integer(json.readTree(value), "control") }
        assertEquals(0, FinancialHeapBounds.integer(json.readTree("0"), "control", false))
    }
    @Test fun actualSmallerMaximumRecomputesLimitAndEqualityRejects() {
        guard(max = { 700 }, estimate = 560).use { assertFailsWith<IllegalStateException> { it.start() } }
        guard(max = { 700 }, estimate = 559).use { it.start(); assertEquals(560, it.admissionLimitBytes) }
        guard(used = { 800 }).use { assertFailsWith<IllegalStateException> { it.start() } }
        guard(max = { FinancialHeapBounds.AUTHORIZED_MAX + 1 }).use { assertFailsWith<IllegalStateException> { it.start() } }
    }
    @Test fun beforeSetupBaselineRefusalPreventsAnyClientOrTopicSetup() {
        var setups = 0; val g = guard(used = { 101 })
        assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) { setups++ } }
        assertEquals(0, setups); assertTrue(g.isClosed)
    }
    @Test fun warmedBaselineRefusalPreventsAnySendThroughActualLifecycle() {
        val used = AtomicLong(50); var sends = 0; val g = guard(used = used::get)
        assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) {
            used.set(101); it.baseline("producer-warmed-before-aging"); sends++
        } }
        assertEquals(0, sends); assertTrue(g.isClosed)
    }
    @Test fun breachSticksAfterGcAndMaximumCannotChange() {
        val used = AtomicLong(50); val max = AtomicLong(1000)
        guard(used::get, max::get).use { g ->
            g.start(); used.set(800); assertFailsWith<IllegalStateException> { g.refresh("send") }
            val first = assertNotNull(g.failure); used.set(1)
            assertFailsWith<IllegalStateException> { g.refresh("after-gc") }; assertSame(first, g.failure)
        }
        guard(max = { max.get() }).use { g -> g.start(); max.set(999); assertFailsWith<IllegalStateException> { g.refresh("changed-max") } }
    }
    @Test fun sensorThrowUnknownMaximumAndInvalidUsedRefuseSticky() {
        var throws = false
        val g = FinancialHeapGuard(bounds(), 172, 1000, { if (throws) error("sensor-failure") else FinancialHeapSnapshot(1000, 50, 0) }, { 0 })
        g.use { it.start(); throws = true; assertFailsWith<IllegalStateException> { it.refresh("sensor") }
            val first = it.failure; throws = false; assertFailsWith<IllegalStateException> { it.refresh("recovered") }; assertSame(first, it.failure) }
        for ((max, used) in listOf(0L to 0L, -1L to 1L, 1000L to -1L, 1000L to 1001L))
            guard({ used }, { max }).use { assertFailsWith<IllegalStateException> { it.start() } }
    }
    @Test fun staleFutureAndMissingSnapshotRefuseSticky() {
        val time = AtomicLong(0)
        guard(clock = time::get, sampleAt = { 0 }).use { g ->
            g.start(); time.set(5_000_000_000); assertFailsWith<IllegalStateException> { g.checkpoint("stalled-sensor") }
            val first = g.failure; time.set(0); assertFailsWith<IllegalStateException> { g.refresh("fresh-again") }; assertSame(first, g.failure)
        }
        guard(clock = { 0 }, sampleAt = { 1 }).use { assertFailsWith<IllegalStateException> { it.start() } }
        guard().use { assertFailsWith<IllegalStateException> { it.checkpoint("no-start") }; assertNotNull(it.failure) }
    }
    @Test fun sensorRemainsIndependentWhilePhysicalCollectorStalls() {
        val stalled = CountDownLatch(1); val release = CountDownLatch(1); val observed = CountDownLatch(1); val used = AtomicLong(50)
        val physical = Thread { stalled.countDown(); release.await() }.apply { start() }
        val g = FinancialHeapGuard(bounds(), 172, 1000, {
            val value = used.get(); if (value == 800L) observed.countDown(); FinancialHeapSnapshot(1000, value, System.nanoTime())
        }, pollMillis = 5)
        try {
            assertTrue(stalled.await(1, TimeUnit.SECONDS)); g.start(); used.set(800)
            assertTrue(observed.await(1, TimeUnit.SECONDS)); awaitFailure(g)
            assertFailsWith<IllegalStateException> { g.checkpoint("physical-still-stalled") }; assertTrue(physical.isAlive)
        } finally { g.close(); release.countDown(); physical.join(1000) }
        assertTrue(g.isClosed); assertFalse(physical.isAlive)
    }
    @Test fun actualReplayBatchFailureAfterWorkersCloseBlocksArtifactAndStopsSensor() {
        val dir = Files.createTempDirectory("heap-replay-control"); val output = dir.resolve("measurement.json")
        val used = AtomicLong(50); val g = guard(used::get); var restored = 0; var workerClosed = false
        try {
            assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) {
                workerClosed = true; it.refresh("workers-closed")
                FinancialRateProbe.heapReplayBatch(it, listOf(1, 2)) {
                    restored++; if (restored == 1) { used.set(800); g.refresh("result-only-replay-record") }
                }
                it.publish(output, "success")
            } }
            assertTrue(workerClosed); assertEquals(1, restored); assertFalse(Files.exists(output)); assertTrue(g.isClosed)
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(dir) }
    }
    @Test fun actualLifecycleFailureAtEveryPhaseCannotPublishAfterward() {
        val phases = listOf("startup", "aging", "send", "drain", "workers-closed", "before-owner-digest", "result-only-replay", "final-result")
        for (phase in phases) {
            val used = AtomicLong(50); val g = guard(used::get); var published = false
            assertFailsWith<IllegalStateException>(phase) { FinancialRateProbe.withHeapProtection(g) {
                used.set(800); it.refresh(phase); published = true
            } }
            assertFalse(published, phase); assertTrue(g.isClosed, phase)
        }
    }
    @Test fun finalSampleFailureLeavesNoArtifactAndExceptionalBodyClosesGuard() {
        val dir = Files.createTempDirectory("heap-publish-control"); val output = dir.resolve("measurement.json")
        val used = AtomicLong(50); val g = guard(used::get)
        try {
            assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) { used.set(800); it.publish(output, "success") } }
            assertFalse(Files.exists(output)); assertEquals(0, Files.list(dir).use { it.count() }); assertTrue(g.isClosed)
            val exceptional = guard(); assertFailsWith<IllegalArgumentException> { FinancialRateProbe.withHeapProtection(exceptional) { throw IllegalArgumentException("body") } }
            assertTrue(exceptional.isClosed)
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(dir) }
    }
    @Test fun successfulActualReplayLifecyclePublishesOnlyAfterFinalGuardClose() {
        val dir = Files.createTempDirectory("heap-success-control"); val output = dir.resolve("measurement.json"); val g = guard(); var records = 0
        try {
            FinancialRateProbe.withHeapProtection(g) { FinancialRateProbe.heapReplayBatch(it, listOf(1, 2, 3)) { records++ }; it.publish(output, "validation-only") }
            assertEquals(3, records); assertEquals("validation-only", Files.readString(output)); assertTrue(g.isClosed)
            assertEquals(true, g.telemetry()["sampled"])
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(dir) }
    }
    @Test fun actualMainMissingHeapContractRejectsBeforeConfigStateDirOrBrokerSetup() {
        val dir = Files.createTempDirectory("heap-main-refusal"); val config = dir.resolve("config.json"); val spec = dir.resolve("policy.json"); val output = dir.resolve("measurement.json"); val state = dir.resolve("must-not-exist")
        try {
            Files.writeString(config, "{\"stateDir\":\"$state\",\"policy\":{}}")
            val p = json.createObjectNode().put("status", "FROZEN").put("expectedTimedTrades", 150000)
            p.set<ObjectNode>("arm", json.createObjectNode().put("rate", 2500).put("seconds", 60).put("state", "fresh"))
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(p))); p.put("policySha256", sha)
            Files.writeString(spec, json.writeValueAsString(p))
            val e = assertFailsWith<IllegalArgumentException> { FinancialRateProbe.main(arrayOf("run", "unreachable-broker", "financial-s1-no-client", config.toString(), "--financial-rate-policy", spec.toString(), "--financial-rate-measurement", output.toString())) }
            assertTrue(e.message!!.contains("HEAP_CONSERVATIVE_EVIDENCE_REQUIRED")); assertFalse(Files.exists(state)); assertFalse(Files.exists(output))
        } finally { Files.deleteIfExists(spec); Files.deleteIfExists(config); Files.deleteIfExists(output); Files.deleteIfExists(dir) }
    }
    @Test fun actualBlockedWaitAbortsOnDedicatedSensorFailureAndInterruptsOwnedOperation() {
        val used = AtomicLong(50); val entered = CountDownLatch(1); val interrupted = CountDownLatch(1)
        val g = FinancialHeapGuard(bounds(), 172, 1000, { FinancialHeapSnapshot(1000, used.get(), System.nanoTime()) }, pollMillis = 5)
        val trigger = Thread { entered.await(); used.set(800) }.apply { start() }
        try {
            assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) {
                FinancialRateProbe.heapAwait(it, "blocked-flush-control", 1000) {
                    entered.countDown()
                    try { CountDownLatch(1).await() } catch (_: InterruptedException) { interrupted.countDown() }
                }
            } }
            assertTrue(interrupted.await(1, TimeUnit.SECONDS)); assertTrue(g.isClosed)
        } finally { trigger.join(1000); g.close() }
    }
    @Test fun prematureCloseBeforeActualReplayCannotSucceed() {
        val g = guard(); var restored = 0
        assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) {
            it.close(); FinancialRateProbe.heapReplayBatch(it, listOf(1)) { restored++ }
        } }
        assertEquals(0, restored); assertTrue(g.isClosed)
    }
    @Test fun parityAndMeasurementAreBothAbsentWhenFinalStagedSampleBreaches() {
        val dir = Files.createTempDirectory("heap-two-artifacts"); val output = dir.resolve("measurement.json"); val parity = dir.resolve("parity.json")
        var samples = 0
        val g = FinancialHeapGuard(bounds(), 172, 1000, { samples++; FinancialHeapSnapshot(1000, if (samples >= 3) 800 else 50, 0) }, { 0 })
        try {
            assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) { it.publish(output, "success", parity to "exact-parity") } }
            assertFalse(Files.exists(output)); assertFalse(Files.exists(parity)); assertEquals(0, Files.list(dir).use { it.count() }); assertTrue(g.isClosed)
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(parity); Files.deleteIfExists(dir) }
    }
    @Test fun artifactPeakScopeExplainsFinalHealthySamplesExcludedFromExportedPeak() {
        val exported = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        for (mode in listOf("calibrate", "run")) {
            val dir = Files.createTempDirectory("heap-peak-scope-$mode"); val output = dir.resolve("artifact.json")
            val used = AtomicLong(50); val g = guard(used::get)
            try {
                FinancialRateProbe.withHeapProtection(g) {
                    val snapshot = FinancialRateProbe.heapArtifactSnapshot(it, 50)
                    val observation = snapshot.observation
                    val resources = mapOf("heapPeakBytes" to snapshot.resourcePeakBytes, "heapPeakScope" to snapshot.peakScope)
                    val artifact = if (mode == "calibrate") mapOf("heapObservation" to observation)
                        else mapOf("heapObservation" to observation, "resources" to resources)
                    val contents = json.writeValueAsString(artifact)
                    used.set(700); it.publish(output, contents)
                }
                val artifact = json.readTree(Files.readString(output)); exported.add(artifact)
                assertEquals(50, artifact["heapObservation"]["heapPeakBytes"].asLong())
                if (mode == "run") assertEquals(50, artifact["resources"]["heapPeakBytes"].asLong())
                assertEquals(700L, g.telemetry()["heapPeakBytes"])
                assertEquals(null, g.failure)
                assertFalse(g.telemetry().containsKey("reportedPeakScope"), "Post-publication guard telemetry must retain full sampled lifetime scope")
                println(json.writeValueAsString(mapOf("validationOnly" to true, "mode" to mode, "exportedArtifact" to artifact,
                    "finalHealthySamplePeakBytes" to g.telemetry()["heapPeakBytes"])))
            } finally { Files.deleteIfExists(output); Files.deleteIfExists(dir) }
        }
        val expected = "Sampled through result assembly snapshot; final serialization and staged writes excluded"
        exported.forEach { artifact -> assertEquals(expected, artifact["heapObservation"].path("reportedPeakScope").asText()) }
        assertEquals(expected, exported.last()["resources"].path("heapPeakScope").asText())
    }
    private fun diagnostic(used: () -> Long = { 150 }, max: () -> Long = { 1000 }, clock: () -> Long = { 0 },
        sampleAt: () -> Long = clock, ceiling: Long? = null): FinancialHeapGuard =
        FinancialHeapGuard.diagnosticBootstrap(1000, 100, 0, 1000, ceiling,
            { FinancialHeapSnapshot(max(), used(), sampleAt()) }, clock)

    @Test fun diagnosticRequiresFrozenCohortAndAuthorizedCapability() {
        for ((cohort, aged) in listOf(999L to 100L to 0L, 1001L to 100L to 0L,
            1000L to 99L to 0L, 1000L to 101L to 0L, 1000L to 100L to 1L)) {
            assertFailsWith<IllegalArgumentException> {
                FinancialHeapGuard.diagnosticBootstrap(cohort.first, cohort.second, aged, 1000)
            }
        }
        for (cap in listOf(0L, FinancialHeapBounds.AUTHORIZED_MAX + 1))
            assertFailsWith<IllegalArgumentException> { FinancialHeapGuard.diagnosticBootstrap(1000, 100, 0, cap) }
    }
    @Test fun diagnosticTelemetryHasSeparateSchemaNullEstimateAndNoConservativeBound() {
        diagnostic().use { g ->
            g.start(); g.baseline("warmed")
            val observation = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(g.telemetry())
            assertEquals("financial-bootstrap-heap-observation-v1", observation["schema"].asText())
            assertEquals("EMPIRICAL_BOUNDED_DIAGNOSTIC", observation["purpose"].asText())
            assertFalse(observation["heapConservativeBound"].asBoolean())
            assertTrue(observation.has("estimatedHeapBytes")); assertTrue(observation["estimatedHeapBytes"].isNull)
            assertEquals(2, observation["baselineObservations"].size())
        }
        guard().use { g -> g.start(); assertEquals("financial-heap-observation-v1", g.telemetry()["schema"])
            assertEquals(172L, g.telemetry()["estimatedHeapBytes"]); assertFalse(g.telemetry().containsKey("purpose")) }
    }
    @Test fun diagnosticActualCapEqualityAndOperationalPreloadStopRefuse() {
        diagnostic(used = { 560 }, max = { 700 }).use { assertFailsWith<IllegalStateException> { it.start() } }
        diagnostic(used = { 559 }, max = { 700 }).use { it.start(); assertEquals(560L, it.admissionLimitBytes) }
        diagnostic(max = { FinancialHeapBounds.AUTHORIZED_MAX + 1 }).use { assertFailsWith<IllegalStateException> { it.start() } }
        for (ceiling in listOf(0L, 800L)) assertFailsWith<IllegalArgumentException> { diagnostic(ceiling = ceiling) }
        diagnostic(ceiling = 200).use { g -> g.start(); assertEquals(null, g.failure) }
        diagnostic(used = { 200 }, ceiling = 200).use { assertFailsWith<IllegalStateException> { it.start() } }
        diagnostic(max = { 200 }, ceiling = 200).use { assertFailsWith<IllegalStateException> { it.start() } }
        guard(used = { 100 }).use { it.start() } // Conservative baseline equality remains accepted.
    }
    @Test fun diagnosticBreachSensorErrorStaleAndMaximumChangeRemainSticky() {
        val used = AtomicLong(150); val max = AtomicLong(1000); val time = AtomicLong(0)
        for (failureMode in listOf("breach", "stale", "max")) {
            used.set(150); max.set(1000); time.set(0)
            diagnostic(used::get, max::get, time::get, { 0 }).use { g ->
                g.start()
                when (failureMode) { "breach" -> used.set(800); "stale" -> time.set(5_000_000_000); "max" -> max.set(999) }
                assertFailsWith<IllegalStateException> { g.refresh(failureMode) }; val first = assertNotNull(g.failure)
                used.set(1); max.set(1000); time.set(0)
                assertFailsWith<IllegalStateException> { g.refresh("recovered") }; assertSame(first, g.failure)
            }
        }
        var throws = false
        FinancialHeapGuard.diagnosticBootstrap(1000, 100, 0, 1000, sensor = {
            if (throws) error("diagnostic-sensor-error") else FinancialHeapSnapshot(1000, 150, 0)
        }, clock = { 0 }).use { g ->
            g.start(); throws = true; assertFailsWith<IllegalStateException> { g.refresh("sensor") }
            val first = assertNotNull(g.failure); throws = false
            assertFailsWith<IllegalStateException> { g.refresh("sensor-recovered") }; assertSame(first, g.failure)
        }
    }
    @Test fun diagnosticDedicatedSensorSurvivesWorkerCloseAndBlocksPublication() {
        val used = AtomicLong(150); val observed = CountDownLatch(1)
        val g = FinancialHeapGuard.diagnosticBootstrap(1000, 100, 0, 1000, sensor = {
            val value = used.get(); if (value == 800L) observed.countDown()
            FinancialHeapSnapshot(1000, value, System.nanoTime())
        }, pollMillis = 5)
        val dir = Files.createTempDirectory("bootstrap-heap-sensor"); val output = dir.resolve("result.json")
        try {
            assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(g) {
                it.refresh("workers-closed"); used.set(800)
                assertTrue(observed.await(1, TimeUnit.SECONDS)); awaitFailure(g); it.publish(output, "success")
            } }
            assertFalse(Files.exists(output)); assertTrue(g.isClosed)
        } finally { g.close(); Files.deleteIfExists(output); Files.deleteIfExists(dir) }
    }
    @Test fun diagnosticStagedFailureLeavesBothArtifactsAbsentAndHealthyPublicationCloses() {
        val dir = Files.createTempDirectory("bootstrap-heap-publication"); val output = dir.resolve("result.json"); val parity = dir.resolve("parity.json")
        var samples = 0
        val failed = FinancialHeapGuard.diagnosticBootstrap(1000, 100, 0, 1000, sensor = {
            samples++; FinancialHeapSnapshot(1000, if (samples >= 3) 800 else 150, 0)
        }, clock = { 0 })
        try {
            assertFailsWith<IllegalStateException> { FinancialRateProbe.withHeapProtection(failed) { it.publish(output, "success", parity to "parity") } }
            assertFalse(Files.exists(output)); assertFalse(Files.exists(parity)); assertTrue(failed.isClosed)
            assertEquals(0, Files.list(dir).use { it.count() })
            val healthy = diagnostic()
            FinancialRateProbe.withHeapProtection(healthy) { it.refresh("managed-restart"); it.refresh("result-only-replay"); it.publish(output, "empirical-only", parity to "parity") }
            assertEquals("empirical-only", Files.readString(output)); assertTrue(healthy.isClosed)
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(parity); Files.deleteIfExists(dir) }
    }
    private fun awaitFailure(g: FinancialHeapGuard) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (g.failure == null && System.nanoTime() < end) Thread.yield()
        assertNotNull(g.failure)
    }
    @Test fun empiricalFixedHeapBudgetIsSeparateAndBreachRemainsSticky() {
        val max = FinancialHeapGuard.EMPIRICAL_MAX; val used = AtomicLong(128)
        assertFailsWith<IllegalArgumentException> { FinancialHeapBounds.limit(max) }
        assertFailsWith<IllegalArgumentException> { FinancialHeapGuard.empiricalTimed(max - 1) }
        FinancialHeapGuard.empiricalTimed(max, { FinancialHeapSnapshot(max, used.get(), 0) }, { 0 }).use { g ->
            g.start(); assertEquals(max * 4 / 5, g.admissionLimitBytes)
            assertEquals("financial-empirical-heap-observation-v1", g.telemetry()["schema"])
            assertEquals(false, g.telemetry()["heapConservativeBound"]); assertEquals(null, g.telemetry()["estimatedHeapBytes"])
            used.set(max * 4 / 5); assertFailsWith<IllegalStateException> { g.refresh("breach") }
            used.set(0); assertFailsWith<IllegalStateException> { g.refresh("after-gc") }
        }
    }
    @Test fun empiricalEightGiBRequiresSeparateExplicitAuthorizationAndExactObservedMaximum() {
        val max = FinancialHeapGuard.EMPIRICAL_8G_MAX
        assertFailsWith<IllegalArgumentException> { FinancialHeapGuard.empiricalTimed(max) }
        assertFailsWith<IllegalArgumentException> { FinancialHeapGuard.empiricalTimed(FinancialHeapGuard.EMPIRICAL_MAX, authorizedMax = max) }
        assertFailsWith<IllegalArgumentException> { FinancialHeapGuard.empiricalTimed(max, authorizedMax = max + 1) }
        FinancialHeapGuard.empiricalTimed(max, { FinancialHeapSnapshot(max, 128, 0) }, { 0 }, authorizedMax = max).use { g ->
            g.start(); assertEquals(max * 4 / 5, g.admissionLimitBytes)
            assertEquals(max, g.telemetry()["actualMaxHeapBytes"])
            assertEquals(false, g.telemetry()["heapConservativeBound"])
        }
        FinancialHeapGuard.empiricalTimed(max, { FinancialHeapSnapshot(FinancialHeapGuard.EMPIRICAL_MAX, 128, 0) }, { 0 }, authorizedMax = max).use {
            assertFailsWith<IllegalStateException> { it.start() }
        }
    }
}
