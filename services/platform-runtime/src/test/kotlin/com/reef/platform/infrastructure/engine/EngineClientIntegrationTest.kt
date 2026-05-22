package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.SubmitOrderCommand
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EngineClientIntegrationTest {
    @Test
    fun submitOrderPostsToEngineAndParsesAcceptedResponse() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/orders/submit") { exchange ->
            val response = """
                {
                  "accepted":{
                    "eventId":"evt-1",
                    "orderId":"ord-1",
                    "engineOrderId":"eng-ord-1",
                    "occurredAt":"2026-03-14T18:00:00Z"
                  },
                  "executions":[
                    {
                      "eventId":"evt-exec-1",
                      "executionId":"exec-1-buy",
                      "orderId":"ord-1",
                      "instrumentId":"AAPL",
                      "quantityUnits":"100",
                      "executionPrice":"150250000000",
                      "currency":"USD",
                      "occurredAt":"2026-03-14T18:00:00Z"
                    }
                  ],
                  "trades":[
                    {
                      "eventId":"evt-trade-1",
                      "tradeId":"trade-1",
                      "executionId":"exec-1",
                      "buyOrderId":"ord-1",
                      "sellOrderId":"ord-2",
                      "instrumentId":"AAPL",
                      "quantityUnits":"100",
                      "price":"150250000000",
                      "currency":"USD",
                      "occurredAt":"2026-03-14T18:00:00Z"
                    }
                  ]
                }
            """.trimIndent().toByteArray()

            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { output -> output.write(response) }
        }
        server.start()

        try {
            val client = EngineClient("http://localhost:${server.address.port}")
            val result = client.submitOrder(
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

            assertNotNull(result.accepted)
            assertEquals("eng-ord-1", result.accepted.engineOrderId)
            assertEquals(1, result.executions.size)
            assertEquals(1, result.trades.size)
        } finally {
            server.stop(0)
        }
    }
}
