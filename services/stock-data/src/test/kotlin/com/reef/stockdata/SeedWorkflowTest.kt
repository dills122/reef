package com.reef.stockdata

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.temporal.ChronoUnit

class SeedWorkflowTest {

    @Test
    fun `batch children must belong to their seed and have unique symbols`() {
        val repository = InMemorySeedSnapshotRepository()
        val candidate = FakeStockDataProvider().getSeedSnapshots("invalid-seed", listOf("AAPL"), Instant.EPOCH)
        val invalidBatches = listOf(
            candidate.copy(snapshots = emptyList()),
            candidate.copy(snapshots = candidate.snapshots + candidate.snapshots),
            candidate.copy(snapshots = candidate.snapshots.map { it.copy(gameSeedId = "other-seed") }),
        )
        for (invalid in invalidBatches) {
            assertFailsWith<IllegalArgumentException> { repository.createOrExisting(invalid) }
            assertEquals(null, repository.find(candidate.gameSeedId))
            assertEquals(null, repository.find("other-seed"))
        }
    }


    @Test
    fun `published in-memory snapshots cannot be changed through caller list aliases`() {
        val repository = InMemorySeedSnapshotRepository()
        val candidate = FakeStockDataProvider().getSeedSnapshots("alias-seed", listOf("AAPL"), Instant.EPOCH)
        val mutableSnapshots = candidate.snapshots.toMutableList()
        val winner = repository.createOrExisting(candidate.copy(snapshots = mutableSnapshots))
        mutableSnapshots.clear()
        assertEquals(candidate, repository.find(candidate.gameSeedId))
        assertFailsWith<UnsupportedOperationException> {
            (winner.snapshots as MutableList<StockSeedSnapshot>).clear()
        }
        assertEquals(candidate, repository.find(candidate.gameSeedId))
    }

    @Test
    fun `in-memory canonical timestamps use PostgreSQL microsecond precision`() {
        val repository = InMemorySeedSnapshotRepository()
        val asOf = Instant.parse("2026-07-08T15:00:00.123456789Z")
        val candidate = FakeStockDataProvider().getSeedSnapshots("precision-seed", listOf("AAPL"), asOf)
        val winner = repository.createOrExisting(candidate)
        assertEquals(asOf.truncatedTo(ChronoUnit.MICROS), winner.asOf)
        assertEquals(asOf.truncatedTo(ChronoUnit.MICROS), winner.snapshots.single().sourceTimestamp)
        assertEquals(winner, repository.find(candidate.gameSeedId))
    }


    @Test
    fun `concurrent seed requests and replay return one canonical winner`() {
        assertConcurrentSeedWinner(InMemorySeedSnapshotRepository())
    }


    @Test
    fun `second seed request for the same gameSeedId does not call the provider again`() {
        val provider = FakeStockDataProvider()
        val repository = InMemorySeedSnapshotRepository()
        val workflow = SeedWorkflow(provider, repository)
        val asOf = Instant.parse("2026-07-08T15:00:00Z")

        val first = workflow.seed("game-1", listOf("AAPL", "MSFT"), asOf)
        val second = workflow.seed("game-1", listOf("AAPL", "MSFT"), asOf)

        assertEquals(1, provider.callCount)
        assertEquals(first.batchSeedHash, second.batchSeedHash)
        assertEquals(first.snapshots.map { it.price }, second.snapshots.map { it.price })
    }

    @Test
    fun `replay reads persisted snapshots even if asked with different symbols`() {
        val provider = FakeStockDataProvider()
        val repository = InMemorySeedSnapshotRepository()
        val workflow = SeedWorkflow(provider, repository)
        val asOf = Instant.parse("2026-07-08T15:00:00Z")

        workflow.seed("game-1", listOf("AAPL"), asOf)
        // Same gameSeedId, different requested symbols: replay must not re-hit the provider.
        val replay = workflow.seed("game-1", listOf("MSFT", "GOOG"), asOf)

        assertEquals(1, provider.callCount)
        assertEquals(listOf("AAPL"), replay.snapshots.map { it.symbol })
    }

    @Test
    fun `unresolved symbol fails the whole batch without persisting anything`() {
        val provider = FakeStockDataProvider(unsupportedSymbols = setOf("ZZZZ"))
        val repository = InMemorySeedSnapshotRepository()
        val workflow = SeedWorkflow(provider, repository)

        assertFailsWith<StockDataSeedException> {
            workflow.seed("game-1", listOf("AAPL", "ZZZZ"), Instant.now())
        }

        assertEquals(null, repository.find("game-1"))
    }
}
