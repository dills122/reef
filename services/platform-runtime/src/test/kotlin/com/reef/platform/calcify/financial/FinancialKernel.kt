package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.security.MessageDigest
import java.util.TreeMap
import java.util.HexFormat

/** Test-only unreserved gross DvP experiment; no production financial authority. */
internal class FinancialKernel private constructor(private val state: ObjectNode, private val retainHistory: Boolean) {
    private val records = mutableListOf<JsonNode>()
    private var latestRecord: JsonNode? = null
    private val dueIndex = TreeMap<Due, String>()
    private val executionIndex = mutableMapOf<String, MutableSet<String>>()
    private var chain = "GENESIS"

    init {
        // JSON wire integers carry value, not Jackson IntNode/LongNode subtype.
        for (field in listOf("businessSeq", "historySeq", "deliveryCursor", "nextDelivery")) {
            val value = state[field]
            require(value.isIntegralNumber && fits(value.bigIntegerValue()) && value.bigIntegerValue().signum() >= 0)
            state.put(field, value.asLong())
        }
        state["stagedInputs"].properties().forEach { (_, value) ->
            val position = value["position"]
            require(position.isIntegralNumber && fits(position.bigIntegerValue()) && position.bigIntegerValue().signum() >= 0)
            (value as ObjectNode).put("position", position.asLong())
        }
        (state["versions"] as ObjectNode).properties().toList().forEach { (key, value) ->
            require(value.isIntegralNumber && fits(value.bigIntegerValue()) && value.bigIntegerValue().signum() >= 0)
            (state["versions"] as ObjectNode).put(key, value.asLong())
        }
    }

    constructor(genesisBalances: JsonNode, policy: JsonNode, retainHistory: Boolean = true) : this(emptyState(), retainHistory) {
        val changes = Changes()
        genesisBalances.properties().forEach { (key, value) ->
            require(key in balanceKeys && integer(value).signum() >= 0)
            changes.put(listOf("balances", key), text(checked(integer(value))))
        }
        require(balanceKeys.all { genesisBalances.has(it) })
        changes.put(listOf("balances", "openingCash"), text(checked(-integer(genesisBalances["buyerCash"]) - integer(genesisBalances["sellerCash"]))))
        changes.put(listOf("balances", "openingShares"), text(checked(-integer(genesisBalances["buyerShares"]) - integer(genesisBalances["sellerShares"]))))
        (balanceKeys + listOf("openingCash", "openingShares")).forEach { changes.put(listOf("versions", it), number(0)) }
        changes.put(listOf("policy"), policy)
        changes.put(listOf("activePolicy"), policy["policy"])
        changes.put(listOf("semanticDigest"), text(hash("GENESIS")))
        val genesisLegs = mapper.createArrayNode()
        for ((asset, suffix) in listOf("USD_NANO" to "Cash", "ACME_SHARE" to "Shares")) {
            genesisLegs.add(leg(asset, "opening", BigInteger(changes.get(listOf("balances", "opening$suffix"))!!.asText())))
            for (account in listOf("buyer", "seller")) genesisLegs.add(leg(asset, account, integer(genesisBalances[account + suffix])))
        }
        validate(changes, genesisLegs)
        append("GENESIS", changes, null, genesisLegs)
    }

    private data class Due(val tick: BigInteger, val work: String, val key: String) : Comparable<Due> {
        override fun compareTo(other: Due): Int = compareValuesBy(this, other, { it.tick }, { it.work }, { it.key })
    }

    private inner class Changes {
        private val writes = linkedMapOf<List<String>, JsonNode?>()
        fun get(path: List<String>): JsonNode? = if (writes.containsKey(path)) writes[path] else at(state, path)
        fun put(path: List<String>, value: JsonNode?) { writes[path] = value?.deepCopy<JsonNode>() }
        fun encoded(): ArrayNode = mapper.createArrayNode().also { a -> writes.forEach { (path, value) ->
            a.add(obj("path" to mapper.valueToTree(path), "before" to (at(state, path) ?: mapper.nullNode()), "after" to (value ?: mapper.nullNode())))
        } }
        fun increment(path: List<String>) = put(path, number((get(path)?.asLong() ?: 0L) + 1))
    }

    fun execute(input: JsonNode, detailed: Boolean = true): JsonNode {
        val kind = input.path("kind").asText().uppercase()
        if (!state["continuation"].isNull && kind != "CONTINUE") return stage(input, detailed)
        return process(input, null, detailed)
    }

    private fun process(input: JsonNode, stagedKey: String?, detailed: Boolean): JsonNode {
        val key = actionKey(input)
        val kind = input.path("kind").asText().uppercase()
        val normalized = runCatching { normalize(input) }
        val digest = hash(normalized.getOrElse { canonical(input) })
        val original = state["dedup"][key]
        val conflictId = frame(listOf(key, "conflict", digest))
        val prior = if (original != null && original["digest"].asText() != digest) state["dedup"][conflictId] ?: original else original
        if (prior != null && prior["digest"].asText() == digest) {
            if (stagedKey != null) {
                val d = Changes(); d.put(listOf("stagedInputs", stagedKey), null); d.increment(listOf("deliveryCursor")); append("STAGE", d, input)
            }
            return projection(input, "PRIOR_RESULT", 0, prior["context"]["policy"].asText(), detailed).apply {
                put("originalDisposition", input["actionId"].asText()); put("noNewAttempt", true)
            }
        }
        val d = Changes()
        if (stagedKey != null) { d.put(listOf("stagedInputs", stagedKey), null); d.increment(listOf("deliveryCursor")) }
        val context = state["policy"].deepCopy<ObjectNode>().apply {
            put("policy", input.path("requestedPolicy").takeUnless { it.isMissingNode || it.isNull }?.asText() ?: state["activePolicy"].asText())
            put("logicalTick", state["logicalTick"].asText()); put("domain", input.path("domain").asText())
        }
        val legs = mapper.createArrayNode()
        var status = when {
            prior != null -> "ACTION_CONFLICT"
            normalized.isFailure || listOf("namespace", "domain", "actionId").any { input.path(it).asText().isEmpty() } -> "INVALID_INPUT"
            context["policy"].asText() !in setOf("gross-p1", "gross-p2") -> "MISSING_POLICY"
            else -> runCatching {
                when (kind) {
                    "CAPTURE" -> capture(input["payload"], d)
                    "SETTLE" -> settle(input["payload"], d, legs, false)
                    "FUND" -> fund(input["payload"], d, legs)
                    "ACTIVATE_POLICY" -> {
                        val policy = required(input["payload"], "policy")
                        require(policy in setOf("gross-p1", "gross-p2"))
                        d.put(listOf("activePolicy"), text(policy)); "POLICY_ACTIVATED"
                    }
                    "CLOCK" -> clock(input, d)
                    "CONTINUE" -> settle(input["payload"], d, legs, true)
                    else -> "INVALID_INPUT"
                }
            }.getOrElse { error ->
                require(error is IllegalArgumentException || error is ArithmeticException) { "unexpected kernel failure: $error" }
                // Expected invalid input must never retain partial planned mutations.
                return processRejected(input, stagedKey, key, digest, context, if (error is ArithmeticException) "AMOUNT_OVERFLOW" else "INVALID_INPUT", detailed)
            }
        }
        val decisionId = if (prior == null) key else frame(listOf(key, "conflict", digest))
        d.increment(listOf("businessSeq"))
        d.put(listOf("lastDecisionId"), text(decisionId))
        d.put(listOf("dedup", if (prior == null) key else decisionId), obj("digest" to text(digest), "context" to context, "disposition" to text(status)))
        if (!legs.isEmpty) d.put(listOf("effects", decisionId), legs)
        d.put(listOf("semanticDigest"), text(semantic(decisionId, status, digest, context, legs, d.encoded())))
        validate(d, legs)
        append("BUSINESS", d, input, legs)
        return projection(input, status, legs.size(), context["policy"].asText(), detailed)
    }

    private fun processRejected(input: JsonNode, stagedKey: String?, key: String, digest: String, context: ObjectNode, disposition: String, detailed: Boolean): JsonNode {
        val d = Changes()
        if (stagedKey != null) { d.put(listOf("stagedInputs", stagedKey), null); d.increment(listOf("deliveryCursor")) }
        d.increment(listOf("businessSeq")); d.put(listOf("lastDecisionId"), text(key))
        d.put(listOf("dedup", key), obj("digest" to text(digest), "context" to context, "disposition" to text(disposition)))
        d.put(listOf("semanticDigest"), text(semantic(key, disposition, digest, context, mapper.createArrayNode(), d.encoded())))
        append("BUSINESS", d, input)
        return projection(input, disposition, 0, context["policy"].asText(), detailed)
    }

    private fun capture(p: JsonNode, d: Changes): String {
        val fields = listOf("executionId", "runId", "venueSessionId", "instrumentId", "buyOrderId", "sellOrderId", "buyerAccount", "sellerAccount", "cashAsset", "securityAsset", "quantity", "priceNanos", "dueTick")
        fields.forEach { required(p, it) }
        require(p["buyerAccount"].asText() == "buyer" && p["sellerAccount"].asText() == "seller")
        require(p["cashAsset"].asText() == "USD_NANO" && p["securityAsset"].asText() == "ACME_SHARE")
        val q = integer(p["quantity"]); val price = integer(p["priceNanos"]); val due = integer(p["dueTick"])
        require(q.signum() > 0 && price.signum() >= 0 && due.signum() >= 0)
        val cash = checked(q * price); checked(q); checked(due)
        val normalizedPayload = normalizePayload(p)
        val key = executionKey(p)
        val old = state["executions"][key]
        if (old != null) return if (old == normalizedPayload) "EXECUTION_ALREADY_CAPTURED" else "EXECUTION_CONFLICT"
        d.put(listOf("executions", key), normalizedPayload)
        d.put(listOf("obligations", key), obj("status" to text("PENDING"), "cashResidual" to text(cash), "shareResidual" to text(q.toString())))
        d.put(listOf("workflows", key), text("PENDING")); d.put(listOf("instructions", key), text("READY"))
        d.put(listOf("dueQueue", key), obj("tick" to text(due.toString()), "priority" to mapper.nodeFactory.numberNode(0), "workId" to p["executionId"]))
        d.increment(listOf("versions", key))
        return "CAPTURED"
    }

    private fun resolve(p: JsonNode, idField: String): String {
        val id = required(p, idField)
        if (listOf("runId", "venueSessionId", "instrumentId").all { p.has(it) }) {
            val key = frame(listOf(required(p, "runId"), required(p, "venueSessionId"), required(p, "instrumentId"), id))
            require(state["executions"].has(key)); return key
        }
        return executionIndex[id]?.singleOrNull() ?: throw IllegalArgumentException("missing or ambiguous execution")
    }

    private fun settle(p: JsonNode, d: Changes, legs: ArrayNode, continuation: Boolean): String {
        val key = resolve(p, if (continuation) "workId" else "executionId")
        val execution = state["executions"][key]
        val obligation = state["obligations"][key]
        val ordinal = if (continuation) "1" else integer(p["attempt"]).also { require(it.signum() > 0) }.toString()
        checked(BigInteger(ordinal))
        if (continuation) {
            require(!state["continuation"].isNull && required(p, "clockAction") == state["continuation"]["clockAction"].asText())
            require(dueIndex.firstEntry()?.value == key && BigInteger(state["dueQueue"][key]["tick"].asText()) <= BigInteger(state["logicalTick"].asText()))
        }
        if (obligation["status"].asText() == "SETTLED") return "ALREADY_SETTLED"
        val attemptKey = frame(mapper.readTree(key).map { it.asText() } + ordinal)
        if (state["attempts"].has(attemptKey)) return "PRIOR_ATTEMPT"
        val cash = BigInteger(obligation["cashResidual"].asText()); val shares = BigInteger(obligation["shareResidual"].asText())
        var status = when {
            balance("buyerCash") < cash -> "INSUFFICIENT_CASH"
            balance("sellerShares") < shares -> "INSUFFICIENT_SHARES"
            else -> "SETTLED"
        }
        if (status == "SETTLED") {
            val updates = mapOf("buyerCash" to balance("buyerCash") - cash, "sellerCash" to balance("sellerCash") + cash,
                "buyerShares" to balance("buyerShares") + shares, "sellerShares" to balance("sellerShares") - shares)
            if (updates.values.any { !fits(it) }) status = "BALANCE_OVERFLOW" else {
                updates.forEach { (field, amount) -> d.put(listOf("balances", field), text(amount.toString())); d.increment(listOf("versions", field)) }
                legs.add(leg("USD_NANO", "buyer", -cash)); legs.add(leg("USD_NANO", "seller", cash))
                legs.add(leg("ACME_SHARE", "seller", -shares)); legs.add(leg("ACME_SHARE", "buyer", shares))
                d.put(listOf("obligations", key), obj("status" to text("SETTLED"), "cashResidual" to text("0"), "shareResidual" to text("0")))
                d.put(listOf("workflows", key), text("SETTLED")); d.put(listOf("instructions", key), text("SETTLED"))
            }
        }
        d.put(listOf("attempts", attemptKey), text(status)); d.increment(listOf("versions", key))
        if (status != "SETTLED") d.put(listOf("exceptions", attemptKey), text(status))
        d.put(listOf("dueQueue", key), null)
        if (continuation && dueIndex.higherKey(dueIndex.firstKey())?.tick?.let { it <= BigInteger(state["logicalTick"].asText()) } != true) d.put(listOf("continuation"), mapper.nullNode())
        return status
    }

    private fun fund(p: JsonNode, d: Changes, legs: ArrayNode): String {
        require(required(p, "authority") == "opening-resource-owner")
        val account = required(p, "account"); val asset = required(p, "asset")
        require(account in setOf("buyer", "seller") && asset in setOf("USD_NANO", "ACME_SHARE"))
        val amount = integer(p["amount"]); require(amount.signum() > 0); checked(amount)
        val cash = asset == "USD_NANO"; val field = account + if (cash) "Cash" else "Shares"; val opening = if (cash) "openingCash" else "openingShares"
        val credit = balance(field) + amount; val debit = balance(opening) - amount
        if (!fits(credit) || !fits(debit)) return "BALANCE_OVERFLOW"
        d.put(listOf("balances", field), text(credit.toString())); d.put(listOf("balances", opening), text(debit.toString()))
        d.increment(listOf("versions", field)); d.increment(listOf("versions", opening))
        legs.add(leg(asset, "opening", -amount)); legs.add(leg(asset, account, amount)); return "FUNDED"
    }

    private fun clock(input: JsonNode, d: Changes): String {
        val tick = integer(input["payload"]["tick"]); checked(tick); require(tick >= BigInteger(state["logicalTick"].asText()))
        d.put(listOf("logicalTick"), text(tick.toString()))
        d.put(listOf("continuation"), if (dueIndex.firstKeyOrNull()?.tick?.let { it <= tick } == true) obj("clockAction" to input["actionId"], "phase" to text("DRAIN_DUE")) else mapper.nullNode())
        return "CLOCK_ADVANCED"
    }

    private fun validate(d: Changes, legs: ArrayNode) {
        legs.groupBy { it["asset"].asText() }.values.forEach { group -> require(group.fold(BigInteger.ZERO) { total, l -> total + integer(l["amount"]) } == BigInteger.ZERO) }
        d.encoded().forEach { change ->
            val path = change["path"].map { it.asText() }
            if (path.first() == "balances") { val amount = integer(change["after"]); checked(amount); require(path.last().startsWith("opening") || amount.signum() >= 0) }
        }
    }

    fun stage(input: JsonNode, detailed: Boolean = true): JsonNode {
        val digest = hash(normalize(input)); val key = frame(listOf(actionKey(input), digest))
        val old = state["stagedInputs"][key]
        if (old != null) { require(old["digest"].asText() == digest) { "conflicting staged identity" }; return projection(input, "STAGED", 0, state["activePolicy"].asText(), detailed) }
        require(state["stagedInputs"].size() < 64) { "staging capacity exceeded" }
        val d = Changes()
        d.put(listOf("stagedInputs", key), obj("input" to input, "digest" to text(digest), "position" to number(state["nextDelivery"].asLong())))
        d.increment(listOf("nextDelivery")); append("STAGE", d, input)
        return projection(input, "STAGED", 0, state["activePolicy"].asText(), detailed)
    }

    fun drainStaged(maxDecisions: Int = Int.MAX_VALUE, detailed: Boolean = true): List<JsonNode> {
        require(maxDecisions > 0)
        val results = mutableListOf<JsonNode>()
        while (!state["stagedInputs"].isEmpty && results.size < maxDecisions) {
            val staged = state["stagedInputs"].properties().sortedBy { it.value["position"].asLong() }
            val selected = if (state["continuation"].isNull) staged.first() else staged.firstOrNull {
                val input = it.value["input"]
                input["kind"].asText().uppercase() == "CONTINUE" && input["payload"]["workId"].asText() == dueIndex.firstEntry()?.key?.work
            } ?: break
            results.add(process(selected.value["input"], selected.key, detailed))
        }
        return results
    }

    fun businessView(): JsonNode = state.deepCopy().apply {
        listOf("policy", "historySeq", "deliveryCursor", "nextDelivery", "stagedInputs").forEach { remove(it) }
        set<ArrayNode>("dueWork", mapper.valueToTree(dueWork()))
    }
    fun ownerView(): JsonNode = state.deepCopy()
    fun history(): List<JsonNode> {
        check(retainHistory) { "journal retention disabled; read durable history instead" }
        return records.map { it.deepCopy<JsonNode>() }
    }
    fun lastRecord(): JsonNode? = latestRecord?.deepCopy<JsonNode>()
    fun historySequence(): Long = state["historySeq"].asLong()
    fun checkpoint(): JsonNode = obj("historySeq" to state["historySeq"], "chain" to text(chain), "state" to ownerView()).also { it.put("checksum", hash(canonical(it))) }

    private fun dueWork(): List<String> = if (state["continuation"].isNull) emptyList() else dueIndex.keys.takeWhile { it.tick <= BigInteger(state["logicalTick"].asText()) }.map { it.work }
    private fun balance(field: String) = BigInteger(state["balances"][field].asText())

    private fun projection(input: JsonNode, disposition: String, legCount: Int, selectedPolicy: String, detailed: Boolean): ObjectNode = obj().apply {
        put("disposition", disposition); put("selectedPolicy", selectedPolicy); put("journalLegs", legCount)
        if (!detailed) return@apply
        put("disposition", disposition); set<JsonNode>("balances", state["balances"].deepCopy<ObjectNode>().apply { remove(listOf("openingCash", "openingShares")) })
        put("obligationCount", state["obligations"].size()); put("journalLegs", legCount)
        put("activePolicy", state["activePolicy"].asText()); put("selectedPolicy", selectedPolicy)
        set<ArrayNode>("dueWork", mapper.valueToTree(dueWork()))
        if (!state["continuation"].isNull) put("continuationPhase", "DRAIN_DUE")
        val p = input.path("payload")
        val key = runCatching { resolve(p, if (input.path("kind").asText().uppercase() == "CONTINUE") "workId" else "executionId") }.getOrNull()
            ?: state["obligations"].properties().firstOrNull()?.key
        key?.let {
            val obligation = state["obligations"][it]
            put("obligationStatus", obligation["status"].asText()); put("cashResidual", obligation["cashResidual"].asText()); put("shareResidual", obligation["shareResidual"].asText())
        }
        if (disposition == "SETTLED") put("attempt", p.path("attempt").asText("1"))
        if (disposition in setOf("INSUFFICIENT_CASH", "INSUFFICIENT_SHARES", "BALANCE_OVERFLOW")) put("failedAttempt", p.path("attempt").asText("1"))
        for ((field, status) in listOf("settledExecutions" to "SETTLED", "pendingExecutions" to "PENDING")) {
            set<ArrayNode>(field, mapper.valueToTree(state["obligations"].properties().filter { it.value["status"].asText() == status }.map { state["executions"][it.key]["executionId"].asText() }.sorted()))
        }
    }

    private fun append(kind: String, d: Changes, input: JsonNode?, legs: JsonNode = mapper.createArrayNode()) {
        d.increment(listOf("historySeq"))
        val record = obj("kind" to text(kind), "sequence" to number(state["historySeq"].asLong() + 1), "priorSequence" to state["historySeq"], "priorChecksum" to text(chain), "changes" to d.encoded(), "journalGroups" to mapper.valueToTree(legs.groupBy { it["asset"].asText() }), "input" to (input ?: mapper.nullNode()))
        val bytes = canonical(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_536 && record["changes"].size() <= 64) { "decision envelope exceeds frozen bound" }
        record.put("checksum", hash(String(bytes, Charsets.UTF_8)))
        evolve(record); if (retainHistory) records.add(record)
    }

    private fun evolve(record: JsonNode) {
        require(record["sequence"].asLong() == state["historySeq"].asLong() + 1 && record["priorSequence"].asLong() == state["historySeq"].asLong()) { "history hole or reordered suffix" }
        require(record["priorChecksum"].asText() == chain) { "history chain mismatch" }
        val body = record.deepCopy<ObjectNode>().apply { remove("checksum") }
        require(record["checksum"].asText() == hash(canonical(body))) { "history tamper" }
        // Validate all preconditions before applying any mutation.
        record["changes"].forEach { c -> require(equivalent(at(state, c["path"].map { it.asText() }) ?: mapper.nullNode(), c["before"])) { "delta precondition mismatch" } }
        record["changes"].forEach { c ->
            val path = c["path"].map { it.asText() }; val after = c["after"]
            val parent = at(state, path.dropLast(1)) as ObjectNode
            if (after.isNull && path.size > 1) parent.remove(path.last()) else parent.set<JsonNode>(path.last(), if (path.first() == "versions" || (path.size == 1 && path.first() in setOf("businessSeq", "historySeq", "deliveryCursor", "nextDelivery"))) {
                require(after.isIntegralNumber && fits(after.bigIntegerValue()) && after.bigIntegerValue().signum() >= 0)
                number(after.asLong())
            } else if (path.first() == "stagedInputs") after.deepCopy<ObjectNode>().apply {
                val position = get("position")
                require(position.isIntegralNumber && fits(position.bigIntegerValue()) && position.bigIntegerValue().signum() >= 0)
                put("position", position.asLong())
            } else after.deepCopy<JsonNode>())
            if (path.first() == "executions" && !after.isNull) executionIndex.getOrPut(after["executionId"].asText()) { mutableSetOf() }.add(path[1])
            if (path.first() == "dueQueue") {
                c["before"].takeUnless { it.isNull }?.let { dueIndex.remove(Due(BigInteger(it["tick"].asText()), it["workId"].asText(), path[1])) }
                if (!after.isNull) dueIndex[Due(BigInteger(after["tick"].asText()), after["workId"].asText(), path[1])] = path[1]
            }
        }
        chain = record["checksum"].asText()
        latestRecord = record.deepCopy<JsonNode>()
    }

    companion object {
        private val mapper = ObjectMapper()
        private val balanceKeys = listOf("buyerCash", "sellerCash", "buyerShares", "sellerShares")
        private val numeric = setOf("quantity", "priceNanos", "dueTick", "attempt", "amount", "tick")
        private val min = BigInteger.valueOf(Long.MIN_VALUE); private val max = BigInteger.valueOf(Long.MAX_VALUE)
        private fun fits(n: BigInteger) = n >= min && n <= max
        private fun checked(n: BigInteger): String { if (!fits(n)) throw ArithmeticException("signed64 overflow"); return n.toString() }
        private fun integer(n: JsonNode?): BigInteger { require(n != null && n.isTextual && n.asText().matches(Regex("-?[0-9]+"))); return BigInteger(n.asText()) }
        private fun required(n: JsonNode?, field: String): String { val v = n?.get(field); require(v != null && v.isTextual && v.asText().isNotEmpty()); return v.asText() }
        private fun frame(fields: List<String>) = mapper.writeValueAsString(fields)
        private fun executionKey(p: JsonNode) = frame(listOf("runId", "venueSessionId", "instrumentId", "executionId").map { required(p, it) })
        private fun actionKey(input: JsonNode) = frame(listOf("namespace", "domain", "actionId").map { input.path(it).asText() })
        private fun normalizePayload(p: JsonNode): ObjectNode = obj().apply { p.properties().sortedBy { it.key }.forEach { (k,v) -> set<JsonNode>(k, if (k in numeric) text(integer(v).toString()) else v) } }
        private fun normalize(input: JsonNode): String {
            val pairs = mapper.createArrayNode(); val p = input["payload"]; require(p != null && p.isObject)
            p.properties().sortedBy { it.key }.forEach { (k,v) -> pairs.add(mapper.createArrayNode().add(k).add(if (k in numeric) text(integer(v).toString()) else v)) }
            return mapper.writeValueAsString(mapper.createArrayNode().add(input.path("kind").asText().uppercase()).add(input.get("requestedPolicy") ?: mapper.nullNode()).add(pairs))
        }
        private fun semantic(id: String, disposition: String, inputDigest: String, context: JsonNode, legs: JsonNode, changes: JsonNode): String {
            val semanticChanges = mapper.createArrayNode()
            changes.filter { c ->
                c["path"][0].asText() !in setOf("historySeq", "deliveryCursor", "nextDelivery", "stagedInputs", "policy", "semanticDigest") && c["before"] != c["after"]
            }.sortedBy { canonical(it["path"]) }.forEach { semanticChanges.add(it) }
            return hash(canonical(obj("decisionId" to text(id), "disposition" to text(disposition), "input" to text(inputDigest), "context" to context, "journalLegs" to legs, "semanticChanges" to semanticChanges)))
        }
        private fun leg(asset: String, account: String, amount: BigInteger) = obj("asset" to text(asset), "account" to text(account), "amount" to text(amount.toString()))
        private fun obj(vararg fields: Pair<String, JsonNode>): ObjectNode = mapper.createObjectNode().apply { fields.forEach { (k,v) -> set<JsonNode>(k,v) } }
        private fun text(s: String) = mapper.nodeFactory.textNode(s)
        private fun number(n: Long) = mapper.nodeFactory.numberNode(n)
        private fun hash(s: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
        private fun canonical(n: JsonNode): String = when {
            n.isObject -> n.properties().sortedBy { it.key }.joinToString(",", "{", "}") { mapper.writeValueAsString(it.key) + ":" + canonical(it.value) }
            n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
            else -> mapper.writeValueAsString(n)
        }
        private fun equivalent(left: JsonNode, right: JsonNode): Boolean = when {
            left.isIntegralNumber && right.isIntegralNumber -> left.bigIntegerValue() == right.bigIntegerValue()
            left.isObject && right.isObject -> left.size() == right.size() && left.properties().all { (key, value) -> right.get(key)?.let { equivalent(value, it) } == true }
            left.isArray && right.isArray -> left.size() == right.size() && (0 until left.size()).all { equivalent(left[it], right[it]) }
            else -> left == right
        }
        private fun at(root: JsonNode, path: List<String>): JsonNode? = path.fold(root as JsonNode?) { n, key -> n?.get(key) }
        private fun emptyState(): ObjectNode = obj().apply {
            for (field in listOf("balances", "executions", "obligations", "workflows", "instructions", "attempts", "exceptions", "reservations", "dedup", "effects", "versions", "dueQueue", "stagedInputs")) set<ObjectNode>(field, obj())
            put("logicalTick", "0"); put("businessSeq", 0L); put("historySeq", 0L); put("deliveryCursor", 0L); put("nextDelivery", 0L)
            putNull("continuation"); putNull("lastDecisionId"); putNull("semanticDigest"); putNull("activePolicy"); set<ObjectNode>("policy", obj())
        }
        private fun <K,V> TreeMap<K,V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()
        fun replay(records: Iterable<JsonNode>, retainHistory: Boolean = true): FinancialKernel {
            val iterator = records.iterator()
            require(iterator.hasNext()) { "empty history" }
            val first = iterator.next()
            require(first["kind"].asText() == "GENESIS")
            return FinancialKernel(emptyState(), retainHistory).apply {
                evolve(first); if (retainHistory) this.records.add(first.deepCopy<JsonNode>())
                while (iterator.hasNext()) { val record = iterator.next(); evolve(record); if (retainHistory) this.records.add(record.deepCopy<JsonNode>()) }
            }
        }
        fun restore(checkpoint: JsonNode, suffix: Iterable<JsonNode>, retainHistory: Boolean = true): FinancialKernel {
            val body = checkpoint.deepCopy<ObjectNode>().apply { remove("checksum") }
            require(checkpoint["checksum"].asText() == hash(canonical(body))) { "checkpoint tamper" }
            require(equivalent(checkpoint["historySeq"], checkpoint["state"]["historySeq"]))
            return FinancialKernel(checkpoint["state"].deepCopy<ObjectNode>(), retainHistory).apply {
                chain = checkpoint["chain"].asText()
                state["executions"].properties().forEach { (k,v) -> executionIndex.getOrPut(v["executionId"].asText()) { mutableSetOf() }.add(k) }
                state["dueQueue"].properties().forEach { (k,v) -> dueIndex[Due(BigInteger(v["tick"].asText()), v["workId"].asText(), k)] = k }
                suffix.forEach { evolve(it); if (retainHistory) records.add(it.deepCopy<JsonNode>()) }
            }
        }
    }
}
