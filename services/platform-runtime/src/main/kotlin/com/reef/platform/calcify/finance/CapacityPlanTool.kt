package com.reef.platform.calcify.finance

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Writes a `calcify-capacity-plan-v1` file for a run on the platform's own routing: the lane sets are what [CapacityRouting.engineLanes] gives the instruments, so the
 * plan always matches the engine's partitions. Used by the full-chain harness (`scripts/dev/calcify-fullchain-soak.mjs`) and by operators:
 *   java -cp '<lib jars>' com.reef.platform.calcify.finance.CapacityPlanTool --run R --session S --lanes 4 --instruments A,B,C,... --expected-trades 9000000 [--mode capture-settle-v1]
 *       [--capture-topic T] [--control-topic T] [--decisions-topic T] [--application-id ID]
 * The bounds are per lane and sized for all [Spec.expectedTrades] on one lane (a skewed instrument mix may put most of them there) with 25% headroom, so they are limits
 * that stay out of the way, not a forecast. The result goes to standard output.
 */
internal object CapacityPlanTool {
    data class Spec(
        val run: String, val session: String, val lanes: Int, val instruments: List<String>, val expectedTrades: Long, val decisionMode: DecisionMode = DecisionMode.TWO_STEP,
        val applicationId: String = "capacity-fullchain", val captureTopic: String = "REEF_MATCH_CONTEXT_RESOLVED_V1", val controlTopic: String = "CAPACITY_CONTROL", val decisionsTopic: String = "CAPACITY_DECISIONS",
    )

    fun plan(spec: Spec): String {
        require(spec.lanes in 1..256) { "lanes must be 1 to 256" }
        require(spec.expectedTrades in 1..1_000_000_000L) { "expected trades must be 1 to 1,000,000,000" }
        val laneSets = CapacityRouting.engineLanes(spec.run, spec.session, spec.instruments, spec.lanes)
        val trades = spec.expectedTrades + spec.expectedTrades / 4 + 1_000
        val perTrade = if (spec.decisionMode == DecisionMode.CAPTURE_SETTLE) 1L else 2L
        val limit = Int.MAX_VALUE.toLong()
        val mapper = ObjectMapper()
        val root = mapper.createObjectNode()
        root.put("schema", "calcify-capacity-plan-v1"); root.put("planId", spec.applicationId); root.put("run", spec.run); root.put("session", spec.session)
        root.put("cashAsset", "USD"); root.put("openingAccount", "opening"); root.put("laneRouting", CapacityRouting.ENGINE_PARTITIONS)
        val lanes = root.putArray("laneInstruments"); laneSets.forEach { set -> lanes.addArray().also { a -> set.sorted().forEach(a::add) } }
        root.put("maxObligations", minOf(trades, limit).toString()); root.put("maxCaptures", minOf(trades, limit).toString())
        root.put("maxSettleAttempts", minOf(trades, limit).toString()); root.put("maxControls", "100000")
        root.put("sequenceBound", minOf(trades * perTrade + 200_000, FinanceProjector.MAX_SEQUENCE_BOUND).toString())
        root.put("autoSettle", true)
        root.put("applicationId", spec.applicationId)
        root.putObject("topics").also { it.put("capture", spec.captureTopic); it.put("control", spec.controlTopic); it.put("decisions", spec.decisionsTopic) }
        root.putObject("captureSource").also { it.put("kind", "resolved-context-v1"); it.put("policyVersion", CapacityResolvedContextDecoder.POLICY_VERSION.toString()) }
        if (spec.decisionMode != DecisionMode.TWO_STEP) root.put("decisionMode", spec.decisionMode.wire)
        return mapper.writeValueAsString(root)
    }

    fun parseArgs(args: List<String>): Spec {
        val values = HashMap<String, String>()
        var i = 0
        while (i < args.size) { require(args[i].startsWith("--") && i + 1 < args.size) { "expected --name value pairs, got ${args[i]}" }; values[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
        fun need(k: String) = values[k]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing --$k")
        val known = setOf("run", "session", "lanes", "instruments", "expected-trades", "mode", "capture-topic", "control-topic", "decisions-topic", "application-id")
        require(values.keys.all { it in known }) { "unknown option ${values.keys.first { it !in known }}" }
        return Spec(need("run"), need("session"), need("lanes").toInt(), need("instruments").split(",").map { it.trim() }, need("expected-trades").toLong(),
            values["mode"]?.let { DecisionMode.ofWire(it) } ?: DecisionMode.TWO_STEP, values["application-id"] ?: "capacity-fullchain",
            values["capture-topic"] ?: "REEF_MATCH_CONTEXT_RESOLVED_V1", values["control-topic"] ?: "CAPACITY_CONTROL", values["decisions-topic"] ?: "CAPACITY_DECISIONS")
    }

    @JvmStatic fun main(args: Array<String>) {
        try { println(plan(parseArgs(args.toList()))) } catch (e: Exception) { System.err.println("plan failed: ${e.message}"); kotlin.system.exitProcess(2) }
    }
}
