package com.reef.platform.infrastructure.engine

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.SubmitOrderCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineClientTest {
    @Test
    fun submitPayloadMatchesStrictEngineHttpContract() {
        val client = EngineClient()
        val payload = client.submitPayload(submitCommand())
        val json = JsonCodec.parseObject(payload)

        assertEquals("cmd-1", json.string("commandId"))
        assertEquals("corr-1", json.string("correlationId"))
        assertEquals("ord-1", json.string("orderId"))
        assertFalse(json.has("traceId"))
        assertFalse(json.has("causationId"))
    }

    @Test
    fun parseSubmitOrderResultParsesAcceptedPayload() {
        val client = EngineClient()

        val result = client.parseSubmitOrderResult(
            """
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
            """.trimIndent()
        )

        assertNotNull(result.accepted)
        assertEquals("eng-ord-1", result.accepted.engineOrderId)
        assertEquals(1, result.executions.size)
        assertEquals(1, result.trades.size)
        assertEquals("ord-2", result.trades[0].sellOrderId)
    }

    @Test
    fun parseSubmitOrderResultParsesRejectedPayload() {
        val client = EngineClient()

        val result = client.parseSubmitOrderResult(
            """
            {
              "rejected":{
                "eventId":"evt-2",
                "orderId":"ord-1",
                "code":"VALIDATION_ERROR",
                "reason":"instrumentId is required",
                "occurredAt":"2026-03-14T18:00:00Z"
              }
            }
            """.trimIndent()
        )

        assertNotNull(result.rejected)
        assertEquals("VALIDATION_ERROR", result.rejected.code)
        assertTrue(result.executions.isEmpty())
        assertTrue(result.trades.isEmpty())
    }

    private fun submitCommand(): SubmitOrderCommand {
        return SubmitOrderCommand(
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
    }
}
