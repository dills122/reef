package com.reef.platform.calcify.finance

/**
 * A capacity deployment: one run and session split into lanes. A lane is an independent ledger over a disjoint set of instruments (design option S1: shard by
 * instrument, cash held per lane), with its own input partition, store, decision log, hash chain and projection binding. Nothing is shared between lanes, so lanes
 * add throughput without any cross-lane coordination; a participant's cash is allocated to lanes by funding controls.
 *
 * [planId] is the opaque identity of the deployment (binding v3 will define and sign it); every lane's binding digest commits to it, to the lane index and to the
 * lane's configuration, so a store, log or projection built for one lane or plan is refused by every other.
 */
internal class CapacityPlan(
    val planId: String, val run: String, val session: String, val cashAsset: AssetId, val openingAccount: AccountId,
    val laneInstruments: List<Set<String>>,
    val maxObligations: Int, val maxCaptures: Int, val maxSettleAttempts: Int, val maxControls: Int,
    /** Instant profile: every captured execution is followed by its first settlement attempt, decided in the same record (a booked trade is two decisions). */
    val autoSettle: Boolean = true,
) {
    init {
        require(planId.isNotBlank() && run.isNotBlank() && session.isNotBlank()) { "plan identity" }
        require(laneInstruments.isNotEmpty() && laneInstruments.all { it.isNotEmpty() }) { "every lane needs at least one instrument" }
        require(laneInstruments.flatMap { it }.let { it.size == it.toSet().size }) { "an instrument belongs to exactly one lane" }
        require(laneInstruments.flatten().none { it == cashAsset.value }) { "an instrument cannot be the cash asset" }
    }

    val lanes: Int get() = laneInstruments.size
    private val laneByInstrument: Map<String, Int> = laneInstruments.withIndex().flatMap { (i, set) -> set.map { it to i } }.toMap()

    /** The lane an instrument trades in, or null for an instrument outside the plan. */
    fun laneOf(instrument: String): Int? = laneByInstrument[instrument]

    fun capacity(lane: Int): CapacityConfig {
        require(lane in 0 until lanes) { "lane $lane is outside the plan" }
        return CapacityConfig(null, cashAsset, laneInstruments[lane].map { AssetId(it) }.toSet(), openingAccount, maxObligations, maxCaptures, maxSettleAttempts, maxControls)
    }

    fun bindingDigest(lane: Int): String = Canon.sha(Canon.bytes {
        str("calcify-capacity-lane-binding-v0"); str(planId); str(run); str(session); int(lanes); int(lane); str(capacity(lane).digest())
    })

    fun spec(lane: Int): CapacityLaneSpec = CapacityLaneSpec(bindingDigest(lane), capacity(lane))
}

internal data class CapacityLaneSpec(val bindingDigest: String, val capacity: CapacityConfig)
