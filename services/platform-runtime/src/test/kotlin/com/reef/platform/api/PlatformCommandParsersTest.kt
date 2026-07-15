package com.reef.platform.api

import kotlin.test.Test
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
}
