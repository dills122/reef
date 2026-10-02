package com.reef.stockdata

import java.time.temporal.ChronoUnit
import java.util.Collections

/**
 * Durable store for seed snapshot batches. Once a batch is published for a
 * gameSeedId, the seed workflow must read from here instead of calling the
 * provider again - see docs/STOCK_DATA_SEEDING_PLAN.md "Usage Shape".
 */
interface SeedSnapshotRepository {
    fun find(gameSeedId: String): StockSeedSnapshotBatch?

    /**
     * Atomically publish one complete batch, or return the existing immutable winner.
     * A losing candidate must never change the winner's header or append snapshots.
     * The returned batch is canonical persisted state, including storage normalization.
     */
    fun createOrExisting(batch: StockSeedSnapshotBatch): StockSeedSnapshotBatch
}

/** Canonical storage precision/order is shared so memory and PostgreSQL publish identical facts. */
internal fun StockSeedSnapshotBatch.canonicalCandidate(): StockSeedSnapshotBatch {
    require(snapshots.isNotEmpty()) { "Seed batch must contain snapshots" }
    require(snapshots.all { it.gameSeedId == gameSeedId }) { "Every snapshot must belong to batch gameSeedId" }
    require(snapshots.map { it.symbol }.distinct().size == snapshots.size) { "Seed batch symbols must be unique" }
    return copy(
        asOf = asOf.truncatedTo(ChronoUnit.MICROS),
        snapshots = Collections.unmodifiableList(snapshots.sortedBy { it.symbol }.map { snapshot ->
            snapshot.copy(
                asOf = snapshot.asOf.truncatedTo(ChronoUnit.MICROS),
                sourceTimestamp = snapshot.sourceTimestamp.truncatedTo(ChronoUnit.MICROS),
                retrievedAt = snapshot.retrievedAt.truncatedTo(ChronoUnit.MICROS),
            )
        }),
    )
}
