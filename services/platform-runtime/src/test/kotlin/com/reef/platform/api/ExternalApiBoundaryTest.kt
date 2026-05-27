package com.reef.platform.api

import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.time.Instant

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

    @Test
    fun idempotencyStoreScopesByClientRouteAndKey() {
        val store = InMemoryIdempotencyStore()
        store.save(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            result = IdempotencyResult(200, """{"ok":true}"""),
            ttlClass = IdempotencyTtlClass.STANDARD
        )

        val found = store.find("client-1", "/api/v1/orders/submit", "idem-1")
        val otherRoute = store.find("client-1", "/api/v1/orders/cancel", "idem-1")

        assertNotNull(found)
        assertEquals(200, found.status)
        assertNull(otherRoute)
    }

    @Test
    fun staticTokenAuthHookRequiresMatchingClientToken() {
        val hook = StaticTokenAuthHook(mapOf("client-1" to "token-1"))
        assertNull(hook.authorize("client-1", "Bearer token-1"))
        assertEquals("UNAUTHORIZED", hook.authorize("client-1", "Bearer wrong")?.code)
        assertEquals("UNAUTHORIZED", hook.authorize("unknown", "Bearer token-1")?.code)
    }

    @Test
    fun fixedWindowRateLimitHookRejectsAfterThreshold() {
        val store = InMemoryRateLimitStore()
        val hook = FixedWindowRateLimitHook(
            store = store,
            maxRequests = 2,
            windowSeconds = 60,
            clock = { Instant.ofEpochSecond(120) }
        )

        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))
        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))
        val limited = hook.allow("client-1", "/api/v1/orders/submit")
        assertEquals("RATE_LIMITED", limited?.code)
    }

    @Test
    fun rejectRateAbuseHookBlocksClientAfterTrackedRejectThreshold() {
        var now = Instant.ofEpochSecond(120)
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 2,
            windowSeconds = 60,
            blockSeconds = 30,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            clock = { now }
        )

        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))
        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))
        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")

        val blocked = hook.allow("client-1", "/api/v1/orders/submit")
        assertEquals("ABUSE_BLOCKED", blocked?.code)

        now = Instant.ofEpochSecond(151)
        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))
    }

    @Test
    fun rejectRateAbuseHookIgnoresNonTrackedRejectCodes() {
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 60,
            blockSeconds = 30,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            clock = { Instant.ofEpochSecond(200) }
        )

        hook.observe("client-1", "/api/v1/orders/submit", 200, "NOT_FOUND")
        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))
    }

    @Test
    fun rejectRateAbuseHookScopesByTrackedRoutes() {
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 60,
            blockSeconds = 30,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            clock = { Instant.ofEpochSecond(240) }
        )

        hook.observe("client-1", "/api/v1/orders/modify", 200, "INVALID_STATE")
        hook.observe("client-1", "/api/v1/orders/modify", 200, "INVALID_STATE")
        assertNull(hook.allow("client-1", "/api/v1/orders/modify"))
        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))

        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        assertEquals("ABUSE_BLOCKED", hook.allow("client-1", "/api/v1/orders/submit")?.code)
    }
}
