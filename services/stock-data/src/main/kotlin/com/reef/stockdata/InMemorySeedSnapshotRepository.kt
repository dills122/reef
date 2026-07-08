package com.reef.stockdata

import java.util.concurrent.ConcurrentHashMap

class InMemorySeedSnapshotRepository : SeedSnapshotRepository {
    private val batches = ConcurrentHashMap<String, StockSeedSnapshotBatch>()

    override fun find(gameSeedId: String): StockSeedSnapshotBatch? = batches[gameSeedId]

    override fun save(batch: StockSeedSnapshotBatch) {
        batches[batch.gameSeedId] = batch
    }
}
