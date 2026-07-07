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
    fun idempotencyStoreDoesNotOverwriteExistingEntry() {
        val store = InMemoryIdempotencyStore()
        store.save(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            result = IdempotencyResult(200, """{"ok":true}"""),
            ttlClass = IdempotencyTtlClass.STANDARD
        )
        // A second save with the same key must not replace the first result,
        // matching putIfAbsent semantics for idempotent replay.
        store.save(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            result = IdempotencyResult(500, """{"ok":false}"""),
            ttlClass = IdempotencyTtlClass.STANDARD
        )

        val found = store.find("client-1", "/api/v1/orders/submit", "idem-1")
        assertNotNull(found)
        assertEquals(200, found.status)
    }

    @Test
    fun idempotencyStoreCleanupExpiredRemovesStaleEntries() {
        val store = InMemoryIdempotencyStore()
        store.save(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            result = IdempotencyResult(200, """{"ok":true}"""),
            ttlClass = IdempotencyTtlClass.SHORT
        )

        // Cleanup with a "now" far in the future should evict the entry even
        // though real time hasn't elapsed, exercising the removeIf branch.
        store.cleanupExpired(Instant.now().plusSeconds(365L * 24 * 60 * 60))

        assertNull(store.find("client-1", "/api/v1/orders/submit", "idem-1"))
    }

    @Test
    fun idempotencyStoreCleanupExpiredKeepsFreshEntries() {
        val store = InMemoryIdempotencyStore()
        store.save(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            result = IdempotencyResult(200, """{"ok":true}"""),
            ttlClass = IdempotencyTtlClass.LONG
        )

        store.cleanupExpired(Instant.now())

        val found = store.find("client-1", "/api/v1/orders/submit", "idem-1")
        assertNotNull(found)
        assertEquals(200, found.status)
    }

    @Test
    fun staticTokenAuthHookRequiresMatchingClientToken() {
        val hook = StaticTokenAuthHook(mapOf("client-1" to "token-1"))
        assertNull(hook.authorize("client-1", "Bearer token-1"))
        assertEquals("UNAUTHORIZED", hook.authorize("client-1", "Bearer wrong")?.code)
        assertEquals("UNAUTHORIZED", hook.authorize("unknown", "Bearer token-1")?.code)
    }

    @Test
    fun staticAccountRiskCheckReturnsConfiguredDecisions() {
        val hook = StaticAccountRiskCheck(
            rejectedAccounts = setOf("reject-account"),
            backpressuredAccounts = setOf("slow-account"),
            disabledBots = setOf("disabled-bot")
        )

        assertEquals(AccountRiskDecision.ALLOW, hook.evaluate(accountRiskRequest()).decision)
        assertEquals(
            AccountRiskDecision.REJECT,
            hook.evaluate(accountRiskRequest(accountId = "reject-account")).decision
        )
        assertEquals(
            AccountRiskDecision.BACKPRESSURE,
            hook.evaluate(accountRiskRequest(accountId = "slow-account")).decision
        )
        assertEquals(
            AccountRiskDecision.DISABLED_BOT,
            hook.evaluate(accountRiskRequest(botId = "disabled-bot")).decision
        )
        assertEquals(429, AccountRiskCheckResult(AccountRiskDecision.BACKPRESSURE).toBoundaryError()?.status)
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
    fun inMemoryRateLimitStorePrunesOldWindows() {
        val store = InMemoryRateLimitStore(retainedWindows = 2)

        assertEquals(1L, store.increment("client-1", "/api/v1/orders/submit", 0, 60))
        assertEquals(1L, store.increment("client-2", "/api/v1/orders/submit", 60, 60))
        assertEquals(2, store.entryCount())

        assertEquals(1L, store.increment("client-3", "/api/v1/orders/submit", 120, 60))

        assertEquals(2, store.entryCount())
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
    fun rejectRateAbuseHookCleansExpiredStateWithoutDoubleCountingRelease() {
        var now = Instant.ofEpochSecond(100)
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 10,
            blockSeconds = 5,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            clock = { now }
        )

        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        assertEquals(1, hook.trackedStateCount())
        assertEquals(1, hook.stats().activeBlockedClients)

        now = Instant.ofEpochSecond(106)
        val released = hook.stats()
        assertEquals(0, released.activeBlockedClients)
        assertEquals(1, released.releases)
        assertEquals(1, hook.trackedStateCount())
        assertEquals(1, hook.stats().releases)

        now = Instant.ofEpochSecond(111)
        hook.stats()
        assertEquals(0, hook.trackedStateCount())
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

    @Test
    fun rejectRateAbuseHookAppliesRoutePolicyOverrides() {
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 10,
            windowSeconds = 60,
            blockSeconds = 30,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit", "/api/v1/orders/modify"),
            routePolicies = mapOf(
                "/api/v1/orders/modify" to RejectRatePolicy(maxRejects = 1, windowSeconds = 30, blockSeconds = 45)
            ),
            clock = { Instant.ofEpochSecond(360) }
        )

        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        hook.observe("client-1", "/api/v1/orders/submit", 200, "INVALID_STATE")
        assertNull(hook.allow("client-1", "/api/v1/orders/submit"))

        hook.observe("client-1", "/api/v1/orders/modify", 200, "INVALID_STATE")
        hook.observe("client-1", "/api/v1/orders/modify", 200, "INVALID_STATE")
        assertEquals("ABUSE_BLOCKED", hook.allow("client-1", "/api/v1/orders/modify")?.code)
    }

    @Test
    fun parseStaticTokensParsesValidPairsAndSkipsMalformed() {
        val parsed = parseStaticTokens("client-1:token-1,bad-entry,client-2: token-2 , :missing-client,client-3:")
        assertEquals(mapOf("client-1" to "token-1", "client-2" to "token-2"), parsed)
        assertEquals(emptyMap(), parseStaticTokens(null))
        assertEquals(emptyMap(), parseStaticTokens("  "))
    }

    @Test
    fun parseCsvSetTrimsAndFiltersBlanks() {
        assertEquals(setOf("a", "b", "c"), parseCsvSet(" a, b ,,c"))
        assertEquals(emptySet(), parseCsvSet(null))
        assertEquals(emptySet(), parseCsvSet(""))
    }

    @Test
    fun parseRejectCodesFallsBackWhenBlankOrEmpty() {
        val fallback = setOf("INVALID_STATE", "NOT_FOUND", "REFERENCE_DATA_ERROR", "VALIDATION_ERROR")
        assertEquals(fallback, parseRejectCodes(null))
        assertEquals(fallback, parseRejectCodes(""))
        assertEquals(fallback, parseRejectCodes(" , "))
        assertEquals(setOf("CUSTOM_CODE"), parseRejectCodes("custom_code"))
    }

    @Test
    fun parseTrackedRoutesFallsBackWhenBlankOrEmpty() {
        val fallback = setOf("/api/v1/orders/submit", "/api/v1/orders/modify", "/api/v1/orders/cancel")
        assertEquals(fallback, parseTrackedRoutes(null))
        assertEquals(fallback, parseTrackedRoutes(" , "))
        assertEquals(setOf("/custom/route"), parseTrackedRoutes("/custom/route"))
    }

    @Test
    fun parseRoutePoliciesParsesValidEntriesAndSkipsMalformed() {
        val parsed = parseRoutePolicies(
            "/api/v1/orders/submit:5/30/60,malformed,/api/v1/orders/modify:0/30/60,/api/v1/orders/cancel:bad/30/60"
        )
        assertEquals(
            mapOf("/api/v1/orders/submit" to RejectRatePolicy(5, 30, 60)),
            parsed
        )
        assertEquals(emptyMap(), parseRoutePolicies(null))
        assertEquals(emptyMap(), parseRoutePolicies(""))
    }

    @Test
    fun envBoolParsesRecognizedValuesAndFallsBackOtherwise() {
        assertEquals(true, envBool("true", false))
        assertEquals(true, envBool("1", false))
        assertEquals(true, envBool("YES", false))
        assertEquals(false, envBool("false", true))
        assertEquals(false, envBool("0", true))
        assertEquals(false, envBool("off", true))
        assertEquals(true, envBool(null, true))
        assertEquals(false, envBool("garbage", false))
    }

    private fun accountRiskRequest(
        accountId: String = "account-1",
        botId: String = "bot-1"
    ): AccountRiskCheckRequest {
        return AccountRiskCheckRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            commandType = "SubmitOrder",
            commandId = "cmd-1",
            idempotencyKey = "idem-1",
            correlationId = "corr-1",
            actorId = "actor-1",
            participantId = "participant-1",
            accountId = accountId,
            botId = botId,
            runId = "run-1",
            venueSessionId = "session-1",
            instrumentId = "AAPL",
            orderId = "ord-1",
            payloadHash = "hash-1"
        )
    }
}
