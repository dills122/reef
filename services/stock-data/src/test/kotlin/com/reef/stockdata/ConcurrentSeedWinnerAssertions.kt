package com.reef.stockdata

import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Both requests reach provider before either can publish, proving both observed absence. */
internal fun assertConcurrentSeedWinner(repository: SeedSnapshotRepository, gameSeedId: String = "race-seed") {
    val providersReady = CyclicBarrier(2)
    val asOf = Instant.parse("2026-07-08T15:00:00Z")
    val candidates = listOf(
        FakeStockDataProvider(fixedPrices = mapOf("AAPL" to BigDecimal("100.00")))
            .getSeedSnapshots(gameSeedId, listOf("AAPL", "MSFT"), asOf),
        FakeStockDataProvider(fixedPrices = mapOf("AAPL" to BigDecimal("200.00")))
            .getSeedSnapshots(gameSeedId, listOf("AAPL", "GOOG"), asOf.plusSeconds(1)),
    )
    assertNotEquals(candidates[0].batchSeedHash, candidates[1].batchSeedHash)
    val executor = Executors.newFixedThreadPool(2)
    try {
        val responses = candidates.map { candidate ->
            executor.submit<StockSeedSnapshotBatch> {
                val provider = object : StockDataProvider {
                    override fun getSeedSnapshots(gameSeedId: String, symbols: List<String>, asOf: Instant): StockSeedSnapshotBatch {
                        providersReady.await(10, TimeUnit.SECONDS)
                        return candidate
                    }
                }
                SeedWorkflow(provider, repository).seed(gameSeedId, candidate.snapshots.map { it.symbol }, candidate.asOf)
            }
        }.map { it.get(15, TimeUnit.SECONDS) }
        val replayProvider = object : StockDataProvider {
            override fun getSeedSnapshots(gameSeedId: String, symbols: List<String>, asOf: Instant): StockSeedSnapshotBatch =
                error("Replay must not call provider")
        }
        val replay = SeedWorkflow(replayProvider, repository).seed(gameSeedId, listOf("TSLA"), asOf.plusSeconds(2))
        assertEquals(responses[0], responses[1], "Both starts must return exact persisted winner")
        assertEquals(responses[0], replay, "Replay must equal both starts, including every snapshot field")
        assertEquals(responses[0].batchSeedHash, replay.batchSeedHash)
        assertTrue(replay in candidates, "Winner must be one whole candidate, never a union of symbols")
        assertEquals(replay, repository.find(gameSeedId))
    } finally {
        executor.shutdownNow()
    }
}
