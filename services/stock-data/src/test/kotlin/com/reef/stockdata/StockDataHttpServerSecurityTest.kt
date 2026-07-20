package com.reef.stockdata

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StockDataHttpServerSecurityTest {

    @Test
    fun `security config parses clamps and defaults environment values`() {
        assertEquals(
            StockDataHttpSecurityConfig("token", 1024, 500),
            StockDataHttpSecurityConfig.fromEnv(
                mapOf(
                    "STOCK_DATA_API_TOKEN" to " token ",
                    "STOCK_DATA_HTTP_MAX_REQUEST_BYTES" to "12",
                    "STOCK_DATA_MAX_SEED_SYMBOLS" to "900"
                )
            )
        )
        assertEquals(
            StockDataHttpSecurityConfig("", 64 * 1024, 100),
            StockDataHttpSecurityConfig.fromEnv(emptyMap())
        )
    }

    @Test
    fun `tiingo config parses valid environment overrides and ignores invalid values`() {
        assertEquals(
            TiingoConfig(
                apiToken = "tiingo-token",
                currentMaxAgeSeconds = 120,
                providerTimeoutMs = 750,
                providerMaxRetries = 4,
                allowStaleCache = true
            ),
            TiingoConfig.fromEnv(
                mapOf(
                    "TIINGO_API_TOKEN" to "tiingo-token",
                    "STOCK_DATA_CURRENT_MAX_AGE_SECONDS" to "120",
                    "STOCK_DATA_PROVIDER_TIMEOUT_MS" to "750",
                    "STOCK_DATA_PROVIDER_MAX_RETRIES" to "4",
                    "STOCK_DATA_ALLOW_STALE_CACHE" to "true"
                )
            )
        )
        assertEquals(TiingoConfig(apiToken = ""), TiingoConfig.fromEnv(mapOf("STOCK_DATA_PROVIDER_TIMEOUT_MS" to "bad")))
    }

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
