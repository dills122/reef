package com.reef.stockdata

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class ScriptedTiingoHttpClient(
    private val current: (String) -> TiingoIexQuote,
    private val eod: (String) -> TiingoEodRow,
) : TiingoHttpClient {
    var currentCallCount = 0
        private set
    var eodCallCount = 0
        private set

    override fun getIexCurrent(symbol: String): TiingoIexQuote {
        currentCallCount += 1
        return current(symbol)
    }

    override fun getEodOnOrBefore(symbol: String, onOrBefore: LocalDate): TiingoEodRow {
        eodCallCount += 1
        return eod(symbol)
    }
}

class TiingoStockDataProviderTest {

    private val regularHoursInstant = ZonedDateTime.of(
        LocalDate.parse("2026-07-08"),
        LocalTime.parse("15:00:00"),
        MarketSession.ET_ZONE,
    ).toInstant()

    private val weekendInstant = ZonedDateTime.of(
        LocalDate.parse("2026-07-04"),
        LocalTime.parse("12:00:00"),
        MarketSession.ET_ZONE,
    ).toInstant()

    private fun freshQuote(price: BigDecimal, asOf: Instant) = TiingoIexQuote(
        ticker = "AAPL",
        tngoLast = price,
        last = price,
        prevClose = price,
        open = price,
        high = price,
        low = price,
        volume = 100L,
        quoteTimestamp = asOf.minusSeconds(10),
        rawPayload = "{}",
    )

    private fun eodRow(price: BigDecimal, date: LocalDate) = TiingoEodRow(
        date = date,
        close = price,
        open = price,
        high = price,
        low = price,
        volume = 500L,
        rawPayload = "{}",
    )

    @Test
    fun `fresh current price is used during regular hours`() {
        val client = ScriptedTiingoHttpClient(
            current = { freshQuote(BigDecimal("101.50"), regularHoursInstant) },
            eod = { error("EOD should not be called when current data is fresh") },
        )
        val provider = TiingoStockDataProvider(TiingoConfig(apiToken = "x"), client)

        val snapshot = provider.getSeedSnapshots("game-1", listOf("AAPL"), regularHoursInstant).snapshots.single()

        assertEquals(SourceType.INTRADAY_CURRENT, snapshot.sourceType)
        assertEquals(BigDecimal("101.50"), snapshot.price)
        assertEquals(1, client.currentCallCount)
    }

    @Test
    fun `stale current data falls back to EOD close`() {
        val staleQuote = freshQuote(BigDecimal("101.50"), regularHoursInstant)
            .copy(quoteTimestamp = regularHoursInstant.minus(Duration.ofHours(2)))
        val client = ScriptedTiingoHttpClient(
            current = { staleQuote },
            eod = { eodRow(BigDecimal("99.00"), LocalDate.parse("2026-07-07")) },
        )
        val provider = TiingoStockDataProvider(TiingoConfig(apiToken = "x", currentMaxAgeSeconds = 900), client)

        val snapshot = provider.getSeedSnapshots("game-1", listOf("AAPL"), regularHoursInstant).snapshots.single()

        assertEquals(SourceType.HISTORICAL_EOD, snapshot.sourceType)
        assertEquals(BigDecimal("99.00"), snapshot.price)
        assertTrue(snapshot.selectionReason.contains("stale_current_data"))
    }

    @Test
    fun `provider timeout on current falls back to EOD close`() {
        val client = ScriptedTiingoHttpClient(
            current = { throw TiingoTimeoutException("timed out") },
            eod = { eodRow(BigDecimal("98.00"), LocalDate.parse("2026-07-07")) },
        )
        val provider = TiingoStockDataProvider(TiingoConfig(apiToken = "x"), client)

        val snapshot = provider.getSeedSnapshots("game-1", listOf("AAPL"), regularHoursInstant).snapshots.single()

        assertEquals(SourceType.HISTORICAL_EOD, snapshot.sourceType)
        assertTrue(snapshot.selectionReason.contains("provider_timeout"))
    }

    @Test
    fun `missing symbol on both current and EOD fails closed with symbol_not_supported`() {
        val client = ScriptedTiingoHttpClient(
            current = { throw TiingoSymbolNotSupportedException("no such symbol") },
            eod = { throw TiingoSymbolNotSupportedException("no such symbol") },
        )
        val provider = TiingoStockDataProvider(TiingoConfig(apiToken = "x"), client)

        val ex = assertFailsWith<StockDataSeedException> {
            provider.getSeedSnapshots("game-1", listOf("ZZZZ"), regularHoursInstant)
        }
        assertEquals(FailureCategory.SYMBOL_NOT_SUPPORTED, ex.category)
    }

    @Test
    fun `no historical data available fails closed with historical_fallback_unavailable`() {
        val client = ScriptedTiingoHttpClient(
            current = { throw TiingoUnavailableException("down") },
            eod = { throw TiingoNoHistoricalDataException("no rows") },
        )
        val provider = TiingoStockDataProvider(TiingoConfig(apiToken = "x", allowStaleCache = false), client)

        val ex = assertFailsWith<StockDataSeedException> {
            provider.getSeedSnapshots("game-1", listOf("AAPL"), regularHoursInstant)
        }
        assertEquals(FailureCategory.HISTORICAL_FALLBACK_UNAVAILABLE, ex.category)
    }

    @Test
    fun `weekend never calls the current endpoint`() {
        val client = ScriptedTiingoHttpClient(
            current = { error("current endpoint must not be called when market is closed") },
            eod = { eodRow(BigDecimal("97.00"), LocalDate.parse("2026-07-02")) },
        )
        val provider = TiingoStockDataProvider(TiingoConfig(apiToken = "x"), client)

        val snapshot = provider.getSeedSnapshots("game-1", listOf("AAPL"), weekendInstant).snapshots.single()

        assertEquals(0, client.currentCallCount)
        assertEquals(SourceType.HISTORICAL_EOD, snapshot.sourceType)
        assertEquals(BigDecimal("97.00"), snapshot.price)
    }

    @Test
    fun `cached fallback is used only when explicitly enabled and current plus EOD both fail`() {
        val cache = TtlCache<CacheKey, StockSeedSnapshot>(ttl = Duration.ofMinutes(5))
        val client = ScriptedTiingoHttpClient(
            current = { freshQuote(BigDecimal("101.50"), regularHoursInstant) },
            eod = { eodRow(BigDecimal("100.00"), LocalDate.parse("2026-07-07")) },
        )
        val provider = TiingoStockDataProvider(
            TiingoConfig(apiToken = "x", allowStaleCache = true),
            client,
            cache = cache,
        )

        // First call succeeds and populates the cache.
        val warm = provider.getSeedSnapshots("game-1", listOf("AAPL"), regularHoursInstant).snapshots.single()
        assertEquals(SourceType.INTRADAY_CURRENT, warm.sourceType)

        // Second call: force both current and EOD to fail; cached fallback should kick in.
        val failingClient = ScriptedTiingoHttpClient(
            current = { throw TiingoUnavailableException("down") },
            eod = { throw TiingoUnavailableException("down") },
        )
        val fallbackProvider = TiingoStockDataProvider(
            TiingoConfig(apiToken = "x", allowStaleCache = true),
            failingClient,
            cache = cache,
        )
        val fallback = fallbackProvider.getSeedSnapshots("game-2", listOf("AAPL"), regularHoursInstant).snapshots.single()

        assertEquals(SourceType.CACHED_FALLBACK, fallback.sourceType)
        assertEquals(warm.price, fallback.price)
    }
}
