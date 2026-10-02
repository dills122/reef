package com.reef.stockdata

import java.util.concurrent.ConcurrentHashMap

class InMemorySeedSnapshotRepository : SeedSnapshotRepository {
    private val batches = ConcurrentHashMap<String, StockSeedSnapshotBatch>()

    override fun find(gameSeedId: String): StockSeedSnapshotBatch? = batches[gameSeedId]

    override fun createOrExisting(batch: StockSeedSnapshotBatch): StockSeedSnapshotBatch {
        val candidate = batch.canonicalCandidate()
        return batches.putIfAbsent(candidate.gameSeedId, candidate) ?: candidate
    }
}
