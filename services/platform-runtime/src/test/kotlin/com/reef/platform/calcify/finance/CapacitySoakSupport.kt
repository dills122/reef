package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import kotlin.random.Random

/*
 * Pure pieces of the paced soak harness ([CapacitySoakTest]); none of them needs Docker, so [CapacitySoakSupportTest] runs in the default test run.
 */

/**
 * A fixed schedule against a monotonic clock (System.nanoTime in the harness): trade k is due at `start + k / rate`. There is no accumulated bucket, so a sender that
 * falls behind does not "lose" its backlog and never bursts above what the schedule says it owes: [nextBatch] hands out what is overdue, capped by `maxBatch`.
 * [behindTrades] is how many trades are overdue right now, i.e. the producer's own lateness (as opposed to the lane's lag).
 */
internal class SchedulePacer(val ratePerSecond: Long, val startNanos: Long) {
    init { require(ratePerSecond > 0) { "rate must be positive" } }

    /** Trades whose scheduled time is at or before [nowNanos]: trade 0 is due at the start. */
    fun due(nowNanos: Long): Long { val elapsed = nowNanos - startNanos; return if (elapsed < 0) 0 else (elapsed.toDouble() * ratePerSecond / 1e9).toLong() + 1 }

    /** The time trade [k] is scheduled for. */
    fun scheduledAt(k: Long): Long = startNanos + (k.toDouble() * 1e9 / ratePerSecond).toLong()

    /** Overdue trades: scheduled by [nowNanos] but not yet sent. */
    fun behindTrades(sent: Long, nowNanos: Long): Long = (due(nowNanos) - sent).coerceAtLeast(0)

    /** How many trades to send now (zero when nothing is due). */
    fun nextBatch(sent: Long, nowNanos: Long, maxBatch: Int): Int = minOf(behindTrades(sent, nowNanos), maxBatch.toLong()).toInt()

    /** Nanoseconds until the next unsent trade is due (zero when it is overdue). */
    fun nanosUntilNext(sent: Long, nowNanos: Long): Long = (scheduledAt(sent) - nowNanos).coerceAtLeast(0)
}

/**
 * "Lag above [thresholdSeconds] for at least [sustainSeconds]": [observe] is fed one (time, lag in seconds of rate) pair per sample and answers true once every sample from
 * the first over-threshold one up to this one is over the threshold and at least [sustainSeconds] separate the two. One sample under the threshold resets it.
 */
internal class SustainedLagRule(val thresholdSeconds: Double, val sustainSeconds: Double) {
    private var since: Double? = null
    var breached: Boolean = false; private set

    fun observe(timeSeconds: Double, lagSeconds: Double): Boolean {
        if (lagSeconds <= thresholdSeconds) { since = null; return breached }
        val start = since ?: timeSeconds.also { since = it }
        if (timeSeconds - start >= sustainSeconds) breached = true
        return breached
    }

    /** Seconds the lag has been continuously over the threshold as of the last sample (zero when it is not). */
    fun overFor(timeSeconds: Double): Double = since?.let { timeSeconds - it } ?: 0.0
}

/** Which side of the harness is the limit, so a failing run names the right culprit. */
internal enum class SoakVerdict { OK, PRODUCER_BEHIND, LANE_BEHIND_BOOKING, LANE_BEHIND_PROJECTION }

internal object SoakVerdicts {
    /**
     * The producer is judged first: if the sender is late the numbers say nothing about the lane (the lane never saw the trades), so a producer breach wins over a lane breach.
     * Booking lag is trades sent but not yet decided; projection lag is trades decided but not yet in Postgres.
     */
    fun judge(producerBreached: Boolean, bookingBreached: Boolean, projectionBreached: Boolean): SoakVerdict = when {
        producerBreached -> SoakVerdict.PRODUCER_BEHIND
        bookingBreached -> SoakVerdict.LANE_BEHIND_BOOKING
        projectionBreached -> SoakVerdict.LANE_BEHIND_PROJECTION
        else -> SoakVerdict.OK
    }
}

/** Trades from decisions, mode-aware: a lane's decision log holds its funding decisions and then [decisionsPerTrade] per trade (a half-booked two-step trade does not count yet). */
internal object SoakAccounting {
    fun decisionsPerTrade(mode: DecisionMode): Long = if (mode == DecisionMode.CAPTURE_SETTLE) 1L else 2L
    fun trades(sequence: Long, fundingDecisions: Long, mode: DecisionMode): Long = ((sequence - fundingDecisions).coerceAtLeast(0)) / decisionsPerTrade(mode)

    /** Funding decisions of lane [lane]: one cash Fund per account (every lane is funded) and one security Fund per account for each instrument of the lane. */
    fun fundingDecisions(plan: CapacityPlan, lane: Int, accounts: Int): Long = accounts.toLong() * (1 + plan.laneInstruments[lane].size)
}

/** Opening balances sized from the planned total, so no account can run out of cash or shares during the whole soak (funding is done once, before the clock starts). */
internal data class SoakFunding(val cashPerAccountPerLane: Long, val sharesPerAccountPerInstrument: Long) {
    companion object {
        const val MAX_UNITS = 4L                // the generator's largest quantity
        const val MAX_PRICE_NANOS = 1_100L      // above the generator's largest price (1,049)

        /**
         * Cash: every trade could, in the worst case, be bought by one account at the top quantity and price, so the lane gets planned x MAX_UNITS x MAX_PRICE_NANOS (and not less than the
         * throughput test's 1e12). Shares: the busiest (account, instrument) pair is the hot instrument with probability [pMax] x planned / accounts trades as a seller; six times that mean at
         * the top quantity, plus a floor of 1,000,000 as in the throughput test. Both are far below Long.MAX_VALUE for any soak a droplet can hold.
         */
        fun sized(plannedTrades: Long, accounts: Int, instruments: Int, hotShare: Double): SoakFunding {
            val pMax = hotShare + (1.0 - hotShare) / instruments
            val cash = maxOf(1_000_000_000_000L, Math.multiplyExact(Math.multiplyExact(plannedTrades, MAX_UNITS), MAX_PRICE_NANOS))
            val shares = maxOf(1_000_000L, (6.0 * plannedTrades * pMax / accounts * MAX_UNITS).toLong() + 100_000L)
            return SoakFunding(cash, shares)
        }
    }
}

internal class SoakTrade(val index: Long, val lane: Int, val bytes: ByteArray)

/**
 * Trades produced one at a time and deterministically (the same seed gives the same byte stream), in the shape of the throughput test's pre-generated schedule: instrument hot with
 * probability [hotShare] (INS0), otherwise uniform; buyer and seller uniform and distinct; quantity 1 to 4, price 1,000 to 1,049 nanos. The `resolved` source is a MatchContextResolvedV1
 * per trade (synthetic: there is no engine behind it); the lifecycle source is one genuine capture envelope edited per trade with its content digest recomputed (more expensive per trade).
 * Not thread-safe: one generator thread.
 */
internal class SoakTradeGenerator(
    private val plan: CapacityPlan, private val instruments: List<String>, private val accounts: List<AccountId>, private val hotShare: Double, private val resolved: Boolean, seed: Int = 3,
) {
    private val rnd = Random(seed)
    private var index = 0L
    val generated: Long get() = index

    private class Lifecycle(val one: reef.contracts.calcify.v1.FiniteLifecycleCaptureV1, val readyIndex: Int)
    private val lifecycle: Lifecycle? by lazy {
        val base = FiniteLifecycleFixtures.sequence().completedRecordsList.single { it.source.sourceOffset == 2L }
        val ready = base.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val keep = base.membersList.withIndex().filter { (i, m) -> m.disposition.name != "LIFECYCLE_READY_EXECUTION" || i == ready }.map { it.value }
        val one = base.toBuilder().clearMembers().addAllMembers(keep).setMemberCount(keep.size).build()
        Lifecycle(one, one.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" })
    }

    fun next(): SoakTrade {
        val i = index++
        val ins = if (rnd.nextDouble() < hotShare) instruments[0] else instruments[rnd.nextInt(instruments.size)]
        val bi = rnd.nextInt(accounts.size); var si = rnd.nextInt(accounts.size); if (si == bi) si = (bi + 1) % accounts.size
        val units = 1L + rnd.nextInt(4); val nanos = 1_000L + rnd.nextInt(50)
        val lane = plan.laneOf(ins)!!
        return SoakTrade(i, lane, if (resolved) resolvedContext(i, ins, accounts[bi], accounts[si], units, nanos) else lifecycleEnvelope(i, ins, accounts[bi], accounts[si], units, nanos))
    }

    private fun resolvedContext(i: Long, ins: String, b: AccountId, s: AccountId, units: Long, nanos: Long): ByteArray {
        val c = reef.contracts.calcify.v1.MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId(plan.run)
        c.tradeBuilder.setRunId(plan.run).factBuilder.setExecutionId("exec-$i").setInstrumentId(ins).setBuyOrderId("b-$i").setSellOrderId("s-$i").quantityBuilder.setUnits(units.toString())
        c.tradeBuilder.factBuilder.priceBuilder.setNanos(nanos.toString()).setCurrency(plan.cashAsset.value)
        c.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$i").setVenueSessionId(plan.session).setInstrumentId(ins).setAccountId(b.value).setParticipantId("pb")
        c.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$i").setVenueSessionId(plan.session).setInstrumentId(ins).setAccountId(s.value).setParticipantId("ps")
        return c.build().toByteArray()
    }

    private fun lifecycleEnvelope(i: Long, ins: String, b: AccountId, s: AccountId, units: Long, nanos: Long): ByteArray {
        val l = lifecycle!!
        val edited = l.one.toBuilder().also { bld ->
            val m = bld.getMembersBuilder(l.readyIndex)
            m.tradeBuilder.factBuilder.setExecutionId("exec-$i").setInstrumentId(ins).quantityBuilder.setUnits(units.toString())
            m.tradeBuilder.factBuilder.priceBuilder.setNanos(nanos.toString())
            m.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId(b.value); m.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setAccountId(s.value)
        }.build()
        return edited.toBuilder().setContentDigest(FiniteLifecycleContract.digest(edited)).build().toByteArray()
    }
}

/** One row of the time series. Every number is cumulative since the producer's first trade unless its name says otherwise; blank fields are values a source could not give. */
internal data class SoakSample(
    val phase: String, val seconds: Double, val sent: Long, val acked: Long, val booked: Long, val projected: Long, val producerBehind: Long,
    val instSent: Double, val instBooked: Double, val instProjected: Double, val cumSent: Double, val cumBooked: Double, val cumProjected: Double,
    val heapUsedMib: Long, val gcCountDelta: Long, val gcMsDelta: Long, val processCpuPct: Double, val streamsCpuPct: Double, val projectionCpuPct: Double, val kafkaClientsCpuPct: Double, val harnessCpuPct: Double,
    val rpTotalKib: Long?, val rpCaptureKib: Long?, val rpDecisionsKib: Long?, val rpChangelogKib: Long?, val pgDirKib: Long?, val pgWalKib: Long?, val pgDatabaseBytes: Long?, val stateDirKib: Long?,
    val rpGrowthKibS: Double?, val pgGrowthKibS: Double?, val hostDiskWriteMibS: Double?, val healthy: Boolean,
) {
    val lagSentBooked: Long get() = (sent - booked).coerceAtLeast(0)
    val lagBookedProjected: Long get() = (booked - projected).coerceAtLeast(0)

    fun csvRow(targetRate: Long): String {
        fun d(v: Double?) = if (v == null) "" else "%.1f".format(java.util.Locale.ROOT, v)
        fun l(v: Long?) = v?.toString() ?: ""
        val rate = targetRate.toDouble()
        return listOf(
            phase, "%.1f".format(java.util.Locale.ROOT, seconds), sent, acked, booked, projected, producerBehind, lagSentBooked, lagBookedProjected,
            d(producerBehind / rate), d(lagSentBooked / rate), d(lagBookedProjected / rate),
            d(instSent), d(instBooked), d(instProjected), d(cumSent), d(cumBooked), d(cumProjected),
            heapUsedMib, gcCountDelta, gcMsDelta, d(processCpuPct), d(streamsCpuPct), d(projectionCpuPct), d(kafkaClientsCpuPct), d(harnessCpuPct),
            l(rpTotalKib), l(rpCaptureKib), l(rpDecisionsKib), l(rpChangelogKib), l(pgDirKib), l(pgWalKib), l(pgDatabaseBytes), l(stateDirKib),
            d(rpGrowthKibS), d(pgGrowthKibS), d(hostDiskWriteMibS), if (healthy) 1 else 0,
        ).joinToString(",")
    }

    companion object {
        val HEADER = listOf(
            "phase", "seconds", "sent", "acked", "booked", "projected", "producer_behind", "lag_sent_booked", "lag_booked_projected",
            "producer_behind_s", "lag_sent_booked_s", "lag_booked_projected_s",
            "inst_sent_per_s", "inst_booked_per_s", "inst_projected_per_s", "cum_sent_per_s", "cum_booked_per_s", "cum_projected_per_s",
            "heap_used_mib", "gc_count_delta", "gc_ms_delta", "process_cpu_pct", "streams_cpu_pct", "projection_cpu_pct", "kafka_clients_cpu_pct", "harness_cpu_pct",
            "rp_total_kib", "rp_capture_kib", "rp_decisions_kib", "rp_changelog_kib", "pg_dir_kib", "pg_wal_kib", "pg_database_bytes", "state_dir_kib",
            "rp_growth_kib_s", "pg_growth_kib_s", "host_disk_write_mib_s", "healthy",
        ).joinToString(",")
    }
}
