package com.reef.platform.api

import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalApiBoundaryTest {
    @Test
    fun writeCheckRequiresClientId() {
        val boundary = ExternalApiBoundary()
        val headers = Headers()
        headers.add("Idempotency-Key", "idem-1")

        val error = boundary.checkWrite(headers, "/api/v1/orders/submit")

        assertEquals("CLIENT_ID_REQUIRED", error?.code)
    }

    @Test
    fun writeCheckRequiresIdempotencyKey() {
        val boundary = ExternalApiBoundary()
        val headers = Headers()
        headers.add("X-Client-Id", "client-1")

        val error = boundary.checkWrite(headers, "/api/v1/orders/submit")

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", error?.code)
    }

    @Test
    fun writeCheckPassesWithRequiredHeaders() {
        val boundary = ExternalApiBoundary()
        val headers = Headers()
        headers.add("X-Client-Id", "client-1")
        headers.add("Idempotency-Key", "idem-1")
        headers.add("Authorization", "Bearer token")

        val error = boundary.checkWrite(headers, "/api/v1/orders/submit")

        assertNull(error)
    }

    @Test
    fun errorEnvelopeIncludesCodeMessageAndCorrelationId() {
        val boundary = ExternalApiBoundary()
        val payload = boundary.toErrorJson(
            BoundaryError(429, "RATE_LIMITED", "too many requests"),
            "corr-1"
        )

        assertContains(payload, "\"code\":\"RATE_LIMITED\"")
        assertContains(payload, "\"message\":\"too many requests\"")
        assertContains(payload, "\"correlationId\":\"corr-1\"")
    }
}
