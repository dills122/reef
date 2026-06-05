package com.reef.platform.application

import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

class OrderApplicationServicePerfGuardrailTest {
    @Test
    fun submitOrderInMemoryPathStaysWithinBudget() {
        val gateway = FastAcceptingEngineGateway()
        val service = OrderApplicationService(gateway, InMemoryRuntimePersistence())
        seedReferenceDataForPerf(service)
        seedOrderAuthorizationForPerf(service)

        val iterations = envInt("REEF_RUNTIME_PERF_ITERATIONS", 1500)
        val warmup = minOf(200, iterations / 5)

        for (i in 0 until warmup) {
            service.submitOrder(submitCommand(i, "warm"))
        }

        val elapsedNs = measureNanoTime {
            for (i in 0 until iterations) {
                service.submitOrder(submitCommand(i, "bench"))
            }
        }

        val nsPerOp = elapsedNs / iterations.toLong()
        val budgetNsPerOp = envLong("REEF_RUNTIME_SUBMIT_NS_PER_OP_BUDGET", 20_000_000L)

        assertTrue(
            nsPerOp <= budgetNsPerOp,
            "submitOrder perf guardrail failed: nsPerOp=$nsPerOp budgetNsPerOp=$budgetNsPerOp iterations=$iterations"
        )
    }

    private fun submitCommand(index: Int, prefix: String): SubmitOrderCommand {
        return SubmitOrderCommand(
            commandId = "cmd-$prefix-$index",
            traceId = "trace-$prefix-$index",
            causationId = "",
            correlationId = "corr-$prefix-$index",
            actorId = "perf-tester",
            occurredAt = "2026-03-14T18:00:00Z",
            orderId = "ord-$prefix-$index",
            instrumentId = "AAPL",
            participantId = "participant-1",
            accountId = "account-1",
            side = "BUY",
            orderType = "LIMIT",
            quantityUnits = "100",
            limitPrice = "150250000000",
            currency = "USD",
            timeInForce = "DAY"
        )
    }
}

private fun seedReferenceDataForPerf(service: OrderApplicationService) {
    service.createInstrument(Instrument("AAPL", "AAPL"))
    service.createParticipant(Participant("participant-1", "Participant 1"))
    service.createAccount(Account("account-1", "participant-1"))
}

private fun seedOrderAuthorizationForPerf(service: OrderApplicationService) {
    service.createRole(
        RoleDefinition(
            "order_trader",
            listOf(Permission.ORDER_SUBMIT, Permission.ORDER_CANCEL, Permission.ORDER_MODIFY)
        )
    )
    service.assignRole(ActorRoleBinding("perf-tester", "order_trader"))
}

private class FastAcceptingEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
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
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-cancel-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = command.occurredAt
            )
        )
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-modify-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = command.occurredAt
            )
        )
    }
}

private fun envInt(name: String, fallback: Int): Int {
    val raw = System.getenv(name) ?: return fallback
    return raw.toIntOrNull() ?: fallback
}

private fun envLong(name: String, fallback: Long): Long {
    val raw = System.getenv(name) ?: return fallback
    return raw.toLongOrNull() ?: fallback
}
