package com.reef.platform.calcify.finance

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/**
 * Research: decisions per second of the product [CapacityLedger] (synchronous commitment, every decision carries C(S')) as the state grows, and the heap per
 * entity. In-memory only, one thread. Run with `-PresearchTests`; PRODUCT_SIZES overrides the entity targets (default 100000,1000000).
 */
@Tag("research")
class CapacityLedgerScaleSpikeTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL")

    private fun heapMb(): Long { repeat(3) { System.gc() }; return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024) }

    @Test fun decisionsPerSecondAsTheStateGrows() {
        val sizes = (System.getenv("PRODUCT_SIZES") ?: "100000,1000000").split(",").map { it.trim().toInt() }
        val accountCount = 2_000
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }.toSet()
        val log = StringBuilder("\nCapacityLedger (product code), synchronous commitment, $accountCount accounts, one thread, in memory\n")
        val heap0 = heapMb()
        val cap = CapacityLedger(CapacityConfig(accounts, usd, setOf(aapl), AccountId("opening"), 10_000_000, 10_000_000, 20_000_000, 100_000))
        for (a in accounts) { cap.decide(FinanceInput.Fund(ActionKey("n", "fu", a.value), Authority.OPERATOR, a, usd, Long.MAX_VALUE / 4)); cap.decide(FinanceInput.Fund(ActionKey("n", "fa", a.value), Authority.OPERATOR, a, aapl, Long.MAX_VALUE / 4)) }
        val rnd = Random(1); var next = 0
        fun trade(t: Int) {
            val key = ExecutionKey("run", "session", "AAPL", "e$t"); val b = rnd.nextInt(accountCount); var s = rnd.nextInt(accountCount - 1); if (s >= b) s++
            cap.decide(FinanceInput.Capture(ActionKey("n", "cap", "c$t"), key, AccountId("acct-$b"), AccountId("acct-$s"), 1L + rnd.nextInt(5), 1_000L + rnd.nextInt(100)))
            cap.decide(FinanceInput.Settle(ActionKey("n", "rep", "s$t"), Authority.OPERATOR, key, 1))
        }
        for (target in sizes) {
            while (cap.entityCount < target) trade(next++)
            val measured = 20_000; val t0 = System.nanoTime(); val before = cap.sequence
            repeat(measured / 2) { trade(next++) }
            val secs = (System.nanoTime() - t0) / 1e9; val decisions = cap.sequence - before
            val heap = heapMb() - heap0
            log.append("  %,d entities: %,.0f decisions/s over %,d decisions; heap about %d bytes per entity\n".format(cap.entityCount, decisions / secs, decisions, heap * 1024 * 1024 / cap.entityCount))
        }
        println(log); Files.writeString(S0Metrics.file("capacity-ledger-scale.txt"), log)
    }
}
