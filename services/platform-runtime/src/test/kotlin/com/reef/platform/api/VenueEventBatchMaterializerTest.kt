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
