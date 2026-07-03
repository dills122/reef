package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamCommandWorkerTest {
    @Test
    fun submitWorkerPersistsCanonicalOutcomeBeforeAck() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        var ackObservedCanonical = false
        val delivery = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-1", "ord-worker-1"),
            onAck = {
                ackObservedCanonical = persistence.canonicalSubmitOutcomes()
                    .any { it.commandId == "cmd-worker-1" }
            }
        )
        val source = FixedStreamCommandSource(delivery)
        val worker = StreamCommandWorker(source = source, api = api, partition = 0)

        val processed = worker.processOnce()

        assertEquals(1, processed)
        assertEquals(1, gateway.submitCalls)
        assertEquals(1, delivery.ackCalls)
        assertTrue(ackObservedCanonical)
        assertEquals(0, delivery.nakCalls)
        assertNotNull(persistence.submitResult("cmd-worker-1"))
        assertEquals(null, persistence.acceptedOrder("ord-worker-1"))
        val canonical = persistence.canonicalSubmitOutcomes().single()
        assertEquals("run-worker-1", canonical.runId)
        assertEquals("session-worker-1", canonical.venueSessionId)
        assertEquals(0, canonical.partitionId)
        assertEquals(1, canonical.partitionSequence)
        assertEquals("REEF_COMMANDS", canonical.streamName)
        assertEquals(1, canonical.streamSequence)
        assertEquals("AAPL", canonical.instrumentId)
        assertEquals("SubmitOrder", canonical.commandType)
        assertEquals("accepted", canonical.resultStatus)
        assertTrue(canonical.payloadHash.isNotBlank())

        val projected = persistence.projectCanonicalSubmitOutcomes("runtime-normalized-submit", 100, emptyList())

        assertEquals(1, projected)
        assertNotNull(persistence.acceptedOrder("ord-worker-1"))
        assertEquals(0, persistence.projectionStatus("runtime-normalized-submit", emptyList()).lag)
    }

    @Test
    fun redeliveryAfterPersistBeforeAckDoesNotCallEngineAgain() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val payload = validSubmitBody("cmd-worker-redeliver", "ord-worker-redeliver")
        val first = RecordingDelivery(payloadJson = payload, failAck = true)
        val second = RecordingDelivery(payloadJson = payload, deliveredCount = 2)
        val source = QueueStreamCommandSource(first, second)
        val worker = StreamCommandWorker(source = source, api = api)

        worker.processOnce()
        worker.processOnce()

        assertEquals(1, gateway.submitCalls)
        assertEquals(1, first.ackCalls)
        assertEquals(1, second.ackCalls)
        assertEquals(0, first.nakCalls + second.nakCalls)
        assertNotNull(persistence.submitResult("cmd-worker-redeliver"))
        assertEquals(null, persistence.acceptedOrder("ord-worker-redeliver"))
        assertEquals(1, persistence.canonicalSubmitOutcomes().size)
    }

    @Test
    fun submitWorkerPersistsFetchedSubmitBatchBeforeAckingDeliveries() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val first = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-batch-1", "ord-worker-batch-1"),
            streamSequence = 10
        )
        val second = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-batch-2", "ord-worker-batch-2"),
            streamSequence = 11
        )
        val worker = StreamCommandWorker(
            source = FixedBatchStreamCommandSource(first, second),
            api = api,
            partition = 3
        )

        val processed = worker.processOnce()

        assertEquals(2, processed)
        assertEquals(2, gateway.submitCalls)
        assertEquals(1, first.ackCalls)
        assertEquals(1, second.ackCalls)
        assertEquals(0, first.nakCalls + second.nakCalls)
        assertNotNull(persistence.submitResult("cmd-worker-batch-1"))
        assertNotNull(persistence.submitResult("cmd-worker-batch-2"))
        assertEquals(null, persistence.acceptedOrder("ord-worker-batch-1"))
        assertEquals(null, persistence.acceptedOrder("ord-worker-batch-2"))
        assertEquals(
            listOf(10L, 11L),
            persistence.canonicalSubmitOutcomes().map { it.streamSequence }
        )
        val partition = StreamCommandWorkerMetrics.snapshot().partitions.single()
        assertEquals(3, partition.partition)
        assertEquals(2, partition.fetched)
        assertEquals(2, partition.completed)
        assertEquals(11, partition.lastCompletedStreamSequence)
    }

    @Test
    fun submitWorkerRepairsPublishedMarkerBeforeAckingDelivery() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val marker = RecordingPublicationMarker()
        var repairedBeforeAck = false
        val delivery = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-marker", "ord-worker-marker"),
            streamSequence = 77,
            onAck = {
                repairedBeforeAck = marker.marked["cmd-worker-marker"] == 77L
            }
        )
        val worker = StreamCommandWorker(
            source = FixedStreamCommandSource(delivery),
            api = api,
            publicationMarker = marker,
            partition = 5
        )

        val processed = worker.processOnce()

        assertEquals(1, processed)
        assertEquals(1, delivery.ackCalls)
        assertTrue(repairedBeforeAck)
        assertEquals(77L, marker.marked["cmd-worker-marker"])
    }

    @Test
    fun submitWorkerAcceptsIdempotentBatchPublicationMarkerCounts() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val marker = DistinctBatchPublicationMarker()
        val first = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-batch-marker-1", "ord-worker-batch-marker-1"),
            streamSequence = 90
        )
        val second = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-batch-marker-2", "ord-worker-batch-marker-2"),
            streamSequence = 91
        )
        val worker = StreamCommandWorker(
            source = FixedBatchStreamCommandSource(first, second),
            api = api,
            publicationMarker = marker,
            partition = 5
        )

        val processed = worker.processOnce()

        assertEquals(2, processed)
        assertEquals(1, first.ackCalls)
        assertEquals(1, second.ackCalls)
        assertEquals(0, first.nakCalls + second.nakCalls)
        assertEquals(
            mapOf(
                "cmd-worker-batch-marker-1" to 90L,
                "cmd-worker-batch-marker-2" to 91L
            ),
            marker.marked
        )
    }

    @Test
    fun submitWorkerNaksDeliveryWhenPublicationMarkerCannotRepair() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val delivery = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-marker-missing", "ord-worker-marker-missing"),
            streamSequence = 78
        )
        val worker = StreamCommandWorker(
            source = FixedStreamCommandSource(delivery),
            api = api,
            publicationMarker = MissingPublicationMarker,
            partition = 5
        )

        val processed = worker.processOnce()

        assertEquals(1, processed)
        assertEquals(0, delivery.ackCalls)
        assertEquals(1, delivery.nakCalls)
    }

    @Test
    fun redeliveryAfterPublicationMarkerFailureDoesNotDuplicateCanonicalOutcome() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val payload = validSubmitBody("cmd-worker-marker-redeliver", "ord-worker-marker-redeliver")
        val first = RecordingDelivery(payloadJson = payload, streamSequence = 79)
        val second = RecordingDelivery(payloadJson = payload, streamSequence = 79, deliveredCount = 2)
        val marker = FailingThenRecordingPublicationMarker()
        val worker = StreamCommandWorker(
            source = QueueStreamCommandSource(first, second),
            api = api,
            publicationMarker = marker,
            partition = 5
        )

        worker.processOnce()
        worker.processOnce()

        assertEquals(1, gateway.submitCalls)
        assertEquals(0, first.ackCalls)
        assertEquals(1, first.nakCalls)
        assertEquals(1, second.ackCalls)
        assertEquals(0, second.nakCalls)
        assertEquals(1, persistence.canonicalSubmitOutcomes().size)
        assertEquals(79L, persistence.canonicalSubmitOutcomes().single().streamSequence)
        assertEquals(79L, marker.marked["cmd-worker-marker-redeliver"])
    }

    @Test
    fun canonicalProjectorMaterializesNormalizedSubmitOutcomesAndWatermarks() {
        StreamCommandWorkerMetrics.resetForTests()
        CanonicalProjectionMetrics.resetForTests()
        val persistence = seededPersistence()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = CountingAcceptedGateway(),
                runtimePersistence = persistence
            )
        )
        val delivery = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-projector-1", "ord-projector-1"),
            streamSequence = 12
        )
        StreamCommandWorker(
            source = FixedStreamCommandSource(delivery),
            api = api,
            partition = 4
        ).processOnce()
        val projector = CanonicalProjectionWorker(
            api = api,
            projectionName = "runtime-normalized-submit",
            batchSize = 10
        )

        assertEquals(null, persistence.acceptedOrder("ord-projector-1"))
        val projected = projector.processOnce()
        val replayed = projector.processOnce()

        assertEquals(1, projected)
        assertEquals(0, replayed)
        assertNotNull(persistence.acceptedOrder("ord-projector-1"))
        assertEquals(1, persistence.acceptedOrders().size)
        val status = persistence.projectionStatus("runtime-normalized-submit")
        assertEquals(0, status.lag)
        assertEquals(12, status.watermarks.single().lastPartitionSequence)
        assertEquals(1, CanonicalProjectionMetrics.snapshot().projected)
    }

    @Test
    fun canonicalProjectorOnlyProjectsOwnedPartitions() {
        StreamCommandWorkerMetrics.resetForTests()
        CanonicalProjectionMetrics.resetForTests()
        val persistence = seededPersistence()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = CountingAcceptedGateway(),
                runtimePersistence = persistence
            )
        )
        val first = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-projector-owned-1", "ord-projector-owned-1"),
            streamSequence = 20
        )
        val second = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-projector-owned-2", "ord-projector-owned-2"),
            streamSequence = 21
        )
        StreamCommandWorker(
            source = FixedBatchStreamCommandSource(first, second),
            api = api,
            partition = 4
        ).processOnce()
        val projector = CanonicalProjectionWorker(
            api = api,
            projectionName = "runtime-normalized-submit",
            partitions = listOf(5),
            batchSize = 10
        )

        val projected = projector.processOnce()

        assertEquals(0, projected)
        assertEquals(null, persistence.acceptedOrder("ord-projector-owned-1"))
        assertEquals(0, persistence.projectionStatus("runtime-normalized-submit", listOf(5)).lag)
        assertEquals(2, persistence.projectionStatus("runtime-normalized-submit", listOf(4)).lag)
    }

    @Test
    fun unsupportedStreamCommandIsTerminated() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = CountingAcceptedGateway(),
                runtimePersistence = persistence
            )
        )
        val delivery = RecordingDelivery(
            subject = "reef.cmd.v1.p00.session-1.AAPL.CancelOrder",
            payloadJson = """{"commandId":"cmd-cancel"}"""
        )
        val worker = StreamCommandWorker(source = FixedStreamCommandSource(delivery), api = api)

        worker.processOnce()

        assertEquals(1, delivery.termCalls)
        assertEquals(0, delivery.ackCalls)
        assertEquals(0, delivery.nakCalls)
    }

    @Test
    fun workerMetricsTrackPartitionCountersAndConsumerSnapshots() {
        StreamCommandWorkerMetrics.resetForTests()
        val persistence = seededPersistence()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = CountingAcceptedGateway(),
                runtimePersistence = persistence
            )
        )
        val delivery = RecordingDelivery(
            payloadJson = validSubmitBody("cmd-worker-partition", "ord-worker-partition"),
            streamSequence = 42,
            deliveredCount = 3
        )
        val worker = StreamCommandWorker(
            source = FixedStreamCommandSource(delivery),
            api = api,
            partition = 7
        )
        StreamCommandWorkerMetrics.registerConsumerTelemetry(
            7,
            FixedTelemetrySource(
                StreamCommandConsumerSnapshot(
                    partition = 7,
                    durableName = "durable-p07",
                    filterSubject = "reef.cmd.v1.p07.>",
                    pending = 12,
                    ackPending = 2,
                    redelivered = 1,
                    ackFloorStreamSequence = 40,
                    streamLastSequence = 52,
                    streamLag = 12,
                    sampledAt = "2026-07-03T00:00:01Z"
                )
            )
        )

        worker.processOnce()

        val snapshot = StreamCommandWorkerMetrics.snapshot()
        val partition = snapshot.partitions.single()
        val consumer = snapshot.consumers.single()
        assertEquals(7, partition.partition)
        assertEquals(1, partition.fetched)
        assertEquals(1, partition.completed)
        assertEquals(0, partition.localInFlight)
        assertEquals(3, partition.maxDeliveredCount)
        assertEquals(42, partition.lastFetchedStreamSequence)
        assertEquals(42, partition.lastCompletedStreamSequence)
        assertEquals(7, consumer.partition)
        assertEquals(12, consumer.pending)
        assertEquals(2, consumer.ackPending)
        assertEquals(12, consumer.streamLag)
    }

    private fun seededPersistence(): InMemoryRuntimePersistence {
        return InMemoryRuntimePersistence().also { persistence ->
            persistence.saveInstrument(com.reef.platform.domain.Instrument("AAPL", "AAPL"))
            persistence.saveParticipant(com.reef.platform.domain.Participant("participant-1", "Participant 1"))
            persistence.saveAccount(com.reef.platform.domain.Account("account-1", "participant-1"))
            persistence.saveRole(
                RoleDefinition(
                    "order_trader",
                    listOf(Permission.ORDER_SUBMIT)
                )
            )
            persistence.saveActorRoleBinding(ActorRoleBinding("bot-worker-1", "order_trader"))
        }
    }

    private fun validSubmitBody(commandId: String, orderId: String): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"trace-$commandId",
              "causationId":"",
              "correlationId":"corr-$commandId",
              "actorId":"bot-worker-1",
              "occurredAt":"2026-07-03T00:00:00Z",
              "runId":"run-worker-1",
              "venueSessionId":"session-worker-1",
              "clientOrderId":"clord-$orderId",
              "orderId":"$orderId",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()
    }
}

private class FixedStreamCommandSource(
    private val delivery: StreamCommandDelivery
) : StreamCommandSource {
    private var delivered = false

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        if (delivered) return emptyList()
        delivered = true
        return listOf(delivery)
    }
}

private class FixedBatchStreamCommandSource(
    private vararg val deliveries: StreamCommandDelivery
) : StreamCommandSource {
    private var delivered = false

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        if (delivered) return emptyList()
        delivered = true
        return deliveries.toList()
    }
}

private class QueueStreamCommandSource(
    vararg deliveries: StreamCommandDelivery
) : StreamCommandSource {
    private val queue = ArrayDeque(deliveries.toList())

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        if (queue.isEmpty()) return emptyList()
        return listOf(queue.removeFirst())
    }
}

private class FixedTelemetrySource(
    private val snapshot: StreamCommandConsumerSnapshot
) : StreamCommandTelemetrySource {
    override fun consumerSnapshot(): StreamCommandConsumerSnapshot = snapshot
}

private class RecordingPublicationMarker : StreamCommandPublicationMarker {
    val marked = mutableMapOf<String, Long>()

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        marked[commandId] = streamSequence
        return true
    }
}

private class DistinctBatchPublicationMarker : StreamCommandPublicationMarker {
    val marked = mutableMapOf<String, Long>()

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        marked[commandId] = streamSequence
        return true
    }

    override fun markPublishedByCommandIds(commands: List<Pair<String, Long>>): Int {
        commands.forEach { (commandId, streamSequence) -> marked[commandId] = streamSequence }
        return commands.map { it.first }.distinct().size
    }
}

private object MissingPublicationMarker : StreamCommandPublicationMarker {
    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean = false
}

private class FailingThenRecordingPublicationMarker : StreamCommandPublicationMarker {
    val marked = mutableMapOf<String, Long>()
    private var failed = false

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        if (!failed) {
            failed = true
            return false
        }
        marked[commandId] = streamSequence
        return true
    }
}

private class RecordingDelivery(
    override val subject: String = "reef.cmd.v1.p00.session-1.AAPL.SubmitOrder",
    override val payloadJson: String,
    override val streamSequence: Long = 1,
    override val deliveredCount: Long = 1,
    private val failAck: Boolean = false,
    private val onAck: () -> Unit = {}
) : StreamCommandDelivery {
    var ackCalls = 0
    var nakCalls = 0
    var termCalls = 0

    override fun ack() {
        onAck()
        ackCalls++
        if (failAck) error("ack failed")
    }

    override fun nak() {
        nakCalls++
    }

    override fun term() {
        termCalls++
    }
}

private class CountingAcceptedGateway : EngineGateway {
    var submitCalls = 0

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls++
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = command.occurredAt
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        error("not used")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        error("not used")
    }
}
