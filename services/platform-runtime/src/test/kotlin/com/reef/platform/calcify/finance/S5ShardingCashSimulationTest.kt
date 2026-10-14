package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sharding option S1 (docs/work/CALCIFY_PROCESSOR_PATH_AND_SHARDING_OPTIONS_2026-10-14.md): shard by instrument, give each participant a cash sub-account per shard.
 * This simulation asks how often a trade finds the buyer's cash in the wrong shard, i.e. how many cross-shard cash moves a rebalancing rule would have to issue per 1,000 trades.
 * Pure JVM, no Kafka or Docker; research only (`-PresearchTests`). The workload is an ILLUSTRATIVE assumption, not the arena's: 100 participants, 64 instruments, one price,
 * quantities 1 to 10, uniformly random counterparties, instrument popularity uniform or Zipf, seller holdings per instrument, one settled trade at a time (settlement is immediate here).
 * It is ONE evolving session per configuration (balances carry over from trade to trade, as in a real session), not independent draws; one seed. It does not model credit, reservation, open orders or time; it counts cash moves only. Cash that cannot be found in any shard is a true shortfall, the same in every layout.
 */
@org.junit.jupiter.api.Tag("research")
class S5ShardingCashSimulationTest {
    private class Result(val shards: Int, val moves: Long, val tradesNeedingMove: Long, val trades: Long, val shortfalls: Long, val skippedNoSecurity: Long)

    private fun simulate(participants: Int, instruments: Int, shards: Int, trades: Int, openingCash: Long, zipf: Boolean, refill: Boolean, seed: Long): Result {
        val rnd = Random(seed); val price = 100L
        val cash = Array(participants) { LongArray(shards) { k -> openingCash / shards + if (k < openingCash % shards) 1 else 0 } }   // the remainder goes to the first shards, so the total is exactly openingCash
        val sec = Array(participants) { LongArray(instruments) { 1000L } }
        val weights = DoubleArray(instruments) { if (zipf) 1.0 / (it + 1) else 1.0 }; val cum = DoubleArray(instruments); var acc = 0.0
        for (i in 0 until instruments) { acc += weights[i]; cum[i] = acc }
        fun pickInstrument(): Int { val x = rnd.nextDouble() * acc; var lo = 0; var hi = instruments - 1; while (lo < hi) { val mid = (lo + hi) ushr 1; if (cum[mid] < x) lo = mid + 1 else hi = mid }; return lo }
        var moves = 0L; var needing = 0L; var done = 0L; var shortfalls = 0L; var noSec = 0L
        repeat(trades) {
            val instr = pickInstrument(); val shard = instr % shards
            val b = rnd.nextInt(participants); var s = rnd.nextInt(participants); if (s == b) s = (s + 1) % participants
            val q = 1L + rnd.nextInt(10); val cost = q * price
            if (sec[s][instr] < q) { noSec++; return@repeat }
            if (cash[b][shard] < cost) {
                val total = cash[b].sum()
                if (total < cost) { shortfalls++; return@repeat }
                // Rebalance: pull from the shards holding the most cash until the shard can pay (and, with refill, until it again holds its opening share).
                var need = (if (refill) maxOf(cost, openingCash / shards) else cost) - cash[b][shard]; var movedHere = false
                while (need > 0) {
                    var donor = -1; var best = 0L
                    for (k in 0 until shards) if (k != shard && cash[b][k] > best) { best = cash[b][k]; donor = k }
                    if (donor < 0) break
                    val take = minOf(best, need); cash[b][donor] -= take; cash[b][shard] += take; need -= take; moves++; movedHere = true
                }
                if (movedHere) needing++
            }
            cash[b][shard] -= cost; cash[s][shard] += cost; sec[s][instr] -= q; sec[b][instr] += q; done++
        }
        // Conservation: nothing is created or lost.
        assertEquals(participants.toLong() * openingCash, cash.sumOf { it.sum() }, "cash is conserved")
        return Result(shards, moves, needing, done, shortfalls, noSec)
    }

    @Test fun cashMovesAcrossShardsPerThousandTrades() {
        val trades = (System.getenv("S5_TRADES") ?: "2000000").toInt()
        val log = StringBuilder("\nS5 cash sub-account rebalancing (illustrative workload: 100 participants, 64 instruments, price 100, quantity 1 to 10, $trades trade attempts)\n")
        log.append("%-8s %-8s %-9s %-8s %12s %16s %14s %12s\n".format("instr", "opening", "refill", "shards", "moves/1000", "trades needing %", "shortfall %", "no-security %"))
        for (zipf in listOf(false, true)) for (openingCash in listOf(1_000_000L, 200_000L)) for (refill in listOf(false, true)) for (shards in listOf(1, 2, 8, 64)) {
            val r = simulate(100, 64, shards, trades, openingCash, zipf, refill, 17)
            val executed = r.trades.toDouble()
            log.append("%-8s %-8d %-9s %-8d %12.1f %16.2f %14.3f %12.2f\n".format(if (zipf) "zipf" else "uniform", openingCash, refill, shards, r.moves * 1000.0 / executed, r.tradesNeedingMove * 100.0 / executed, r.shortfalls * 100.0 / trades, r.skippedNoSecurity * 100.0 / trades))
            if (shards == 1) assertEquals(0L, r.moves, "one shard never moves cash")
            S0Metrics.record("shardingCash.${if (zipf) "zipf" else "uniform"}.cash$openingCash.refill$refill.shards$shards.movesPer1000", Math.round(r.moves * 1000.0 / executed))
        }
        // Layout independence of true shortfalls: the same total cash is available to the buyer in every layout, so shortfalls may differ only through different trade paths; sanity bound.
        println(log); Files.writeString(S0Metrics.file("s5-sharding-cash.txt"), log)
        assertTrue(log.isNotEmpty())
    }
}
