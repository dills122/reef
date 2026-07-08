package com.reef.stockdata

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant

/**
 * Deterministic in-memory provider for tests and local scenarios - no
 * network calls. Same symbol always yields the same price so scenario
 * assertions and replay tests stay stable.
 */
class FakeStockDataProvider(
    private val fixedPrices: Map<String, BigDecimal> = emptyMap(),
    private val unsupportedSymbols: Set<String> = emptySet(),
) : StockDataProvider {

    var callCount: Int = 0
        private set

    val requestedSymbolBatches: MutableList<List<String>> = mutableListOf()

    override fun getSeedSnapshots(
        gameSeedId: String,
        symbols: List<String>,
        asOf: Instant,
    ): StockSeedSnapshotBatch {
        callCount += 1
        requestedSymbolBatches += symbols

        val snapshots = symbols.map { symbol ->
            if (symbol in unsupportedSymbols) {
                throw StockDataSeedException(
                    symbol = symbol,
                    category = FailureCategory.SYMBOL_NOT_SUPPORTED,
                    message = "fake provider has no data for $symbol",
                )
            }
            val price = fixedPrices[symbol] ?: deterministicPrice(symbol)
            StockSeedSnapshot(
                gameSeedId = gameSeedId,
                symbol = symbol,
                provider = "fake",
                sourceType = SourceType.INTRADAY_CURRENT,
                asOf = asOf,
                sourceTimestamp = asOf,
                retrievedAt = asOf,
                currency = "USD",
                price = price,
                open = price,
                high = price,
                low = price,
                previousClose = price,
                volume = 1_000_000L,
                rawProviderPayloadHash = payloadHash(symbol, price),
                selectionReason = "deterministic fake seed for tests",
            )
        }
        return StockSeedSnapshotBatch(gameSeedId, asOf, snapshots)
    }

    private fun deterministicPrice(symbol: String): BigDecimal {
        val digest = MessageDigest.getInstance("SHA-256").digest(symbol.toByteArray(Charsets.UTF_8))
        val magnitude = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        val cents = 1000 + (magnitude % 49000)
        return BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
    }

    private fun payloadHash(symbol: String, price: BigDecimal): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$symbol:$price".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
