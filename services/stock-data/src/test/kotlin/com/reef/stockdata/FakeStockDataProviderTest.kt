package com.reef.stockdata

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeStockDataProviderTest {

    @Test
    fun `same symbol yields same deterministic price across calls`() {
        val provider = FakeStockDataProvider()
        val asOf = Instant.parse("2026-07-08T15:00:00Z")

        val first = provider.getSeedSnapshots("seed-1", listOf("AAPL"), asOf).snapshots.single()
        val second = provider.getSeedSnapshots("seed-2", listOf("AAPL"), asOf).snapshots.single()

        assertEquals(first.price, second.price)
    }

    @Test
    fun `different symbols yield different prices`() {
        val provider = FakeStockDataProvider()
        val asOf = Instant.parse("2026-07-08T15:00:00Z")

        val batch = provider.getSeedSnapshots("seed-1", listOf("AAPL", "MSFT"), asOf)

        assertTrue(batch.snapshots[0].price != batch.snapshots[1].price)
    }

    @Test
    fun `unsupported symbol fails closed with symbol_not_supported`() {
        val provider = FakeStockDataProvider(unsupportedSymbols = setOf("ZZZZ"))
        val asOf = Instant.now()

        val ex = assertFailsWith<StockDataSeedException> {
            provider.getSeedSnapshots("seed-1", listOf("AAPL", "ZZZZ"), asOf)
        }
        assertEquals(FailureCategory.SYMBOL_NOT_SUPPORTED, ex.category)
        assertEquals("ZZZZ", ex.symbol)
    }

    @Test
    fun `call count increments once per batch request`() {
        val provider = FakeStockDataProvider()
        provider.getSeedSnapshots("seed-1", listOf("AAPL", "MSFT"), Instant.now())
        provider.getSeedSnapshots("seed-2", listOf("AAPL"), Instant.now())

        assertEquals(2, provider.callCount)
    }
}
