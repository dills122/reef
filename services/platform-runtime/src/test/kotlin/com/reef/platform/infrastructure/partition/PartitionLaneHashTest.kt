package com.reef.platform.infrastructure.partition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartitionLaneHashTest {
    @Test
    fun spreadsBenchmarkSymbolsAcrossMostLanes() {
        val symbols = listOf(
            "AAPL", "MSFT", "NVDA", "AMZN",
            "META", "GOOGL", "TSLA", "JPM",
            "XOM", "UNH", "AVGO", "AMD",
            "NFLX", "ORCL", "COST", "CRM"
        )

        val lanes = symbols.groupBy { symbol -> PartitionLaneHash.laneFor(symbol, 16) }

        assertTrue(lanes.size >= 14, "expected at least 14 active lanes, got $lanes")
        assertTrue(lanes.values.all { it.size <= 2 }, "expected no lane to carry more than two symbols, got $lanes")
    }

    @Test
    fun keepsSameKeyOnSameLane() {
        assertEquals(
            PartitionLaneHash.laneFor("AAPL", 16),
            PartitionLaneHash.laneFor("AAPL", 16)
        )
    }
}
