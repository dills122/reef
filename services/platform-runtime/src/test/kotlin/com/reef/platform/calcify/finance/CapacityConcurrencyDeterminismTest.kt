package com.reef.platform.calcify.finance

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lane's hot path reuses per-thread scratch (digest, canonical writer, protobuf buffer) and a flat trie per ledger. Independent ledgers on different threads must
 * produce exactly the decisions, checksums and encoded bytes a single thread produces: a scratch shared between threads would corrupt at least one of them.
 */
class CapacityConcurrencyDeterminismTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val opening = AccountId("opening")
    private val accounts = (0 until 40).map { AccountId("acct-$it") }

    /** One ledger driven through funding and 600 captured-and-settled trades; returns every decision's checksum and encoded record, hashed, in order. */
    private fun script(): List<String> {
        val ledger = CapacityLedger(CapacityConfig(null, usd, setOf(aapl), opening, 2000, 2000, 4000, 400))
        val out = ArrayList<String>()
        fun take(i: FinanceInput) { ledger.decide(i).decision?.let { d -> out += d.checksum + ":" + Canon.sha(FinanceWire.encode(d)) + ":" + Canon.sha(FinanceWire.withInput(FinanceWire.encode(d), i)) } }
        var n = 0
        for (a in accounts) { take(FinanceInput.Fund(ActionKey("n", "f", "u${n++}"), Authority.OPERATOR, a, usd, 1_000_000_000L)); take(FinanceInput.Fund(ActionKey("n", "f", "s${n++}"), Authority.OPERATOR, a, aapl, 100_000L)) }
        for (t in 0 until 600) {
            val k = ExecutionKey("run", "session", "AAPL", "exec-$t")
            take(FinanceInput.Capture(ActionKey("n", "c", "c$t"), k, accounts[t % 40], accounts[(t * 7 + 1) % 40].takeIf { it != accounts[t % 40] } ?: accounts[(t + 1) % 40], 1L + t % 5, 100L + t % 17))
            take(FinanceInput.Settle(ActionKey("n", "s", "s$t"), Authority.OPERATOR, k, 1))
        }
        return out
    }

    @Test fun ledgersOnManyThreadsProduceExactlyWhatOneThreadProduces() {
        val expected = script()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val runs = (0 until threads * 3).map { pool.submit(Callable { script() }) }
            runs.forEachIndexed { i, run -> assertEquals(expected, run.get(), "concurrent run $i differs from the single-thread run") }
        } finally { pool.shutdownNow() }
    }
}
