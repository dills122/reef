package com.reef.platform.api

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlatformHttpServerHelpersTest {
    @Test
    fun rootCauseUnwrapsNestedCompletionAndExecutionExceptions() {
        val root = IllegalStateException("boom")
        val wrapped = CompletionException(ExecutionException(root))

        assertSame(root, rootCause(wrapped))
    }

    @Test
    fun rootCauseReturnsInputWhenNotWrapped() {
        val plain = IllegalStateException("boom")
        assertSame(plain, rootCause(plain))
    }

    @Test
    fun rootCauseStopsAtWrapperWithNoCause() {
        val wrapper = CompletionException("no cause", null)
        assertSame(wrapper, rootCause(wrapper))
    }

    @Test
    fun rootMessageReturnsRootCauseMessage() {
        val wrapped = CompletionException(ExecutionException(IllegalStateException("root failure")))
        assertEquals("root failure", rootMessage(wrapped))
    }

    @Test
    fun rootMessageFallsBackToUnknownWhenMessageIsNull() {
        val wrapped = CompletionException(ExecutionException(IllegalStateException()))
        assertEquals("unknown", rootMessage(wrapped))
    }

    @Test
    fun requiredLongParsesValidInteger() {
        val doc = JsonCodec.parseObject("""{"quantity":"42"}""")
        assertEquals(42L, doc.requiredLong("quantity"))
    }

    @Test
    fun requiredLongThrowsOnMissingOrInvalidValue() {
        val doc = JsonCodec.parseObject("""{"quantity":"not-a-number"}""")
        assertFailsWith<IllegalArgumentException> { doc.requiredLong("quantity") }
        assertFailsWith<IllegalArgumentException> { doc.requiredLong("missing") }
    }

    @Test
    fun requiredIntParsesValidInteger() {
        val doc = JsonCodec.parseObject("""{"count":"7"}""")
        assertEquals(7, doc.requiredInt("count"))
    }

    @Test
    fun requiredIntThrowsOnMissingOrInvalidValue() {
        val doc = JsonCodec.parseObject("""{"count":"nope"}""")
        assertFailsWith<IllegalArgumentException> { doc.requiredInt("count") }
        assertFailsWith<IllegalArgumentException> { doc.requiredInt("missing") }
    }

    @Test
    fun booleanParsesCaseInsensitiveTrueAndFalse() {
        val doc = JsonCodec.parseObject("""{"a":"true","b":"TRUE","c":"false","d":"anything"}""")
        assertEquals(true, doc.boolean("a"))
        assertEquals(true, doc.boolean("b"))
        assertEquals(false, doc.boolean("c"))
        assertEquals(false, doc.boolean("d"))
        assertEquals(false, doc.boolean("missing"))
    }

    // Regression tests for a gap where /api/v1/orders/current|history|fills
    // and /api/v1/arena/leaderboard read routes passed a caller-supplied
    // "limit" query param straight through to internal domain methods that
    // treat limit=0 as "no LIMIT clause at all" (unbounded). An absent or
    // explicit ?limit=0 used to mean unlimited results for those specific
    // downstream signatures; boundedQueryLimit closes that at the boundary.
    @Test
    fun boundedQueryLimitUsesDefaultWhenParamIsAbsent() {
        assertEquals(50, boundedQueryLimit("", defaultValue = 50))
    }

    @Test
    fun boundedQueryLimitUsesDefaultWhenParamIsInvalid() {
        assertEquals(50, boundedQueryLimit("not-a-number", defaultValue = 50))
    }

    @Test
    fun boundedQueryLimitUsesDefaultWhenParamIsZeroOrNegative() {
        assertEquals(50, boundedQueryLimit("0", defaultValue = 50))
        assertEquals(50, boundedQueryLimit("-1", defaultValue = 50))
    }

    @Test
    fun boundedQueryLimitPassesThroughValidPositiveValue() {
        assertEquals(17, boundedQueryLimit("17", defaultValue = 50))
    }

    @Test
    fun boundedQueryLimitClampsCallerSuppliedValueAboveMax() {
        assertEquals(500, boundedQueryLimit("999999999", defaultValue = 50, max = 500))
        assertEquals(10, boundedQueryLimit("999999999", defaultValue = 50, max = 10))
    }

    @Test
    fun serverBoundaryDepsDerivesDefaultsFromMinimalConstructorArgs() {
        val deps = ServerBoundaryDeps(
            boundary = ExternalApiBoundary(),
            abuseProtectionHook = AllowAllAbuseProtectionHook(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = InMemoryCommandCaptureStore()
        )

        assertEquals(null, deps.accountRiskControlStore)
        assertEquals(null, deps.accountRiskDecisionLog)
        assertEquals(null, deps.commandCircuitBreakerStore)
        assertEquals(null, deps.instrumentPriceCollarStore)
        assertEquals(null, deps.streamCommandIntakeStore)
        assertEquals(null, deps.streamCommandPublisher)
        assertEquals(null, deps.streamCommandHealthCheck)
        assertEquals(0.95, deps.streamCommandMaxStorageUtilization)
        assertEquals(100L, deps.streamCommandBackpressureSampleMs)
        assertEquals(CommandProcessingMode.SyncResult, deps.commandProcessingMode)
        assertTrue(deps.commandStatusLookup === deps.commandCaptureStore)
        assertEquals(null, deps.capturedCommandQueue)
    }
}
