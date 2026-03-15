package com.reef.platform.infrastructure.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EngineClientTest {
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
              }
            }
            """.trimIndent()
        )

        assertNotNull(result.accepted)
        assertEquals("eng-ord-1", result.accepted.engineOrderId)
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
    }
}
