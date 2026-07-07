package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VenueEventBatchMaterializerTest {
    @Test
    fun materializerCommitsBatchBeforeAckingDelivery() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        var materializedBeforeAck = false
        val delivery = RecordingVenueEventBatchDelivery(
            payloadJson = venueEventBatchJson("batch-1", "checksum-1"),
            streamSequence = 50,
            onAck = {
                materializedBeforeAck = persistence.canonicalCommandOutcome("cmd-1") != null
            }
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(delivery),
            api = api
        )

        val processed = materializer.processOnce()

        assertEquals(1, processed)
        assertEquals(1, delivery.ackCalls)
        assertEquals(0, delivery.nakCalls)
        assertEquals(0, delivery.termCalls)
        assertEquals(true, materializedBeforeAck)
        val outcome = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcome)
        assertEquals("batch-1", outcome.batchId)
        assertEquals("engine-0", outcome.shardId)
        assertEquals(2, outcome.partition)
        assertEquals(100L, outcome.streamSequence)
        assertEquals("accepted", outcome.resultStatus)
        assertEquals(1, VenueEventBatchMaterializerMetrics.snapshot().materialized)
    }

    @Test
    fun materializerNaksWhenCanonicalPersistenceRejectsBatch() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(venueEventBatch("batch-conflict", "checksum-original"))
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val delivery = RecordingVenueEventBatchDelivery(
            payloadJson = venueEventBatchJson("batch-conflict", "checksum-conflict"),
            streamSequence = 51
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(delivery),
            api = api
        )

        materializer.processOnce()

        assertEquals(0, delivery.ackCalls)
        assertEquals(1, delivery.nakCalls)
        assertEquals(0, delivery.termCalls)
        assertEquals(1, VenueEventBatchMaterializerMetrics.snapshot().failed)
    }

    @Test
    fun materializerTermsUnsupportedSubjectsAndMalformedPayloads() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val unsupported = RecordingVenueEventBatchDelivery(
            subject = "reef.venue.events.v1.p00.Unknown",
            payloadJson = venueEventBatchJson("batch-unsupported", "checksum-unsupported"),
            streamSequence = 52
        )
        val malformed = RecordingVenueEventBatchDelivery(
            payloadJson = "not-json",
            streamSequence = 53
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(unsupported, malformed),
            api = api
        )

        materializer.processOnce()

        assertEquals(1, unsupported.termCalls)
        assertEquals(0, unsupported.ackCalls + unsupported.nakCalls)
        assertEquals(1, malformed.termCalls)
        assertEquals(0, malformed.ackCalls + malformed.nakCalls)
        val stats = VenueEventBatchMaterializerMetrics.snapshot()
        assertEquals(1, stats.unsupported)
        assertEquals(1, stats.failed)
    }

    @Test
    fun replayGateAcceptsDuplicateBatchAndRejectsChecksumDrift() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val originalPayload = resourceText("venue-event-batches/submit-accepted-v1.json")
        val replay = RecordingVenueEventBatchDelivery(
            payloadJson = originalPayload,
            streamSequence = 3001
        )
        val duplicateReplay = RecordingVenueEventBatchDelivery(
            payloadJson = originalPayload,
            streamSequence = 3001
        )
        val checksumConflict = RecordingVenueEventBatchDelivery(
            payloadJson = originalPayload.replace("checksum-replay-gate-1", "checksum-replay-gate-conflict"),
            streamSequence = 3001
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(replay, duplicateReplay, checksumConflict),
            api = api
        )

        val processed = materializer.processOnce()

        assertEquals(3, processed)
        assertEquals(1, replay.ackCalls)
        assertEquals(1, duplicateReplay.ackCalls)
        assertEquals(0, replay.nakCalls + duplicateReplay.nakCalls)
        assertEquals(0, checksumConflict.ackCalls)
        assertEquals(1, checksumConflict.nakCalls)
        val outcome = persistence.canonicalCommandOutcome("cmd-replay-gate-1")
        assertNotNull(outcome)
        assertEquals("batch-replay-gate-1", outcome.batchId)
        assertEquals("accepted", outcome.resultStatus)
        val stats = VenueEventBatchMaterializerMetrics.snapshot()
        assertEquals(2, stats.materialized)
        assertEquals(1, stats.failed)
    }

    @Test
    fun materializerKeepsFirstOutcomeWhenRedeliveredCommandProducesConflictingResult() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val accepted = RecordingVenueEventBatchDelivery(
            payloadJson = venueEventBatchJson("batch-crash-1", "checksum-crash-1"),
            streamSequence = 60
        )
        // Simulates engine crash-then-redelivery: same commandId, a *different*
        // batchId (a real redelivery produces a new VenueEventBatch, not a byte-identical
        // replay of the old one), and a conflicting rejected outcome because the
        // engine's own duplicate-order-id guard rejected the second attempt.
        val redeliveredRejected = RecordingVenueEventBatchDelivery(
            payloadJson = JsonCodec.writeObject(
                "batchId" to "batch-crash-2",
                "shardId" to "engine-0",
                "partition" to 2,
                "commandStream" to "REEF_COMMANDS",
                "eventStream" to "REEF_VENUE_EVENTS",
                "firstSequence" to 101L,
                "lastSequence" to 101L,
                "commandCount" to 1,
                "createdAt" to "2026-07-04T18:00:01Z",
                "payloadChecksum" to "checksum-crash-2",
                "outcomes" to listOf(
                    mapOf(
                        "commandId" to "cmd-1",
                        "commandType" to "SubmitOrder",
                        "streamSequence" to 101L,
                        "deliveredCount" to 2L,
                        "payloadHash" to "payload-hash-1",
                        "instrumentId" to "AAPL",
                        "orderId" to "ord-1",
                        "status" to "rejected",
                        "result" to mapOf("rejected" to mapOf("code" to "DUPLICATE_ORDER_ID"))
                    )
                )
            ),
            streamSequence = 61
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(accepted, redeliveredRejected),
            api = api
        )

        val processed = materializer.processOnce()

        assertEquals(2, processed)
        assertEquals(1, accepted.ackCalls)
        assertEquals(1, redeliveredRejected.ackCalls)
        assertEquals(0, accepted.nakCalls + redeliveredRejected.nakCalls)
        val outcome = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcome)
        assertEquals("batch-crash-1", outcome.batchId)
        assertEquals("accepted", outcome.resultStatus)
        assertEquals(1L, VenueEventBatchMaterializerMetrics.snapshot().materializedOutcomes)
    }

    /**
     * WORK_PLAN.md crash/restart scenario 4: "materializer commits compact canonical
     * rows then exits before event-batch offset commit." `materializerCommitsBatchBeforeAckingDelivery`
     * above already proves the ordering (canonical commit happens before ack/offset
     * commit); this test proves the other half - that when the process genuinely
     * exits before the offset commit lands (ack throws/never completes) and the
     * broker redelivers the same un-acked batch, replay is idempotent: the canonical
     * row is not duplicated and the redelivered attempt still converges on a
     * successful ack this time.
     */
    @Test
    fun materializerExitsBeforeOffsetCommitAndRedeliveryReplaysIdempotently() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val payload = venueEventBatchJson("batch-offset-crash-1", "checksum-offset-crash-1")

        // First delivery: canonical commit succeeds, but the process exits before the
        // offset/ack commit completes (simulated by an ack() that throws, i.e. the
        // broker never receives confirmation and will redeliver).
        val crashingDelivery = AckFailsOnceDelivery(payloadJson = payload, streamSequence = 70)
        val firstMaterializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(crashingDelivery),
            api = api
        )
        firstMaterializer.processOnce()

        assertEquals(1, crashingDelivery.ackAttempts)
        assertEquals(0, crashingDelivery.nakCalls)
        assertEquals(0, crashingDelivery.termCalls)
        val outcomeAfterCrash = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcomeAfterCrash)
        assertEquals("batch-offset-crash-1", outcomeAfterCrash.batchId)
        assertEquals(1, VenueEventBatchMaterializerMetrics.snapshot().ackFailed)

        // "Restart": a brand new materializer instance over the same durable
        // persistence receives the redelivered (still-unacked) batch and must not
        // duplicate the canonical row, but must still converge on a successful ack.
        val redelivered = RecordingVenueEventBatchDelivery(payloadJson = payload, streamSequence = 70, deliveredCount = 2)
        val restartedMaterializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(redelivered),
            api = api
        )
        restartedMaterializer.processOnce()

        assertEquals(1, redelivered.ackCalls)
        assertEquals(0, redelivered.nakCalls)
        val outcomeAfterReplay = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcomeAfterReplay)
        assertEquals("batch-offset-crash-1", outcomeAfterReplay.batchId)
        assertEquals("accepted", outcomeAfterReplay.resultStatus)
        // Same canonical row identity (batchId/streamSequence) survives the
        // redelivery unchanged - the replay was a checksum-matched no-op insert,
        // not a duplicate row.
        assertEquals(outcomeAfterCrash.batchId, outcomeAfterReplay.batchId)
        assertEquals(outcomeAfterCrash.streamSequence, outcomeAfterReplay.streamSequence)
    }

    private fun venueEventBatch(batchId: String, checksum: String): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = batchId,
            shardId = "engine-0",
            partition = 2,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 100,
            lastSequence = 100,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = checksum,
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "cmd-1",
                    commandType = "SubmitOrder",
                    streamSequence = 100,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-1",
                    instrumentId = "AAPL",
                    orderId = "ord-1",
                    resultStatus = "accepted",
                    resultPayloadJson = """{"accepted":{"eventId":"evt-1"}}"""
                )
            )
        )
    }

    private fun venueEventBatchJson(batchId: String, checksum: String): String {
        return JsonCodec.writeObject(
            "batchId" to batchId,
            "shardId" to "engine-0",
            "partition" to 2,
            "commandStream" to "REEF_COMMANDS",
            "eventStream" to "REEF_VENUE_EVENTS",
            "firstSequence" to 100L,
            "lastSequence" to 100L,
            "commandCount" to 1,
            "createdAt" to "2026-07-04T18:00:00Z",
            "payloadChecksum" to checksum,
            "outcomes" to listOf(
                mapOf(
                    "commandId" to "cmd-1",
                    "commandType" to "SubmitOrder",
                    "streamSequence" to 100L,
                    "deliveredCount" to 1L,
                    "payloadHash" to "payload-hash-1",
                    "instrumentId" to "AAPL",
                    "orderId" to "ord-1",
                    "status" to "accepted",
                    "result" to mapOf("accepted" to mapOf("eventId" to "evt-1"))
                )
            )
        )
    }

    private fun resourceText(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        requireNotNull(stream) { "missing test resource: $path" }
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }
}

private class FixedVenueEventBatchSource(
    private vararg val deliveries: VenueEventBatchDelivery
) : VenueEventBatchSource {
    private var delivered = false

    override fun fetch(batchSize: Int, timeout: java.time.Duration): List<VenueEventBatchDelivery> {
        if (delivered) return emptyList()
        delivered = true
        return deliveries.toList()
    }
}

private class RecordingVenueEventBatchDelivery(
    override val subject: String = "reef.venue.events.v1.p00.VenueEventBatch",
    override val payloadJson: String,
    override val streamSequence: Long = 1,
    override val deliveredCount: Long = 1,
    private val onAck: () -> Unit = {}
) : VenueEventBatchDelivery {
    var ackCalls = 0
    var nakCalls = 0
    var termCalls = 0

    override fun ack() {
        onAck()
        ackCalls++
    }

    override fun nak() {
        nakCalls++
    }

    override fun term() {
        termCalls++
    }
}

/**
 * Simulates the process exiting right at ack()/offset-commit time: the canonical
 * materialization already ran (processDelivery calls api.materializeVenueEventBatch
 * before ack), but the delivery's ack throws every time, standing in for "the process
 * crashed before the broker ever received the offset commit" so the broker will
 * redeliver this exact message.
 */
private class AckFailsOnceDelivery(
    override val subject: String = "reef.venue.events.v1.p00.VenueEventBatch",
    override val payloadJson: String,
    override val streamSequence: Long = 1,
    override val deliveredCount: Long = 1
) : VenueEventBatchDelivery {
    var ackAttempts = 0
    var nakCalls = 0
    var termCalls = 0

    override fun ack() {
        ackAttempts++
        error("simulated process exit before offset commit")
    }

    override fun nak() {
        nakCalls++
    }

    override fun term() {
        termCalls++
    }
}
