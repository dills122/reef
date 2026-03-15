package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PlatformApiTest {
    @Test
    fun healthReturnsExpectedPayload() {
        val api = PlatformApi()

        assertEquals("""{"service":"platform-runtime","status":"ok"}""", api.health())
    }

    @Test
    fun submitOrderSerializesAcceptedResponse() {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult(
                        accepted = EngineOrderAccepted(
                            eventId = "evt-1",
                            orderId = "ord-1",
                            engineOrderId = "eng-ord-1",
                            occurredAt = "2026-03-14T18:00:00Z"
                        )
                    )
                )
            )
        )

        val response = api.submitOrder(validRequestBody())

        assertContains(response, "\"accepted\"")
        assertContains(response, "\"engineOrderId\":\"eng-ord-1\"")
    }

    @Test
    fun submitOrderSerializesRejectedResponse() {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult(
                        rejected = EngineOrderRejected(
                            eventId = "evt-2",
                            orderId = "ord-1",
                            code = "VALIDATION_ERROR",
                            reason = "instrumentId is required",
                            occurredAt = "2026-03-14T18:00:00Z"
                        )
                    )
                )
            )
        )

        val response = api.submitOrder(validRequestBody())

        assertContains(response, "\"rejected\"")
        assertContains(response, "\"code\":\"VALIDATION_ERROR\"")
    }

    private fun validRequestBody(): String {
        return """
            {
              "commandId":"cmd-1",
              "correlationId":"corr-1",
              "actorId":"trader-1",
              "occurredAt":"2026-03-14T18:00:00Z",
              "orderId":"ord-1",
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

private class FakeEngineGateway(
    private val result: SubmitOrderResult
) : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return result
    }
}
