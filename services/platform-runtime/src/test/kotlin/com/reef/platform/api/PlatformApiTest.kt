package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
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
                        ),
                        executions = listOf(
                            ExecutionCreated(
                                eventId = "evt-exec-1",
                                executionId = "exec-1",
                                orderId = "ord-1",
                                instrumentId = "AAPL",
                                quantityUnits = "100",
                                executionPrice = "150250000000",
                                currency = "USD",
                                occurredAt = "2026-03-14T18:00:00Z"
                            )
                        ),
                        trades = listOf(
                            TradeCreated(
                                eventId = "evt-trade-1",
                                tradeId = "trade-1",
                                executionId = "exec-1",
                                buyOrderId = "ord-1",
                                sellOrderId = "ord-2",
                                instrumentId = "AAPL",
                                quantityUnits = "100",
                                price = "150250000000",
                                currency = "USD",
                                occurredAt = "2026-03-14T18:00:00Z"
                            )
                        )
                    )
                )
            )
        )

        val response = api.submitOrder(validRequestBody())

        assertContains(response, "\"accepted\"")
        assertContains(response, "\"engineOrderId\":\"eng-ord-1\"")
        assertContains(response, "\"executions\"")
        assertContains(response, "\"trades\"")
        assertContains(response, "\"sellOrderId\":\"ord-2\"")
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

    @Test
    fun orderAndEventsExposePersistedArtifacts() {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult(
                        accepted = EngineOrderAccepted(
                            eventId = "evt-1",
                            orderId = "ord-1",
                            engineOrderId = "eng-ord-1",
                            occurredAt = "2026-03-14T18:00:00Z"
                        ),
                        executions = listOf(
                            ExecutionCreated(
                                eventId = "evt-exec-1",
                                executionId = "exec-1",
                                orderId = "ord-1",
                                instrumentId = "AAPL",
                                quantityUnits = "100",
                                executionPrice = "150250000000",
                                currency = "USD",
                                occurredAt = "2026-03-14T18:00:00Z"
                            )
                        ),
                        trades = listOf(
                            TradeCreated(
                                eventId = "evt-trade-1",
                                tradeId = "trade-1",
                                executionId = "exec-1",
                                buyOrderId = "ord-1",
                                sellOrderId = "ord-2",
                                instrumentId = "AAPL",
                                quantityUnits = "100",
                                price = "150250000000",
                                currency = "USD",
                                occurredAt = "2026-03-14T18:00:00Z"
                            )
                        )
                    )
                )
            )
        )

        api.submitOrder(validRequestBody())

        val orderResponse = api.order("ord-1")
        assertContains(orderResponse, "\"order\"")
        assertContains(orderResponse, "\"engineOrderId\":\"eng-ord-1\"")

        val orderEventsResponse = api.orderEvents("ord-1")
        assertContains(orderEventsResponse, "\"events\"")
        assertContains(orderEventsResponse, "\"eventType\":\"OrderAccepted\"")

        val eventsResponse = api.events()
        assertContains(eventsResponse, "\"eventType\":\"TradeCreated\"")

        val ordersResponse = api.orders()
        assertContains(ordersResponse, "\"orders\"")
        assertContains(ordersResponse, "\"orderId\":\"ord-1\"")

        val tradesResponse = api.trades()
        assertContains(tradesResponse, "\"trades\"")
        assertContains(tradesResponse, "\"tradeId\":\"trade-1\"")
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
