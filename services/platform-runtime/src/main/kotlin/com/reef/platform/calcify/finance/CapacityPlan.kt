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
    /** The most decisions one lane's projection will ever hold (migration 0016); the default covers a 5-minute slice at the contract rate with room to spare. Part of the lane binding. */
    val sequenceBound: Long = 8_000_000L,
) {
    init {
        require(planId.isNotBlank() && run.isNotBlank() && session.isNotBlank()) { "plan identity" }
        require(laneInstruments.isNotEmpty() && laneInstruments.all { it.isNotEmpty() }) { "every lane needs at least one instrument" }
        require(laneInstruments.flatMap { it }.let { it.size == it.toSet().size }) { "an instrument belongs to exactly one lane" }
        require(laneInstruments.flatten().none { it == cashAsset.value }) { "an instrument cannot be the cash asset" }
        require(sequenceBound in FinanceProjector.FINITE_SEQUENCE_BOUND..FinanceProjector.MAX_SEQUENCE_BOUND) { "sequence bound out of range" }
        capacity(0)      // the budgets are validated by the ledger configuration now, not at first use
    }

    val lanes: Int get() = laneInstruments.size

    /** Refuses a plan whose instrument-to-lane assignment is not the matching engine's own ([com.reef.platform.infrastructure.partition.PartitionLaneHash]). Resolved match contexts arrive on the venue source partition, so the lane an instrument trades in must be that partition. */
    fun requireEngineAligned() = laneInstruments.forEachIndexed { lane, set ->
        set.forEach { require(com.reef.platform.infrastructure.partition.PartitionLaneHash.laneFor(it, lanes) == lane) { "instrument $it does not route to lane $lane in the engine's partitioning" } }
    }
    private val laneByInstrument: Map<String, Int> = laneInstruments.withIndex().flatMap { (i, set) -> set.map { it to i } }.toMap()

    /** The lane an instrument trades in, or null for an instrument outside the plan. */
    fun laneOf(instrument: String): Int? = laneByInstrument[instrument]

    fun capacity(lane: Int): CapacityConfig {
        require(lane in 0 until lanes) { "lane $lane is outside the plan" }
        return CapacityConfig(null, cashAsset, laneInstruments[lane].map { AssetId(it) }.toSet(), openingAccount, maxObligations, maxCaptures, maxSettleAttempts, maxControls)
    }

    fun bindingDigest(lane: Int): String = Canon.sha(Canon.bytes {
        str("calcify-capacity-lane-binding-v0"); str(planId); str(run); str(session); int(lanes); int(lane); long(sequenceBound); str(capacity(lane).digest())
    })

    fun spec(lane: Int): CapacityLaneSpec = CapacityLaneSpec(bindingDigest(lane), capacity(lane))
}

internal data class CapacityLaneSpec(val bindingDigest: String, val capacity: CapacityConfig)

internal object CapacityRouting {
    const val EXPLICIT = "explicit"
    const val ENGINE_PARTITIONS = "partition-lane-hash-v1"

    /** The lane sets the matching engine's partitioning gives these instruments over [lanes] lanes. Every lane must receive at least one instrument. */
    fun engineLanes(instruments: Collection<String>, lanes: Int): List<Set<String>> =
        instruments.groupBy { com.reef.platform.infrastructure.partition.PartitionLaneHash.laneFor(it, lanes) }.let { byLane ->
            (0 until lanes).map { lane -> byLane[lane]?.toSet() ?: throw IllegalArgumentException("no instrument routes to lane $lane of $lanes") }
        }
}
