package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.nio.charset.StandardCharsets
import org.apache.kafka.clients.consumer.ConsumerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class VenueEventBatchMaterializerTest {
    @Test
    fun kafkaMaterializerReadsOnlyCommittedTransactions() {
        val properties = kafkaVenueEventConsumerProperties(
            bootstrapServers = "localhost:9092",
            groupId = "test-group",
            clientId = "test-client",
            maxPollRecords = 50
        )

        assertEquals("read_committed", properties[ConsumerConfig.ISOLATION_LEVEL_CONFIG])
        assertEquals("false", properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG])
        assertEquals("50", properties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG])
    }

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
        val stats = VenueEventBatchMaterializerMetrics.snapshot()
        assertEquals(1, stats.materialized)
        assertEquals(50L, stats.lastFetchedStreamSequence)
        assertEquals(50L, stats.lastMaterializedStreamSequence)
        assertEquals("batch-1", stats.lastMaterializedBatchId)
        assertEquals(2, stats.lastMaterializedPartition)
        assertEquals(100L, stats.lastMaterializedFirstSequence)
        assertEquals(100L, stats.lastMaterializedLastSequence)
        assertEquals(0L, stats.materializerLag)
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
    fun materializerRejectsDifferentBatchClaimingExistingCommand() {
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
        assertEquals(0, redeliveredRejected.ackCalls)
        assertEquals(1, redeliveredRejected.nakCalls)
        assertEquals(0, accepted.nakCalls)
        val outcome = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcome)
        assertEquals("batch-crash-1", outcome.batchId)
        assertEquals("accepted", outcome.resultStatus)
        assertEquals(1L, VenueEventBatchMaterializerMetrics.snapshot().materializedOutcomes)
        assertEquals(1L, VenueEventBatchMaterializerMetrics.snapshot().failed)
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
        assertEquals(1, crashingDelivery.nakCalls)
        assertEquals(0, crashingDelivery.termCalls)
        val outcomeAfterCrash = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcomeAfterCrash)
        assertEquals("batch-offset-crash-1", outcomeAfterCrash.batchId)
        val statsAfterCrash = VenueEventBatchMaterializerMetrics.snapshot()
        assertEquals(1, statsAfterCrash.ackFailed)
        assertEquals(1, statsAfterCrash.materialized)
        assertEquals("batch-offset-crash-1", statsAfterCrash.lastMaterializedBatchId)
        assertEquals(70L, statsAfterCrash.lastFetchedStreamSequence)
        assertEquals(70L, statsAfterCrash.lastMaterializedStreamSequence)
        assertEquals(0L, statsAfterCrash.materializerLag)

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

    @Test
    fun localAckFailureHookRequiresInternalHttpEnabledAndFailsOneAckOnly() {
        val source = FixedVenueEventBatchSource(
            RecordingVenueEventBatchDelivery(payloadJson = venueEventBatchJson("batch-hook-1", "checksum-hook-1")),
            RecordingVenueEventBatchDelivery(payloadJson = venueEventBatchJson("batch-hook-2", "checksum-hook-2"))
        )
        val unsafeLookup = mapOf(
            "VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE" to "true",
            "PLATFORM_INTERNAL_HTTP_MODE" to "local"
        )::get

        assertFailsWith<IllegalArgumentException> {
            venueEventBatchSourceWithLocalFaultHooks(source, unsafeLookup)
        }

        val first = RecordingVenueEventBatchDelivery(payloadJson = venueEventBatchJson("batch-hook-3", "checksum-hook-3"))
        val second = RecordingVenueEventBatchDelivery(payloadJson = venueEventBatchJson("batch-hook-4", "checksum-hook-4"))
        val safeLookup = mapOf(
            "VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE" to "true",
            "PLATFORM_INTERNAL_HTTP_MODE" to "enabled"
        )::get
        val wrapped = venueEventBatchSourceWithLocalFaultHooks(FixedVenueEventBatchSource(first, second), safeLookup)
        val deliveries = wrapped.fetch(10, java.time.Duration.ZERO)

        assertFailsWith<IllegalStateException> {
            deliveries[0].ack()
        }
        deliveries[0].ack()
        deliveries[1].ack()

        assertEquals(1, first.ackCalls)
        assertEquals(1, second.ackCalls)
    }

    @Test
    fun stopAfterAckFailureStopsProcessingAfterCanonicalCommit() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val delivery = AckFailsOnceDelivery(
            payloadJson = venueEventBatchJson("batch-stop-after-ack-failure", "checksum-stop-after-ack-failure"),
            streamSequence = 90
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(delivery),
            api = api,
            stopAfterAckFailure = true
        )

        assertFailsWith<IllegalStateException> {
            materializer.processOnce()
        }

        assertNotNull(persistence.canonicalCommandOutcome("cmd-1"))
        assertEquals(1, VenueEventBatchMaterializerMetrics.snapshot().ackFailed)
    }

    @Test
    fun materializerProcessesFetchedDeliveriesByStreamSequence() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val ackOrder = mutableListOf<String>()
        val later = RecordingVenueEventBatchDelivery(
            payloadJson = venueEventBatchJson(
                batchId = "batch-sort-later",
                checksum = "checksum-sort-later",
                commandId = "cmd-sort-later",
                orderId = "ord-sort-later",
                streamSequence = 101
            ),
            streamSequence = 101,
            onAck = { ackOrder.add("later") }
        )
        val earlier = RecordingVenueEventBatchDelivery(
            payloadJson = venueEventBatchJson(
                batchId = "batch-sort-earlier",
                checksum = "checksum-sort-earlier",
                commandId = "cmd-sort-earlier",
                orderId = "ord-sort-earlier",
                streamSequence = 100
            ),
            streamSequence = 100,
            onAck = { ackOrder.add("earlier") }
        )
        val source = FixedVenueEventBatchSource(later, earlier)
        val materializer = VenueEventBatchMaterializer(
            source = source,
            api = api
        )

        assertEquals(2, materializer.processOnce())

        assertEquals(listOf("earlier", "later"), ackOrder)
        assertEquals(1, source.ackBatchCalls)
        assertNotNull(persistence.canonicalCommandOutcome("cmd-sort-earlier"))
        assertNotNull(persistence.canonicalCommandOutcome("cmd-sort-later"))
    }

    @Test
    fun semanticChecksumMatchesMatchingEngineVectorAndRejectsBodyDrift() {
        VenueEventBatchMaterializerMetrics.resetForTests()
        val payload = semanticChecksumVectorPayload()
        val expected = "83bf9c8f68dfe9eff49e35578ae3e20f3b4b4b4feb08f5636a677bcf9be9da7c"
        val excluded = setOf("createdAt", "payloadChecksum", "payloadChecksumAlgorithm")
        assertEquals(expected, JsonCodec.parseObject(payload).semanticSha256(excluded))

        val persistence = InMemoryRuntimePersistence()
        val valid = RecordingVenueEventBatchDelivery(payloadJson = payload, streamSequence = 101)
        val drifted = RecordingVenueEventBatchDelivery(
            payloadJson = payload.replace("eng-1", "eng-drift"),
            streamSequence = 102
        )
        val materializer = VenueEventBatchMaterializer(
            source = FixedVenueEventBatchSource(valid, drifted),
            api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        )

        assertEquals(2, materializer.processOnce())
        assertEquals(1, valid.ackCalls)
        assertEquals(1, drifted.nakCalls)
        assertEquals(0, drifted.ackCalls + drifted.termCalls)
        assertEquals(1, VenueEventBatchMaterializerMetrics.snapshot().failed)
    }

    private fun semanticChecksumVectorPayload(): String {
        return JsonCodec.writeObject(
            "batchId" to "engine-0-p2-101-101",
            "shardId" to "engine-0",
            "partition" to 2,
            "commandStream" to "REEF_COMMANDS",
            "eventStream" to "REEF_VENUE_EVENTS",
            "firstSequence" to 101L,
            "lastSequence" to 101L,
            "commandCount" to 1,
            "createdAt" to "2026-07-19T12:00:00Z",
            "payloadChecksum" to "83bf9c8f68dfe9eff49e35578ae3e20f3b4b4b4feb08f5636a677bcf9be9da7c",
            "payloadChecksumAlgorithm" to "sha256-reef-canonical-v1",
            "outcomes" to listOf(
                mapOf(
                    "commandId" to "cmd-1",
                    "commandType" to "SubmitOrder",
                    "streamSequence" to 101L,
                    "deliveredCount" to 1L,
                    "payloadHash" to "payload-hash-1",
                    "instrumentId" to "AAPL",
                    "orderId" to "ord-1",
                    "status" to "accepted",
                    "result" to mapOf(
                        "accepted" to mapOf(
                            "eventId" to "evt-1",
                            "orderId" to "ord-1",
                            "engineOrderId" to "eng-1",
                            "occurredAt" to "2026-07-19T12:00:00Z"
                        )
                    )
                )
            )
        )
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

    private fun venueEventBatchJson(
        batchId: String,
        checksum: String,
        commandId: String = "cmd-1",
        orderId: String = "ord-1",
        streamSequence: Long = 100L
    ): String {
        return JsonCodec.writeObject(
            "batchId" to batchId,
            "shardId" to "engine-0",
            "partition" to 2,
            "commandStream" to "REEF_COMMANDS",
            "eventStream" to "REEF_VENUE_EVENTS",
            "firstSequence" to streamSequence,
            "lastSequence" to streamSequence,
            "commandCount" to 1,
            "createdAt" to "2026-07-04T18:00:00Z",
            "payloadChecksum" to checksum,
            "outcomes" to listOf(
                mapOf(
                    "commandId" to commandId,
                    "commandType" to "SubmitOrder",
                    "streamSequence" to streamSequence,
                    "deliveredCount" to 1L,
                    "payloadHash" to "payload-hash-$commandId",
                    "instrumentId" to "AAPL",
                    "orderId" to orderId,
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
    var ackBatchCalls = 0

    override fun fetch(batchSize: Int, timeout: java.time.Duration): List<VenueEventBatchDelivery> {
        if (delivered) return emptyList()
        delivered = true
        return deliveries.toList()
    }

    override fun ackBatch(deliveries: List<VenueEventBatchDelivery>) {
        ackBatchCalls++
        deliveries.forEach { it.ack() }
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
 * materialization already ran (the materializer commits its parsed batch group
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
