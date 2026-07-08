package com.reef.stockdata

/**
 * Durable store for seed snapshot batches. Once a batch is saved for a
 * gameSeedId, the seed workflow must read from here instead of calling the
 * provider again - see docs/STOCK_DATA_SEEDING_PLAN.md "Usage Shape".
 */
interface SeedSnapshotRepository {
    fun find(gameSeedId: String): StockSeedSnapshotBatch?
    fun save(batch: StockSeedSnapshotBatch)
}
