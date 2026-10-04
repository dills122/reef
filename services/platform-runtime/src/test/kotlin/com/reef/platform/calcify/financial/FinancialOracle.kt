package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.security.MessageDigest
import java.util.HexFormat

/** Independent accounting reference. No calls to kernel economic, validation or replay functions. */
internal class FinancialOracle(genesisBalances: JsonNode, private val policy: JsonNode) {
    private val json = ObjectMapper()
    private val max = BigInteger.valueOf(Long.MAX_VALUE)
    private val min = BigInteger.valueOf(Long.MIN_VALUE)
    private val money = linkedMapOf<String, BigInteger>()
    private val executions = linkedMapOf<String, JsonNode>()
    private val obligations = linkedMapOf<String, MutableMap<String, String>>()
    private val attempts = linkedMapOf<String, String>()
    private val exceptions = linkedMapOf<String, String>()
    private val workflows = linkedMapOf<String, String>()
    private val instructions = linkedMapOf<String, String>()
    private val dedup = linkedMapOf<String, Map<String, Any>>()
    private val effects = linkedMapOf<String, List<Map<String, String>>>()
    private val versions = linkedMapOf<String, Long>()
    private val due = linkedMapOf<String, Map<String, Any>>()
    private var activePolicy = policy["policy"].asText()
    private var tick = BigInteger.ZERO
    private var continuation: Map<String, String>? = null
    private var seq = 0L
    private var decision: String? = null
    private var semanticDigest = sha("GENESIS")
    private val numeric = setOf("quantity", "priceNanos", "dueTick", "attempt", "amount", "tick")
    init {
        listOf("buyerCash", "sellerCash", "buyerShares", "sellerShares").forEach { money[it] = BigInteger(genesisBalances[it].asText()) }
        money["openingCash"] = -(money.getValue("buyerCash") + money.getValue("sellerCash"))
        money["openingShares"] = -(money.getValue("buyerShares") + money.getValue("sellerShares"))
        money.keys.forEach { versions[it] = 0L }
    }

    fun execute(input: JsonNode): JsonNode {
        val previousBusiness = businessView()
        val key = json.writeValueAsString(listOf("namespace", "domain", "actionId").map { input.path(it) })
        val payload = input.path("payload")
        var digest = ""
        var normalized: JsonNode = payload
        val invalidNormalization = runCatching {
            require(payload.isObject)
            val pairs = json.createArrayNode()
            val converted = json.createObjectNode()
            payload.fieldNames().asSequence().sorted().forEach { name ->
                val value = payload[name]
                val normalizedValue = if (name in numeric) {
                    require(value.isTextual)
                    json.nodeFactory.textNode(integer(value.asText()).toString())
                } else value
                converted.set<JsonNode>(name, normalizedValue)
                pairs.add(json.createArrayNode().add(name).add(normalizedValue))
            }
            digest = sha(json.writeValueAsString(listOf(input.path("kind").asText().uppercase(), input.get("requestedPolicy"), pairs)))
            normalized = converted
        }.isFailure
        if (continuation != null && input.path("kind").asText().uppercase() != "CONTINUE") return projection("STAGED", input, 0, activePolicy)
        if (invalidNormalization) digest = sha(canonical(input))
        val prior = dedup[key]
        if (prior != null && prior["digest"] == digest) {
            return projection("PRIOR_RESULT", input, 0, (prior["context"] as Map<*, *>)["policy"].toString()).also {
                it.put("originalDisposition", input.path("actionId").asText()); it.put("noNewAttempt", true)
            }
        }
        val conflictKey = frame(key, "conflict", digest)
        val priorConflict = if (prior != null) dedup[conflictKey] else null
        if (priorConflict != null) {
            return projection("PRIOR_RESULT", input, 0, (priorConflict["context"] as Map<*, *>)["policy"].toString()).also {
                it.put("originalDisposition", input.path("actionId").asText()); it.put("noNewAttempt", true)
            }
        }
        val selected = input.get("requestedPolicy")?.takeUnless { it.isNull }?.asText() ?: activePolicy
        val context = policy.fields().asSequence().associate { it.key to it.value.asText() }.toMutableMap()
        context["policy"] = selected; context["logicalTick"] = tick.toString(); context["domain"] = input.path("domain").asText()
        var disposition = "INVALID_INPUT"
        var legs: List<Map<String, String>> = emptyList()
        if (prior != null) disposition = "ACTION_CONFLICT"
        else if (invalidNormalization || listOf("namespace", "domain", "actionId").any { !input.path(it).isTextual || input.path(it).asText().isEmpty() }) disposition = "INVALID_INPUT"
        else if (selected !in setOf("gross-p1", "gross-p2")) disposition = "MISSING_POLICY"
        else if (!invalidNormalization) {
            when (input.path("kind").asText().uppercase()) {
                "CAPTURE" -> {
                    val required = listOf("executionId", "runId", "venueSessionId", "instrumentId", "buyOrderId", "sellOrderId", "buyerAccount", "sellerAccount", "cashAsset", "securityAsset", "quantity", "priceNanos", "dueTick")
                    if (required.all { normalized.hasNonNull(it) && normalized[it].isTextual && normalized[it].asText().isNotEmpty() } &&
                        normalized["buyerAccount"].asText() == "buyer" && normalized["sellerAccount"].asText() == "seller" &&
                        normalized["cashAsset"].asText() == "USD_NANO" && normalized["securityAsset"].asText() == "ACME_SHARE") {
                        val quantity = BigInteger(normalized["quantity"].asText())
                        val price = BigInteger(normalized["priceNanos"].asText())
                        val dueTick = BigInteger(normalized["dueTick"].asText())
                        val value = quantity * price
                        // Synthetic gross-DvP contract accepts positive quantity and nonnegative price, including zero cash value.
                        if (quantity.signum() > 0 && price.signum() >= 0 && dueTick.signum() >= 0) {
                            val executionKey = execKey(normalized)
                            val previous = executions[executionKey]
                            disposition = when {
                                quantity > max || value > max || dueTick > max -> "AMOUNT_OVERFLOW"
                                previous != null && previous != normalized -> "EXECUTION_CONFLICT"
                                previous != null -> "EXECUTION_ALREADY_CAPTURED"
                                else -> {
                                    executions[executionKey] = normalized.deepCopy()
                                    obligations[executionKey] = linkedMapOf("status" to "PENDING", "cashResidual" to value.toString(), "shareResidual" to quantity.toString())
                                    workflows[executionKey] = "PENDING"; instructions[executionKey] = "READY"
                                    versions[executionKey] = 1L
                                    due[executionKey] = mapOf("tick" to dueTick.toString(), "priority" to 0, "workId" to normalized["executionId"].asText())
                                    "CAPTURED"
                                }
                            }
                        }
                    }
                }
                "SETTLE", "CONTINUE" -> {
                    val isContinue = input.path("kind").asText().uppercase() == "CONTINUE"
                    val locator = normalized.get(if (isContinue) "workId" else "executionId")
                    val scopeFields = listOf("runId", "venueSessionId", "instrumentId")
                    val scoped = scopeFields.all { normalized.has(it) }
                    val validLocator = locator != null && locator.isTextual && locator.asText().isNotEmpty()
                    val validScope = !scoped || scopeFields.all { normalized[it].isTextual && normalized[it].asText().isNotEmpty() }
                    // Partial scope does not narrow identity; only complete tuples select scoped execution.
                    val matches = if (!validLocator || !validScope) emptyList() else executions.entries.filter { (_, execution) ->
                        execution["executionId"] == locator && (!scoped || scopeFields.all { normalized[it] == execution[it] })
                    }
                    if (matches.size == 1) {
                        val ekey = matches.single().key
                        val ordinal = if (isContinue) "1" else normalized["attempt"]?.asText()
                        val queue = eligible()
                        val clockAction = normalized.get("clockAction")
                        val allowed = if (isContinue) clockAction != null && clockAction.isTextual && clockAction.asText().isNotEmpty() && continuation?.get("clockAction") == clockAction.asText() && queue.firstOrNull() == ekey else continuation == null
                        if (ordinal != null && BigInteger(ordinal).signum() > 0 && BigInteger(ordinal) > max) disposition = "AMOUNT_OVERFLOW"
                        else if (ordinal != null && BigInteger(ordinal).signum() > 0 && allowed) {
                            val akey = json.writeValueAsString(json.readTree(ekey).map { it.asText() } + ordinal)
                            val o = obligations.getValue(ekey)
                            disposition = when {
                                o["status"] == "SETTLED" -> "ALREADY_SETTLED"
                                attempts.containsKey(akey) -> "PRIOR_ATTEMPT"
                                else -> {
                                    val value = BigInteger(o.getValue("cashResidual")); val quantity = BigInteger(o.getValue("shareResidual"))
                                    val next = mapOf("buyerCash" to money.getValue("buyerCash") - value, "sellerCash" to money.getValue("sellerCash") + value,
                                        "buyerShares" to money.getValue("buyerShares") + quantity, "sellerShares" to money.getValue("sellerShares") - quantity)
                                    val outcome = when {
                                        next.getValue("buyerCash").signum() < 0 -> "INSUFFICIENT_CASH"
                                        next.getValue("sellerShares").signum() < 0 -> "INSUFFICIENT_SHARES"
                                        next.values.any { it > max } -> "BALANCE_OVERFLOW"
                                        else -> "SETTLED"
                                    }
                                    attempts[akey] = outcome; versions[ekey] = versions.getValue(ekey) + 1; due.remove(ekey)
                                    if (outcome == "SETTLED") {
                                        next.forEach { (name, amount) -> money[name] = amount; versions[name] = versions.getValue(name) + 1 }
                                        o["status"] = "SETTLED"; o["cashResidual"] = "0"; o["shareResidual"] = "0"
                                        workflows[ekey] = "SETTLED"; instructions[ekey] = "SETTLED"
                                        legs = listOf(leg("USD_NANO", "buyer", -value), leg("USD_NANO", "seller", value), leg("ACME_SHARE", "seller", -quantity), leg("ACME_SHARE", "buyer", quantity))
                                    } else exceptions[akey] = outcome
                                    outcome
                                }
                            }
                            if (isContinue && eligible().isEmpty()) continuation = null
                        }
                    }
                }
                "FUND" -> {
                    val account = normalized["account"]?.asText(); val asset = normalized["asset"]?.asText(); val amount = normalized["amount"]?.asText()?.let(::BigInteger)
                    if (continuation == null && account in setOf("buyer", "seller") && asset in setOf("USD_NANO", "ACME_SHARE") && amount != null && amount.signum() > 0 && normalized["authority"]?.asText() == "opening-resource-owner") {
                        val field = account + if (asset == "USD_NANO") "Cash" else "Shares"
                        val opening = if (asset == "USD_NANO") "openingCash" else "openingShares"
                        if (amount > max) disposition = "AMOUNT_OVERFLOW"
                        else if (money.getValue(field) + amount > max || money.getValue(opening) - amount < min) disposition = "BALANCE_OVERFLOW"
                        else {
                            money[field] = money.getValue(field) + amount; money[opening] = money.getValue(opening) - amount
                            versions[field] = versions.getValue(field) + 1; versions[opening] = versions.getValue(opening) + 1
                            legs = listOf(leg(asset!!, "opening", -amount), leg(asset, account!!, amount)); disposition = "FUNDED"
                        }
                    }
                }
                "ACTIVATE_POLICY" -> if (normalized["policy"]?.asText() in setOf("gross-p1", "gross-p2") && continuation == null) { activePolicy = normalized["policy"].asText(); disposition = "POLICY_ACTIVATED" }
                "CLOCK" -> {
                    val next = normalized["tick"]?.asText()?.let(::BigInteger)
                    if (next != null && (next < min || next > max)) disposition = "AMOUNT_OVERFLOW"
                    else if (next != null && next >= tick && continuation == null) {
                        tick = next
                        continuation = mapOf("clockAction" to input.path("actionId").asText(), "phase" to "DRAIN_DUE")
                        if (eligible().isEmpty()) continuation = null
                        disposition = "CLOCK_ADVANCED"
                    }
                }
            }
        }
        seq++
        decision = if (prior == null) key else conflictKey
        dedup[decision!!] = mapOf("digest" to digest, "context" to context, "disposition" to disposition)
        if (legs.isNotEmpty()) effects[decision!!] = legs
        semanticDigest = sha(canonical(json.valueToTree(mapOf("decisionId" to decision, "disposition" to disposition, "input" to digest, "context" to context, "journalLegs" to legs, "semanticChanges" to semanticChanges(previousBusiness, businessView())))))
        return projection(disposition, input, legs.size, selected)
    }

    private fun projection(disposition: String, input: JsonNode, journalLegs: Int, selected: String): ObjectNode {
        val result = json.createObjectNode().put("disposition", disposition).put("journalLegs", journalLegs).put("selectedPolicy", selected).put("activePolicy", activePolicy).put("obligationCount", obligations.size)
        result.set<JsonNode>("balances", json.valueToTree(money.filterKeys { !it.startsWith("opening") }.mapValues { it.value.toString() }))
        result.set<JsonNode>("dueWork", json.valueToTree(eligible().map { executions.getValue(it)["executionId"].asText() }))
        result.set<JsonNode>("settledExecutions", json.valueToTree(obligations.filterValues { it["status"] == "SETTLED" }.keys.map { executions.getValue(it)["executionId"].asText() }.sorted()))
        result.set<JsonNode>("pendingExecutions", json.valueToTree(obligations.filterValues { it["status"] == "PENDING" }.keys.map { executions.getValue(it)["executionId"].asText() }.sorted()))
        if (continuation != null) result.put("continuationPhase", "DRAIN_DUE")
        val id = input.path("payload").get("executionId")?.asText() ?: input.path("payload").get("workId")?.asText()
        val o = obligations.entries.singleOrNull { executions.getValue(it.key)["executionId"].asText() == id }?.value ?: obligations.values.singleOrNull()
        o?.let { result.put("obligationStatus", it["status"]); result.put("cashResidual", it["cashResidual"]); result.put("shareResidual", it["shareResidual"]) }
        val ordinal = input.path("payload").get("attempt")?.asText() ?: if (input.path("kind").asText().uppercase() == "CONTINUE") "1" else null
        if (ordinal != null && disposition == "SETTLED") result.put("attempt", BigInteger(ordinal).toString())
        if (ordinal != null && disposition in setOf("INSUFFICIENT_CASH", "INSUFFICIENT_SHARES", "BALANCE_OVERFLOW")) result.put("failedAttempt", BigInteger(ordinal).toString())
        return result
    }

    fun businessView(): JsonNode {
        val view: ObjectNode = json.valueToTree(linkedMapOf("balances" to money.mapValues { it.value.toString() }, "executions" to executions, "obligations" to obligations,
            "attempts" to attempts, "exceptions" to exceptions, "workflows" to workflows, "instructions" to instructions, "dedup" to dedup,
            "effects" to effects, "versions" to versions, "dueQueue" to due, "reservations" to emptyMap<String, String>(), "activePolicy" to activePolicy,
            "logicalTick" to tick.toString(), "dueWork" to eligible().map { executions.getValue(it)["executionId"].asText() }, "continuation" to continuation,
            "businessSeq" to seq, "lastDecisionId" to decision))
        view.put("semanticDigest", semanticDigest)
        return view
    }

    fun assertMatches(actual: JsonNode, label: String) {
        val balances = actual["balances"]
        for (asset in listOf("Cash", "Shares")) {
            val total = listOf("buyer$asset", "seller$asset", "opening$asset").fold(BigInteger.ZERO) { sum, name -> sum + BigInteger(balances[name].asText()) }
            if (total != BigInteger.ZERO) throw AssertionError("$label unbalanced $asset: $total")
        }
        actual["effects"].forEach { group ->
            val totals = group.groupBy { it["asset"].asText() }.mapValues { (_, legs) -> legs.fold(BigInteger.ZERO) { sum, leg -> sum + BigInteger(leg["amount"].asText()) } }
            if (totals.values.any { it != BigInteger.ZERO }) throw AssertionError("$label unbalanced posting group: $totals")
        }
        if (canonical(businessView()) != canonical(actual)) throw AssertionError("$label reference=${businessView()} actual=$actual")
    }

    /** Reference-only snapshots; derive complete semantic deltas independently from economic state. */
    private fun semanticChanges(before: JsonNode, after: JsonNode): List<Map<String, Any>> {
        val entryMaps = setOf("balances", "executions", "obligations", "attempts", "exceptions", "workflows", "instructions", "dedup", "effects", "versions", "dueQueue", "reservations")
        val changes = mutableListOf<Map<String, Any>>()
        before.fieldNames().asSequence().filter { it !in setOf("semanticDigest", "dueWork") }.forEach { root ->
            if (root in entryMaps) {
                val keys = (before[root].fieldNames().asSequence().toList() + after[root].fieldNames().asSequence().toList()).toSet()
                keys.forEach { key ->
                    val old = before[root].get(key) ?: json.nullNode(); val next = after[root].get(key) ?: json.nullNode()
                    if (old != next) changes.add(mapOf("path" to listOf(root, key), "before" to old, "after" to next))
                }
            } else if (before[root] != after[root]) changes.add(mapOf("path" to listOf(root), "before" to before[root], "after" to after[root]))
        }
        return changes.sortedBy { json.writeValueAsString(it["path"]) }
    }

    private fun eligible(): List<String> = if (continuation == null) emptyList() else due.keys.filter { BigInteger(due.getValue(it).getValue("tick").toString()) <= tick }.sortedWith(compareBy<String> { BigInteger(due.getValue(it).getValue("tick").toString()) }.thenBy { executions.getValue(it)["executionId"].asText() }.thenBy { it })
    private fun leg(asset: String, account: String, amount: BigInteger) = mapOf("asset" to asset, "account" to account, "amount" to amount.toString())
    private fun execKey(payload: JsonNode) = frame(*listOf("runId", "venueSessionId", "instrumentId", "executionId").map { payload[it].asText() }.toTypedArray())
    private fun frame(vararg fields: String) = json.writeValueAsString(fields)
    private fun integer(value: String): BigInteger { require(value.matches(Regex("-?[0-9]+"))); return BigInteger(value) }
    private fun sha(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(node[it]) }
        node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
        else -> node.toString()
    }
}
