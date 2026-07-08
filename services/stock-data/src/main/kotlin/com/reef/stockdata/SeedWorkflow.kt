package com.reef.stockdata

import java.time.Instant

/**
 * Seed-once orchestration: a game seed calls the provider at most once. Any
 * later request for the same gameSeedId reads the persisted batch instead -
 * see docs/STOCK_DATA_SEEDING_PLAN.md "Usage Shape".
 */
class SeedWorkflow(
    private val provider: StockDataProvider,
    private val repository: SeedSnapshotRepository,
) {
    fun seed(gameSeedId: String, symbols: List<String>, asOf: Instant): StockSeedSnapshotBatch {
        repository.find(gameSeedId)?.let { return it }
        val batch = provider.getSeedSnapshots(gameSeedId, symbols, asOf)
        repository.save(batch)
        return batch
    }
}
