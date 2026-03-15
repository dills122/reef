package com.reef.platform.application

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrderApplicationServiceTest {
    @Test
    fun submitOrderDelegatesToEngineGateway() {
        val gateway = RecordingEngineGateway()
        val service = OrderApplicationService(gateway)

        val result = service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-1",
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

        assertNotNull(gateway.lastCommand)
        assertEquals("ord-1", gateway.lastCommand?.orderId)
        assertEquals("eng-ord-1", result.accepted?.engineOrderId)
    }
}

private class RecordingEngineGateway : EngineGateway {
    var lastCommand: SubmitOrderCommand? = null

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        lastCommand = command
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-1",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-03-14T18:00:00Z"
            )
        )
    }
}
