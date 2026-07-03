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

class StreamCommandWorkerTest {
    @Test
    fun submitWorkerPersistsCanonicalOutcomeBeforeAck() {
        val persistence = seededPersistence()
        val gateway = CountingAcceptedGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val delivery = RecordingDelivery(payloadJson = validSubmitBody("cmd-worker-1", "ord-worker-1"))
        val source = FixedStreamCommandSource(delivery)
        val worker = StreamCommandWorker(source = source, api = api)

        val processed = worker.processOnce()

        assertEquals(1, processed)
        assertEquals(1, gateway.submitCalls)
        assertEquals(1, delivery.ackCalls)
        assertEquals(0, delivery.nakCalls)
        assertNotNull(persistence.submitResult("cmd-worker-1"))
        assertNotNull(persistence.acceptedOrder("ord-worker-1"))
    }

    @Test
    fun redeliveryAfterPersistBeforeAckDoesNotCallEngineAgain() {
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
        assertNotNull(persistence.acceptedOrder("ord-worker-redeliver"))
    }

    @Test
    fun unsupportedStreamCommandIsTerminated() {
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

private class QueueStreamCommandSource(
    vararg deliveries: StreamCommandDelivery
) : StreamCommandSource {
    private val queue = ArrayDeque(deliveries.toList())

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        if (queue.isEmpty()) return emptyList()
        return listOf(queue.removeFirst())
    }
}

private class RecordingDelivery(
    override val subject: String = "reef.cmd.v1.p00.session-1.AAPL.SubmitOrder",
    override val payloadJson: String,
    override val streamSequence: Long = 1,
    override val deliveredCount: Long = 1,
    private val failAck: Boolean = false
) : StreamCommandDelivery {
    var ackCalls = 0
    var nakCalls = 0
    var termCalls = 0

    override fun ack() {
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
