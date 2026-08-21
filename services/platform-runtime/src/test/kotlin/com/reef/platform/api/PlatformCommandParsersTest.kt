package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PlatformCommandParsersTest {
    @Test
    fun apiV1SubmitAllowsAndRetainsHiddenLimitOrderType() {
        val body = """
            {
              "commandId":"cmd-hidden-1",
              "traceId":"trace-hidden-1",
              "causationId":"cause-hidden-1",
              "correlationId":"corr-hidden-1",
              "actorId":"trader-1",
              "occurredAt":"2026-03-14T18:00:00Z",
              "orderId":"ord-hidden-1",
              "instrumentId":"XYZ",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"SELL",
              "orderType":"LIMIT_HIDDEN",
              "quantityUnits":"100",
              "limitPrice":"100000000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()

        assertEquals(null, PlatformCommandParsers.validateApiV1Command("/api/v1/orders/submit", body))
        assertEquals("LIMIT_HIDDEN", PlatformCommandParsers.submitOrder(body).orderType)
    }

    @Test
    fun apiV1LifecycleMutationsRequireTargetOwnershipAndRoutingClaims() {
        val cancel = """
            {
              "commandId":"cmd-cancel-1",
              "traceId":"trace-cancel-1",
              "correlationId":"corr-cancel-1",
              "actorId":"trader-1",
              "occurredAt":"2026-03-14T18:00:00Z",
              "orderId":"ord-1",
              "reason":"test"
            }
        """.trimIndent()

        assertContains(
            PlatformCommandParsers.validateApiV1Command("/api/v1/orders/cancel", cancel).orEmpty(),
            "missing required field: runId"
        )
        assertEquals("", PlatformCommandParsers.cancelOrder("""{"scenarioRunId":"legacy-alias"}""").runId)
        assertEquals("", PlatformCommandParsers.modifyOrder("""{"scenarioRunId":"legacy-alias"}""").runId)
    }

    @Test
    fun apiV1CommandsRejectMalformedOccurredAt() {
        val body = """
            {
              "commandId":"cmd-invalid-time",
              "traceId":"trace-invalid-time",
              "correlationId":"corr-invalid-time",
              "actorId":"trader-1",
              "occurredAt":"not-a-timestamp",
              "orderId":"ord-invalid-time",
              "instrumentId":"XYZ",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"100000000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()

        assertEquals(
            "invalid occurredAt: not-a-timestamp",
            PlatformCommandParsers.validateApiV1Command("/api/v1/orders/submit", body)
        )
    }
}
