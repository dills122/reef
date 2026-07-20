package com.reef.stockdata

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResilienceTest {
    @Test
    fun circuitBreakerOpensShortCircuitsAndClosesAfterSuccessfulHalfOpenTrial() {
        var now = Instant.parse("2026-07-08T15:00:00Z")
        val breaker = CircuitBreaker(
            failureThreshold = 2,
            cooldown = Duration.ofSeconds(30),
            clock = { now }
        )

        repeat(2) {
            assertFailsWith<IllegalStateException> { breaker.call { error("provider unavailable") } }
        }
        assertEquals(CircuitState.OPEN, breaker.currentState())
        assertFailsWith<CircuitOpenException> { breaker.call { "not called" } }

        now = now.plusSeconds(30)
        assertEquals("recovered", breaker.call { "recovered" })
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun failedHalfOpenTrialImmediatelyReopensCircuit() {
        var now = Instant.parse("2026-07-08T15:00:00Z")
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            cooldown = Duration.ofSeconds(10),
            clock = { now }
        )
        assertFailsWith<IllegalArgumentException> { breaker.call { throw IllegalArgumentException("bad") } }
        now = now.plusSeconds(10)

        assertFailsWith<IllegalStateException> { breaker.call { error("still unavailable") } }
        assertEquals(CircuitState.OPEN, breaker.currentState())
    }

    @Test
    fun ttlCacheDistinguishesFreshExpiredAndMissingEntries() {
        var now = Instant.parse("2026-07-08T15:00:00Z")
        val cache = TtlCache<String, String>(Duration.ofSeconds(30)) { now }

        assertNull(cache.getFresh("missing"))
        cache.put("AAPL", "201.25")
        assertEquals("201.25", cache.getFresh("AAPL"))

        now = now.plusSeconds(30)
        assertEquals("201.25", cache.getFresh("AAPL"))
        now = now.plusMillis(1)
        assertNull(cache.getFresh("AAPL"))
        assertEquals("201.25", cache.getEvenIfStale("AAPL"))
    }
}
