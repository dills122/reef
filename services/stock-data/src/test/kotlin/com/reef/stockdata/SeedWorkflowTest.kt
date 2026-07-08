package com.reef.stockdata

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeedWorkflowTest {

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
