package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
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
        seedReferenceData(api)
        seedOrderAuthorization(api, "trader-1")

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
        seedReferenceData(api)
        seedOrderAuthorization(api, "trader-1")

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
        seedReferenceData(api)
        seedOrderAuthorization(api, "trader-1")

        api.submitOrder(validRequestBody())

        val orderResponse = api.order("ord-1")
        assertContains(orderResponse, "\"order\"")
        assertContains(orderResponse, "\"engineOrderId\":\"eng-ord-1\"")

        val orderEventsResponse = api.orderEvents("ord-1")
        assertContains(orderEventsResponse, "\"events\"")
        assertContains(orderEventsResponse, "\"eventType\":\"OrderAccepted\"")
        assertContains(orderEventsResponse, "\"traceId\":\"trace-1\"")
        assertContains(orderEventsResponse, "\"causationId\":\"cmd-1\"")

        val eventsResponse = api.events()
        assertContains(eventsResponse, "\"eventType\":\"TradeCreated\"")
        assertContains(eventsResponse, "\"producer\":\"platform-runtime\"")
        assertContains(eventsResponse, "\"sequenceNumber\":")

        val ordersResponse = api.orders()
        assertContains(ordersResponse, "\"orders\"")
        assertContains(ordersResponse, "\"orderId\":\"ord-1\"")

        val tradesResponse = api.trades()
        assertContains(tradesResponse, "\"trades\"")
        assertContains(tradesResponse, "\"tradeId\":\"trade-1\"")

        val traceEventsResponse = api.traceEvents("trace-1")
        assertContains(traceEventsResponse, "\"traceId\":\"trace-1\"")
        assertContains(traceEventsResponse, "\"events\"")
    }

    @Test
    fun cancelAndModifySerializeResponses() {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult(
                        accepted = EngineOrderAccepted(
                            eventId = "evt-accepted-1",
                            orderId = "ord-1",
                            engineOrderId = "eng-ord-1",
                            occurredAt = "2026-03-14T18:00:00Z"
                        )
                    )
                )
            )
        )
        seedOrderAuthorization(api, "trader-1")

        val cancelResponse = api.cancelOrder(
            """
            {
              "commandId":"cmd-cancel-1",
              "traceId":"trace-1",
              "causationId":"",
              "correlationId":"corr-1",
              "actorId":"trader-1",
              "occurredAt":"2026-03-14T18:00:00Z",
              "orderId":"ord-1",
              "reason":"test"
            }
            """.trimIndent()
        )
        assertContains(cancelResponse, "\"accepted\"")

        val modifyResponse = api.modifyOrder(
            """
            {
              "commandId":"cmd-modify-1",
              "traceId":"trace-1",
              "causationId":"",
              "correlationId":"corr-1",
              "actorId":"trader-1",
              "occurredAt":"2026-03-14T18:00:00Z",
              "orderId":"ord-1",
              "quantityUnits":"120",
              "limitPrice":"150250000001"
            }
            """.trimIndent()
        )
        assertContains(modifyResponse, "\"accepted\"")
    }

    @Test
    fun roleEndpointsExposeSavedDefinitionsAndActorBindings() {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult()
                )
            )
        )

        api.createRole("""{"roleId":"order_trader","permissions":"order.submit,order.cancel,order.modify"}""")
        api.assignRole("""{"actorId":"trader-9","roleId":"order_trader"}""")

        assertContains(api.roles(), "\"roleId\":\"order_trader\"")
        assertContains(api.roles(), "\"order.submit\"")
        assertContains(api.actorRoles("trader-9"), "\"actorId\":\"trader-9\"")
        assertContains(api.actorRoles("trader-9"), "\"roleId\":\"order_trader\"")
    }

    @Test
    fun referenceDataCrudEndpointsExposeSavedEntities() {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult()
                )
            )
        )

        api.createInstrument("""{"instrumentId":"MSFT","symbol":"MSFT"}""")
        api.createParticipant("""{"participantId":"participant-9","name":"Participant 9"}""")
        api.createAccount("""{"accountId":"account-9","participantId":"participant-9"}""")

        assertContains(api.instruments(), "\"instrumentId\":\"MSFT\"")
        assertContains(api.participants(), "\"participantId\":\"participant-9\"")
        assertContains(api.accounts(), "\"accountId\":\"account-9\"")
    }

    @Test
    fun marketDataSnapshotApiReadsProjectedTopOfBook() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "bid-1",
                engineOrderId = "eng-bid-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:00Z"
            )
        )
        persistence.saveExecutions(
            listOf(
                ExecutionCreated(
                    eventId = "exec-bid-1",
                    executionId = "exec-bid-1",
                    orderId = "bid-1",
                    instrumentId = "AAPL",
                    quantityUnits = "25",
                    executionPrice = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:02Z"
                )
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ask-1",
                engineOrderId = "eng-ask-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "SELL",
                orderType = "LIMIT",
                quantityUnits = "75",
                limitPrice = "150260000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:01Z"
            )
        )
        persistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-ask-cancelled-1",
                eventType = "OrderCancelled",
                orderId = "ask-1",
                traceId = "trace-ask-1",
                causationId = "cmd-cancel-ask-1",
                correlationId = "corr-ask-1",
                actorId = "trader-1",
                producer = "unit-test",
                schemaVersion = "v1",
                payloadJson = "{}",
                occurredAt = "2026-03-14T18:00:03Z"
            )
        )

        assertContains(api.marketDataSnapshot("AAPL"), "\"error\":\"market data snapshot not found\"")
        assertContains(api.refreshMarketDataSnapshots(), "\"refreshed\":1")
        val response = api.marketDataSnapshot("AAPL")

        assertContains(response, "\"snapshot\"")
        assertContains(response, "\"projectionName\":\"market-data-top-of-book\"")
        assertContains(response, "\"sourceProjectionName\":\"runtime-normalized-venue-outcomes\"")
        assertContains(response, "\"bestBidPrice\":\"150250000000\"")
        assertContains(response, "\"bestBidQuantity\":\"75\"")
        assertContains(response, "\"bestAskPrice\":\"\"")
        assertContains(response, "\"bestAskQuantity\":\"\"")
        assertContains(response, "\"lag\":0")

        val orderResponse = api.order("bid-1")
        assertContains(orderResponse, "\"lifecycleState\"")
        assertContains(orderResponse, "\"status\":\"PARTIALLY_FILLED\"")
        assertContains(orderResponse, "\"remainingQuantityUnits\":\"75\"")
    }

    private fun validRequestBody(): String {
        return """
            {
              "commandId":"cmd-1",
              "traceId":"trace-1",
              "causationId":"",
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

    private fun seedReferenceData(api: PlatformApi) {
        api.createInstrument("""{"instrumentId":"AAPL","symbol":"AAPL"}""")
        api.createParticipant("""{"participantId":"participant-1","name":"Participant 1"}""")
        api.createAccount("""{"accountId":"account-1","participantId":"participant-1"}""")
    }

    private fun seedOrderAuthorization(api: PlatformApi, actorId: String) {
        api.createRole("""{"roleId":"order_trader","permissions":"order.submit,order.cancel,order.modify"}""")
        api.assignRole("""{"actorId":"$actorId","roleId":"order_trader"}""")
    }
}

private class FakeEngineGateway(
    private val result: SubmitOrderResult
) : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return result
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return result
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return result
    }
}
