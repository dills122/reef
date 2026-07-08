package com.reef.stockdata

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant

/**
 * Source of a persisted seed snapshot. Serialized form matches the values used
 * in docs/STOCK_DATA_SEEDING_PLAN.md so persisted rows and docs stay aligned.
 */
enum class SourceType(val wireValue: String) {
    INTRADAY_CURRENT("intraday_current"),
    HISTORICAL_EOD("historical_eod"),
    CACHED_FALLBACK("cached_fallback"),
}

/** Allowed failure categories for a seed request, per STOCK_DATA_SEEDING_PLAN.md. */
enum class FailureCategory(val wireValue: String) {
    SYMBOL_NOT_SUPPORTED("symbol_not_supported"),
    PROVIDER_RATE_LIMITED("provider_rate_limited"),
    PROVIDER_TIMEOUT("provider_timeout"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    STALE_CURRENT_DATA("stale_current_data"),
    HISTORICAL_FALLBACK_UNAVAILABLE("historical_fallback_unavailable"),
    INVALID_PROVIDER_PAYLOAD("invalid_provider_payload"),
}

/**
 * Game creation fails closed: any unresolved required symbol aborts the whole
 * seed request rather than seeding a partial batch.
 */
class StockDataSeedException(
    val symbol: String,
    val category: FailureCategory,
    message: String,
    cause: Throwable? = null,
) : Exception("symbol=$symbol category=${category.wireValue}: $message", cause)

/** One normalized seed row, matching the "Seed Snapshot Shape" table in the plan doc. */
data class StockSeedSnapshot(
    val gameSeedId: String,
    val symbol: String,
    val provider: String,
    val sourceType: SourceType,
    val asOf: Instant,
    val sourceTimestamp: Instant,
    val retrievedAt: Instant,
    val currency: String,
    val price: BigDecimal,
    val open: BigDecimal?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val previousClose: BigDecimal?,
    val volume: Long?,
    val rawProviderPayloadHash: String,
    val selectionReason: String,
)

/**
 * A batch of snapshots for one game seed request, plus a hash over the
 * ordered snapshots so replay/handoff flows can verify the external
 * reference state a game started from.
 */
data class StockSeedSnapshotBatch(
    val gameSeedId: String,
    val asOf: Instant,
    val snapshots: List<StockSeedSnapshot>,
) {
    val batchSeedHash: String get() = computeBatchSeedHash(snapshots)

    companion object {
        fun computeBatchSeedHash(snapshots: List<StockSeedSnapshot>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (snapshot in snapshots.sortedBy { it.symbol }) {
                val canonical = listOf(
                    snapshot.symbol,
                    snapshot.provider,
                    snapshot.sourceType.wireValue,
                    snapshot.sourceTimestamp.toString(),
                    snapshot.currency,
                    snapshot.price.stripTrailingZeros().toPlainString(),
                    snapshot.rawProviderPayloadHash,
                ).joinToString("|")
                digest.update(canonical.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Seed-time-only boundary to an external stock reference provider. Callers
 * must persist the returned batch and never call this again for the same
 * gameSeedId — see docs/STOCK_DATA_SEEDING_PLAN.md "Usage Shape".
 */
interface StockDataProvider {
    fun getSeedSnapshots(
        gameSeedId: String,
        symbols: List<String>,
        asOf: Instant,
    ): StockSeedSnapshotBatch
}
