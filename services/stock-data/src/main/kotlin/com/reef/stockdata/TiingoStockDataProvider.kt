package com.reef.stockdata

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal data class CacheKey(val symbol: String, val marketDate: LocalDate, val sourceType: SourceType)

/**
 * Tiingo adapter implementing the "Price Selection Rules" and "Failure
 * Behavior" sections of docs/STOCK_DATA_SEEDING_PLAN.md. Falls back from
 * current IEX data to EOD close, and optionally to a short-lived cache when
 * STOCK_DATA_ALLOW_STALE_CACHE is enabled.
 */
internal class TiingoStockDataProvider(
    private val config: TiingoConfig,
    private val httpClient: TiingoHttpClient,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(),
    private val cache: TtlCache<CacheKey, StockSeedSnapshot> = TtlCache(ttl = Duration.ofMinutes(5)),
    private val clock: () -> Instant = Instant::now,
) : StockDataProvider {

    override fun getSeedSnapshots(
        gameSeedId: String,
        symbols: List<String>,
        asOf: Instant,
    ): StockSeedSnapshotBatch {
        val retrievedAt = clock()
        val session = MarketSession.classify(asOf)
        val snapshots = symbols.map { symbol -> resolveSymbol(gameSeedId, symbol, asOf, retrievedAt, session) }
        return StockSeedSnapshotBatch(gameSeedId, asOf, snapshots)
    }

    /** Attempts the regular-hours current-data path first, then falls back to EOD/cache. */
    private fun resolveSymbol(
        gameSeedId: String,
        symbol: String,
        asOf: Instant,
        retrievedAt: Instant,
        session: MarketSessionClassification,
    ): StockSeedSnapshot {
        var currentFailure: FailureCategory? = null

        if (session.isRegularSession) {
            try {
                val quote = circuitBreaker.call { httpClient.getIexCurrent(symbol) }
                val price = quote.tngoLast ?: quote.last
                val ts = quote.quoteTimestamp
                if (price != null && ts != null && !isStale(ts, asOf)) {
                    val snapshot = StockSeedSnapshot(
                        gameSeedId = gameSeedId,
                        symbol = symbol,
                        provider = "tiingo",
                        sourceType = SourceType.INTRADAY_CURRENT,
                        asOf = asOf,
                        sourceTimestamp = ts,
                        retrievedAt = retrievedAt,
                        currency = "USD",
                        price = price,
                        open = quote.open,
                        high = quote.high,
                        low = quote.low,
                        previousClose = quote.prevClose,
                        volume = quote.volume,
                        rawProviderPayloadHash = sha256(quote.rawPayload),
                        selectionReason = "tngoLast within ${config.currentMaxAgeSeconds}s freshness window",
                    )
                    cache.put(CacheKey(symbol, session.marketDate, SourceType.INTRADAY_CURRENT), snapshot)
                    return snapshot
                }
                currentFailure = FailureCategory.STALE_CURRENT_DATA
            } catch (ex: Exception) {
                currentFailure = mapException(ex)
            }
        }

        try {
            val row = circuitBreaker.call { httpClient.getEodOnOrBefore(symbol, session.marketDate) }
            val reason = if (currentFailure != null) {
                "current data unavailable (${currentFailure.wireValue}), used EOD close"
            } else {
                "outside regular session (${session.reason}), used EOD close"
            }
            val snapshot = StockSeedSnapshot(
                gameSeedId = gameSeedId,
                symbol = symbol,
                provider = "tiingo",
                sourceType = SourceType.HISTORICAL_EOD,
                asOf = asOf,
                sourceTimestamp = row.date.atStartOfDay(MarketSession.ET_ZONE).toInstant(),
                retrievedAt = retrievedAt,
                currency = "USD",
                price = row.close,
                open = row.open,
                high = row.high,
                low = row.low,
                previousClose = null,
                volume = row.volume,
                rawProviderPayloadHash = sha256(row.rawPayload),
                selectionReason = reason,
            )
            cache.put(CacheKey(symbol, row.date, SourceType.HISTORICAL_EOD), snapshot)
            return snapshot
        } catch (ex: Exception) {
            val eodFailure = mapException(ex)
            return resolveWithCacheOrThrow(gameSeedId, symbol, asOf, retrievedAt, session, eodFailure)
        }
    }

    private fun resolveWithCacheOrThrow(
        gameSeedId: String,
        symbol: String,
        asOf: Instant,
        retrievedAt: Instant,
        session: MarketSessionClassification,
        reportedFailure: FailureCategory,
    ): StockSeedSnapshot {
        if (config.allowStaleCache) {
            val cached = cache.getEvenIfStale(CacheKey(symbol, session.marketDate, SourceType.HISTORICAL_EOD))
                ?: cache.getEvenIfStale(CacheKey(symbol, session.marketDate, SourceType.INTRADAY_CURRENT))
            if (cached != null) {
                return cached.copy(
                    gameSeedId = gameSeedId,
                    sourceType = SourceType.CACHED_FALLBACK,
                    asOf = asOf,
                    retrievedAt = retrievedAt,
                    selectionReason = "stale cache fallback: original sourceType=${cached.sourceType.wireValue}",
                )
            }
        }
        throw StockDataSeedException(
            symbol = symbol,
            category = reportedFailure,
            message = "no current, EOD, or cached data available for $symbol",
        )
    }

    private fun isStale(sourceTimestamp: Instant, asOf: Instant): Boolean =
        Duration.between(sourceTimestamp, asOf).abs().seconds > config.currentMaxAgeSeconds

    private fun mapException(ex: Exception): FailureCategory = when (ex) {
        is TiingoSymbolNotSupportedException -> FailureCategory.SYMBOL_NOT_SUPPORTED
        is TiingoNoHistoricalDataException -> FailureCategory.HISTORICAL_FALLBACK_UNAVAILABLE
        is TiingoRateLimitedException -> FailureCategory.PROVIDER_RATE_LIMITED
        is TiingoTimeoutException -> FailureCategory.PROVIDER_TIMEOUT
        is TiingoInvalidPayloadException -> FailureCategory.INVALID_PROVIDER_PAYLOAD
        is CircuitOpenException -> FailureCategory.PROVIDER_UNAVAILABLE
        is TiingoUnavailableException -> FailureCategory.PROVIDER_UNAVAILABLE
        else -> FailureCategory.PROVIDER_UNAVAILABLE
    }

    private fun sha256(payload: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
