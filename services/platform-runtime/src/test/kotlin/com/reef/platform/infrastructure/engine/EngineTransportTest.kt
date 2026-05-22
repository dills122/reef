package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTransportTest {
    @Test
    fun grpcTransportScaffoldDelegatesToFallbackGateway() {
        val fallback = RecordingGateway()
        val gateway = GrpcEngineClient("localhost:9081", fallback)

        val result = gateway.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-1",
                traceId = "trace-1",
                causationId = "",
                correlationId = "corr-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-1",
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
        )

        assertTrue(result === fallback.result)
        assertEquals("localhost:9081", gateway.target())
        assertEquals(1, fallback.submitCalls)
    }
}

private class RecordingGateway : EngineGateway {
    var submitCalls: Int = 0
    val result = SubmitOrderResult()

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls += 1
        return result
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return result
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return result
    }
}
