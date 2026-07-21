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
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

        val depthResponse = api.marketDataDepthSnapshot("AAPL", levels = 2)
        assertContains(depthResponse, "\"depth\"")
        assertContains(depthResponse, "\"projectionName\":\"market-data-depth\"")
        assertContains(depthResponse, "\"bidLevels\":[{\"price\":\"150250000000\",\"quantity\":\"75\"}]")
        assertContains(depthResponse, "\"askLevels\":[]")
        assertContains(depthResponse, "\"levels\":2")
    }

    @Test
    fun tradeTapeApiReturnsRecentPublicSafeTradesForInstrumentMostRecentFirst() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-1",
                    tradeId = "trade-1",
                    executionId = "exec-1",
                    buyOrderId = "bid-1",
                    sellOrderId = "ask-1",
                    instrumentId = "AAPL",
                    quantityUnits = "25",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:02Z"
                ),
                TradeCreated(
                    eventId = "evt-trade-2",
                    tradeId = "trade-2",
                    executionId = "exec-2",
                    buyOrderId = "bid-2",
                    sellOrderId = "ask-1",
                    instrumentId = "MSFT",
                    quantityUnits = "10",
                    price = "300000000000",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:03Z"
                ),
                TradeCreated(
                    eventId = "evt-trade-3",
                    tradeId = "trade-3",
                    executionId = "exec-3",
                    buyOrderId = "bid-3",
                    sellOrderId = "ask-2",
                    instrumentId = "AAPL",
                    quantityUnits = "50",
                    price = "150260000000",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:04Z"
                )
            )
        )

        val response = api.tradeTape("AAPL")

        assertContains(response, "\"instrumentId\":\"AAPL\"")
        assertContains(response, "\"meta\":{\"source\":\"runtime.trades\"")
        assertContains(response, "\"freshness\":\"durable fact rows\"")
        assertContains(response, "\"limit\":50")
        assertContains(response, "\"tradeId\":\"trade-3\"")
        assertContains(response, "\"tradeId\":\"trade-1\"")
        assert(!response.contains("\"tradeId\":\"trade-2\"")) { "unrelated instrument leaked into tape" }
        assert(!response.contains("buyOrderId")) { "counterparty order id leaked into public tape" }
        assert(!response.contains("sellOrderId")) { "counterparty order id leaked into public tape" }
        assert(response.indexOf("\"tradeId\":\"trade-3\"") < response.indexOf("\"tradeId\":\"trade-1\"")) {
            "expected most recent trade first"
        }
    }

    @Test
    fun intradayBarsApiAggregatesTradesIntoOhlcvBucketsForInstrument() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-t1",
                    tradeId = "t1",
                    executionId = "exec-1",
                    buyOrderId = "b1",
                    sellOrderId = "s1",
                    instrumentId = "AAPL",
                    quantityUnits = "10",
                    price = "100",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:05Z"
                ),
                TradeCreated(
                    eventId = "evt-t2",
                    tradeId = "t2",
                    executionId = "exec-2",
                    buyOrderId = "b2",
                    sellOrderId = "s2",
                    instrumentId = "AAPL",
                    quantityUnits = "5",
                    price = "105",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:30Z"
                ),
                TradeCreated(
                    eventId = "evt-t3",
                    tradeId = "t3",
                    executionId = "exec-3",
                    buyOrderId = "b3",
                    sellOrderId = "s3",
                    instrumentId = "AAPL",
                    quantityUnits = "20",
                    price = "95",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:01:10Z"
                ),
                TradeCreated(
                    eventId = "evt-t4",
                    tradeId = "t4",
                    executionId = "exec-4",
                    buyOrderId = "b4",
                    sellOrderId = "s4",
                    instrumentId = "MSFT",
                    quantityUnits = "1",
                    price = "300",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:10Z"
                )
            )
        )

        val response = api.intradayBars(
            "AAPL",
            interval = "1m",
            start = "2026-03-14T18:00:00Z",
            end = "2026-03-14T18:02:00Z"
        )

        assertContains(response, "\"instrumentId\":\"AAPL\"")
        assertContains(response, "\"interval\":\"1m\"")
        assertContains(response, "\"meta\":{\"source\":\"runtime.trades\"")
        assertContains(response, "\"freshness\":\"durable fact row aggregation\"")
        // first bucket [18:00:00, 18:01:00): open=100 (t1 first), close=105 (t2 last), high=105, low=100, volume=15
        assertContains(response, "\"start\":\"2026-03-14T18:00:00Z\"")
        assertContains(response, "\"open\":\"100\"")
        assertContains(response, "\"high\":\"105\"")
        assertContains(response, "\"low\":\"100\"")
        assertContains(response, "\"close\":\"105\"")
        assertContains(response, "\"volume\":\"15\"")
        // second bucket [18:01:00, 18:02:00): only t3
        assertContains(response, "\"start\":\"2026-03-14T18:01:00Z\"")
        assertContains(response, "\"volume\":\"20\"")
        assert(!response.contains("\"300\"")) { "unrelated instrument leaked into bars" }

        val badInterval = api.intradayBars("AAPL", interval = "3m", start = "2026-03-14T18:00:00Z", end = "2026-03-14T18:02:00Z")
        assertContains(badInterval, "\"error\":\"unsupported interval\"")
    }

    // Regression tests for PlatformHttpServer previously deriving HTTP
    // status codes by string-matching an "error" substring in the response
    // body (fragile: would silently misclassify a legitimate success
    // response that happened to contain the same text). The *WithStatus
    // variants expose the found/ok signal the API already computes
    // internally, so the boundary layer no longer has to guess from body
    // content. body must stay byte-identical to the non-status variant so
    // the wire contract for existing API consumers is unchanged.
    @Test
    fun orderWithStatusReportsNotFoundAndMatchesOrderBody() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))

        val missing = api.orderWithStatus("does-not-exist")
        assertEquals(false, missing.found)
        assertEquals(api.order("does-not-exist"), missing.body)
        assertContains(missing.body, "\"error\":\"order not found\"")

        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ord-status-1",
                engineOrderId = "eng-ord-status-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150000000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-12T00:00:00Z"
            )
        )
        val found = api.orderWithStatus("ord-status-1")
        assertEquals(true, found.found)
        assertEquals(api.order("ord-status-1"), found.body)
    }

    @Test
    fun marketDataSnapshotWithStatusReportsNotFound() {
        val api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence()))

        val missing = api.marketDataSnapshotWithStatus("AAPL")
        assertEquals(false, missing.found)
        assertEquals(api.marketDataSnapshot("AAPL"), missing.body)
        assertContains(missing.body, "\"error\":\"market data snapshot not found\"")
    }

    @Test
    fun marketDataDepthSnapshotWithStatusReportsNotFound() {
        val api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence()))

        val missing = api.marketDataDepthSnapshotWithStatus("AAPL")
        assertEquals(false, missing.found)
        assertEquals(api.marketDataDepthSnapshot("AAPL"), missing.body)
        assertContains(missing.body, "\"error\":\"market data depth not found\"")
    }

    @Test
    fun intradayBarsWithStatusReportsUnsupportedIntervalAsNotOk() {
        val api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence()))

        val badInterval = api.intradayBarsWithStatus("AAPL", interval = "3m", start = "2026-03-14T18:00:00Z", end = "2026-03-14T18:02:00Z")
        assertEquals(false, badInterval.found)
        assertEquals(
            api.intradayBars("AAPL", interval = "3m", start = "2026-03-14T18:00:00Z", end = "2026-03-14T18:02:00Z"),
            badInterval.body
        )

        val okInterval = api.intradayBarsWithStatus("AAPL", interval = "1m", start = "2026-03-14T18:00:00Z", end = "2026-03-14T18:02:00Z")
        assertEquals(true, okInterval.found)
    }

    @Test
    fun intradayBarsWithStatusRejectsOversizedAndInvalidRanges() {
        val api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence()))

        val oversized = api.intradayBarsWithStatus(
            "AAPL",
            interval = "1m",
            start = "2026-03-14T00:00:00Z",
            end = "2026-03-16T00:00:00Z"
        )
        val invalid = api.intradayBarsWithStatus(
            "AAPL",
            interval = "1m",
            start = "2026-03-14T18:02:00Z",
            end = "2026-03-14T18:00:00Z"
        )

        assertEquals(false, oversized.found)
        assertContains(oversized.body, "\"error\":\"intraday bars range too large\"")
        assertEquals(false, invalid.found)
        assertContains(invalid.body, "\"error\":\"invalid time range\"")
    }

    @Test
    fun ownOrdersApiScopesToParticipantAndOpenStatusOnly() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "mine-open",
                engineOrderId = "eng-mine-open",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:00Z"
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "mine-cancelled",
                engineOrderId = "eng-mine-cancelled",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "SELL",
                orderType = "LIMIT",
                quantityUnits = "50",
                limitPrice = "151",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:01Z"
            )
        )
        persistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-mine-cancelled",
                eventType = "OrderCancelled",
                orderId = "mine-cancelled",
                traceId = "trace-mine-cancelled",
                causationId = "cmd-cancel",
                correlationId = "corr-mine-cancelled",
                actorId = "trader-1",
                producer = "unit-test",
                schemaVersion = "v1",
                payloadJson = "{}",
                occurredAt = "2026-03-14T18:00:02Z"
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "someone-elses",
                engineOrderId = "eng-someone-elses",
                instrumentId = "AAPL",
                participantId = "participant-2",
                accountId = "account-2",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "10",
                limitPrice = "150",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:03Z"
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "mine-msft",
                engineOrderId = "eng-mine-msft",
                instrumentId = "MSFT",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "10",
                limitPrice = "300",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:04Z"
            )
        )
        persistence.rebuildOrderLifecycleState()

        val current = api.ownOrders("participant-1", openOnly = true)
        assertContains(current, "\"mine-open\"")
        assertContains(current, "\"meta\":{\"source\":\"runtime.order_lifecycle_state\"")
        assertContains(current, "\"freshness\":\"dirty-tracked lifecycle projection\"")
        assertContains(current, "\"openOnly\":true")
        assert(!current.contains("mine-cancelled")) { "cancelled order leaked into current" }
        assert(!current.contains("someone-elses")) { "other participant's order leaked into current" }

        val history = api.ownOrders("participant-1", openOnly = false)
        assertContains(history, "\"mine-open\"")
        assertContains(history, "\"mine-cancelled\"")
        assertContains(history, "\"openOnly\":false")
        assert(!history.contains("someone-elses")) { "other participant's order leaked into history" }

        val filtered = api.ownOrders("participant-1", openOnly = false, instrumentId = "AAPL", limit = 1)
        assertContains(filtered, "\"instrumentId\":\"AAPL\"")
        assertContains(filtered, "\"limit\":1")
        assertContains(filtered, "\"mine-open\"")
        assert(!filtered.contains("mine-msft")) { "instrument filter not applied" }
        assert(!filtered.contains("mine-cancelled")) { "limit not applied" }
    }

    @Test
    fun ownExecutionsApiScopesToParticipantOwnedOrders() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "mine-buy",
                engineOrderId = "eng-mine-buy",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:00Z",
                runId = "run-current"
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "mine-msft",
                engineOrderId = "eng-mine-msft",
                instrumentId = "MSFT",
                participantId = "participant-1",
                accountId = "account-1",
                side = "SELL",
                orderType = "LIMIT",
                quantityUnits = "10",
                limitPrice = "300",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:01Z",
                runId = "run-current"
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "mine-previous-run",
                engineOrderId = "eng-mine-previous-run",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "25",
                limitPrice = "149",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T17:00:00Z",
                runId = "run-previous"
            )
        )
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "someone-elses",
                engineOrderId = "eng-someone-elses",
                instrumentId = "AAPL",
                participantId = "participant-2",
                accountId = "account-2",
                side = "SELL",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:02Z"
            )
        )
        persistence.saveExecutions(
            listOf(
                ExecutionCreated(
                    eventId = "evt-exec-mine",
                    executionId = "exec-mine",
                    orderId = "mine-buy",
                    instrumentId = "AAPL",
                    quantityUnits = "40",
                    executionPrice = "150",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:03Z",
                    liquidityRole = "MAKER"
                ),
                ExecutionCreated(
                    eventId = "evt-exec-msft",
                    executionId = "exec-msft",
                    orderId = "mine-msft",
                    instrumentId = "MSFT",
                    quantityUnits = "10",
                    executionPrice = "300",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:04Z"
                ),
                ExecutionCreated(
                    eventId = "evt-exec-previous-run",
                    executionId = "exec-previous-run",
                    orderId = "mine-previous-run",
                    instrumentId = "AAPL",
                    quantityUnits = "25",
                    executionPrice = "149",
                    currency = "USD",
                    occurredAt = "2026-03-14T17:00:01Z"
                ),
                ExecutionCreated(
                    eventId = "evt-exec-other",
                    executionId = "exec-other",
                    orderId = "someone-elses",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    executionPrice = "150",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:05Z"
                )
            )
        )

        val response = api.ownExecutions("participant-1", instrumentId = "AAPL", runId = "run-current", limit = 1)

        assertContains(response, "\"participantId\":\"participant-1\"")
        assertContains(response, "\"source\":\"runtime.orders + runtime.executions\"")
        assertContains(response, "\"freshness\":\"durable execution rows scoped by participant order ownership\"")
        assertContains(response, "\"fills\":[{\"executionId\":\"exec-mine\"")
        assertContains(response, "\"side\":\"BUY\"")
        assertContains(response, "\"quantityUnits\":\"40\"")
        assertContains(response, "\"liquidityRole\":\"MAKER\"")
        assertContains(response, "\"runId\":\"run-current\"")
        assertContains(response, "\"limit\":1")
        assert(!response.contains("exec-msft")) { "instrument filter not applied" }
        assert(!response.contains("exec-other")) { "other participant's fill leaked" }
        assert(!response.contains("exec-previous-run")) { "previous run fill leaked" }
        assert(!response.contains("eventId")) { "internal event id leaked into own fills" }
    }

    @Test
    fun operationalProjectionSmokeReachesOrderAndMarketDataReads() {
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = FakeEngineGateway(
                    SubmitOrderResult(
                        accepted = EngineOrderAccepted(
                            eventId = "evt-smoke-accepted",
                            orderId = "ord-1",
                            engineOrderId = "eng-ord-1",
                            occurredAt = "2026-03-14T18:00:00Z"
                        )
                    )
                ),
                runtimePersistence = persistence
            )
        )
        seedReferenceData(api)
        seedOrderAuthorization(api, "trader-1")

        val submitResponse = api.submitOrder(validRequestBody())
        val materialized = api.materializeVenueEventBatch(
            VenueEventBatchFact(
                batchId = "batch-smoke-1",
                shardId = "engine-0",
                partition = 0,
                commandStream = "REEF_COMMANDS",
                eventStream = "REEF_VENUE_EVENTS",
                firstSequence = 10,
                lastSequence = 10,
                commandCount = 1,
                createdAt = "2026-03-14T18:00:01Z",
                payloadChecksum = "checksum-smoke-1",
                outcomes = listOf(
                    VenueCommandOutcomeFact(
                        commandId = "cmd-smoke-batch-1",
                        commandType = "SubmitOrder",
                        streamSequence = 10,
                        deliveredCount = 1,
                        payloadHash = "payload-hash-smoke-1",
                        instrumentId = "AAPL",
                        orderId = "ord-1",
                        resultStatus = "accepted",
                        resultPayloadJson = """{"accepted":{"eventId":"evt-smoke-batch","engineOrderId":"eng-ord-1","occurredAt":"2026-03-14T18:00:01Z"}}"""
                    )
                )
            )
        )
        val projected = api.projectCanonicalCommandOutcomes("runtime-normalized-venue-outcomes", 10)
        val refreshed = api.refreshMarketDataSnapshotsCount()

        assertContains(submitResponse, "\"accepted\"")
        assertEquals(1, materialized)
        assertEquals(1, projected)
        assertEquals(1, refreshed)
        val canonical = assertNotNull(api.canonicalCommandOutcome("cmd-smoke-batch-1"))
        assertEquals("accepted", canonical.resultStatus)
        assertEquals(10, canonical.streamSequence)
        assertContains(api.order("ord-1"), "\"lifecycleState\"")
        assertContains(api.order("ord-1"), "\"status\":\"OPEN\"")
        assertContains(api.marketDataSnapshot("AAPL"), "\"bestBidPrice\":\"150250000000\"")
        assertContains(api.marketDataDepthSnapshot("AAPL"), "\"bidLevels\":[{\"price\":\"150250000000\",\"quantity\":\"100\"}]")

        val availability = api.dataAvailability()
        assertContains(availability, "\"source\":\"venue-event-batch\"")
        assertContains(availability, "\"name\":\"marketDataSnapshots\"")
        assertContains(availability, "\"endpoint\":\"/api/v1/market-data/snapshots/{instrumentId}\"")
        assertContains(availability, "\"projectionName\":\"runtime-normalized-venue-outcomes\"")
        assertContains(availability, "\"projectionName\":\"market-data-top-of-book\"")
        assertContains(availability, "\"name\":\"tradeTape\"")
        assertContains(availability, "\"freshness\":\"durable fact rows\"")
        assertContains(availability, "\"name\":\"settlementFacts\"")
        assertContains(availability, "\"endpoint\":\"/api/v1/settlement/facts/{scenarioRunId}\"")
        assertContains(availability, "\"name\":\"settlementObligations\"")
        assertContains(availability, "\"endpoint\":\"/api/v1/settlement/obligations/{scenarioRunId}\"")
        assertContains(availability, "\"name\":\"settlementLedger\"")
        assertContains(availability, "\"endpoint\":\"/api/v1/settlement/ledger/{scenarioRunId}\"")
        assertContains(availability, "\"name\":\"settlementExceptions\"")
        assertContains(availability, "\"endpoint\":\"/api/v1/settlement/exceptions/{scenarioRunId}\"")
        assertContains(availability, "\"name\":\"currentOrders\"")
        assertContains(availability, "\"name\":\"orderFills\"")
        assertContains(availability, "\"endpoint\":\"/api/v1/orders/fills\"")
        assertContains(availability, "\"source\":\"runtime.orders + runtime.executions\"")
        assertContains(availability, "\"scope\":\"participant-own-orders\"")
        assertContains(availability, "\"requiredQuery\":[\"participantId\"]")
        assertContains(availability, "\"optionalQuery\":[\"levels\",\"projectionName\",\"sourceProjectionName\"]")
        assertContains(availability, "venueSessionId filtering is not exposed")
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
