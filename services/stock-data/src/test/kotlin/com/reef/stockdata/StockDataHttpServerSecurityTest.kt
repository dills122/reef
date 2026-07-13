package com.reef.stockdata

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StockDataHttpServerSecurityTest {

    @Test
    fun `remote seed calls require configured bearer token`() {
        val security = StockDataHttpSecurityConfig(apiToken = "seed-token", maxRequestBodyBytes = 1024, maxSymbols = 2)

        assertEquals(401 to "unauthorized", stockDataAuthError("Bearer wrong", isLoopback = false, security))
        assertEquals(null, stockDataAuthError("Bearer seed-token", isLoopback = false, security))
    }

    @Test
    fun `blank token is local-only instead of remote fail-open`() {
        val security = StockDataHttpSecurityConfig(apiToken = "", maxRequestBodyBytes = 1024, maxSymbols = 2)

        assertEquals(null, stockDataAuthError("", isLoopback = true, security))
        assertEquals(
            503 to "stock data API token is not configured",
            stockDataAuthError("", isLoopback = false, security),
        )
    }

    @Test
    fun `seed request validates symbol count and syntax before provider work`() {
        val security = StockDataHttpSecurityConfig(apiToken = "seed-token", maxRequestBodyBytes = 1024, maxSymbols = 2)

        validateSeedRequest(
            SeedRequest("game-1", listOf("AAPL", "BRK.B"), Instant.parse("2026-07-08T15:00:00Z")),
            security,
        )
        assertFailsWith<IllegalArgumentException> {
            validateSeedRequest(SeedRequest("game-1", listOf("AAPL", "MSFT", "GOOG"), null), security)
        }
        assertFailsWith<IllegalArgumentException> {
            validateSeedRequest(SeedRequest("game-1", listOf("AAPL?token=secret"), null), security)
        }
    }
}
