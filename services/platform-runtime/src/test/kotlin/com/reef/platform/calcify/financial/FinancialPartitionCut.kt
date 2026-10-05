package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** Test-only cut verifier; physical group resume positions remain framework evidence. */
internal object FinancialPartitionCut {
    private val json = ObjectMapper()
    data class ReconstructedCut(
        val owners: Map<String, FinancialKernel>,
        val semanticState: Map<String, JsonNode>,
        val ordinal: Long?,
        val offset: Long?,
    )

    fun reconstruct(
        config: JsonNode,
        sourceUuid: String,
        membership: List<JsonNode>,
        certificate: JsonNode?,
        coverage: List<JsonNode>,
        historyWrappers: Map<String, JsonNode>,
    ): ReconstructedCut {
        require(config.isObject && config.path("genesis").isObject && config.path("policy").isObject) { "missing configured authority" }
        require(sourceUuid.isNotEmpty()) { "missing source generation" }
        var previousOffset = -1L
        membership.forEachIndexed { index, member ->
            val offset = number(member, "offset")
            require(number(member, "ordinal") == index.toLong() && offset > previousOffset) { "accepted source membership gap" }
            require(config["genesis"].has(text(member, "domain"))) { "unregistered source domain" }
            text(member, "payloadSha256")
            previousOffset = offset
        }
        if (certificate == null) {
            require(coverage.isEmpty() && historyWrappers.isEmpty()) { "history/coverage without certified cut" }
            return ReconstructedCut(emptyMap(), emptyMap(), null, null)
        }
        val ordinal = number(certificate, "ordinal")
        val offset = number(certificate, "offset")
        require(ordinal < membership.size.toLong() && coverage.size.toLong() == ordinal + 1) { "incomplete certified input prefix" }
        val authority = sha(canonical(json.valueToTree(mapOf("policy" to config["policy"], "genesis" to config["genesis"]))))
        require(text(certificate, "sourceUuid") == sourceUuid && text(certificate, "authoritySha256") == authority) { "certificate authority/source generation mismatch" }
        val certifiedOwners = field(certificate, "owners")
        require(certifiedOwners.isObject) { "certificate owners must be object" }
        certifiedOwners.properties().forEach { (domain, value) ->
            require(config["genesis"].has(domain)) { "unregistered certified owner" }
            head(value)
        }

        val heads = linkedMapOf<String, JsonNode>()
        val records = linkedMapOf<String, MutableList<JsonNode>>()
        val referencedHistory = mutableSetOf<String>()
        val state = linkedMapOf<String, JsonNode>()
        val orderedCoverage = coverage.sortedBy { number(it, "ordinal") }
        orderedCoverage.forEachIndexed { index, row ->
            val member = membership[index]
            val domain = text(row, "domain")
            require(number(row, "ordinal") == index.toLong() && number(row, "offset") == number(member, "offset") &&
                text(row, "uuid") == sourceUuid && text(row, "authoritySha256") == authority &&
                text(row, "payloadSha256") == text(member, "payloadSha256") && domain == text(member, "domain")) { "coverage source membership mismatch" }
            val before = heads[domain] ?: json.valueToTree(mapOf("sequence" to 0L, "checksum" to "GENESIS"))
            val declaredBefore = field(row, "headBefore"); head(declaredBefore)
            require(canonical(declaredBefore) == canonical(before)) { "coverage owner prefix gap" }
            val references = field(row, "history")
            require(references.isArray) { "coverage history must be array" }
            var nextHead = before
            for (reference in references) {
                head(reference)
                val sequence = number(reference, "sequence")
                require(sequence == number(nextHead, "sequence") + 1) { "coverage history sequence gap" }
                val key = "h/$domain/${sequence.toString().padStart(20, '0')}"
                val wrapper = historyWrappers[key] ?: throw IllegalArgumentException("coverage history missing key=$key")
                require(referencedHistory.add(key) && text(wrapper, "domain") == domain) { "duplicate/cross-owner history reference" }
                val record = field(wrapper, "record")
                val source = field(wrapper, "source")
                require(record.isObject && number(record, "sequence") == sequence && text(record, "checksum") == text(reference, "checksum") &&
                    number(record, "priorSequence") == number(nextHead, "sequence") && text(record, "priorChecksum") == text(nextHead, "checksum")) { "history owner frontier mismatch" }
                require(number(source, "ordinal") == index.toLong() && number(source, "offset") == number(row, "offset") &&
                    text(source, "uuid") == sourceUuid && text(source, "payloadSha256") == text(row, "payloadSha256")) { "history source causation mismatch" }
                records.getOrPut(domain) { mutableListOf() }.add(record)
                nextHead = reference
            }
            val after = field(row, "headAfter"); head(after)
            require(canonical(after) == canonical(nextHead)) { "coverage owner suffix mismatch" }
            heads[domain] = after
        }
        require(offset == number(orderedCoverage.last(), "offset") && canonical(json.valueToTree(heads)) == canonical(certifiedOwners) && referencedHistory == historyWrappers.keys) { "uncertified partition/owner coverage" }

        val owners = records.mapValues { (domain, history) ->
            val configuredGenesis = FinancialKernel(config["genesis"][domain]["balances"], config["policy"], retainHistory = false).lastRecord()!!
            require(canonical(history.first()) == canonical(configuredGenesis)) { "history genesis differs from configured authority" }
            val restored = FinancialKernel.replay(history, retainHistory = false)
            val frontier = heads.getValue(domain)
            require(restored.historySequence() == number(frontier, "sequence") && restored.lastRecord()!!["checksum"].asText() == text(frontier, "checksum")) { "mixed-age restored owner" }
            for (record in history) {
                val changes = field(record, "changes")
                require(changes.isArray) { "history changes must be array" }
                for (change in changes) {
                    val path = field(change, "path")
                    require(path.isArray && !path.isEmpty && path.all { it.isTextual && it.asText().isNotEmpty() }) { "invalid semantic state path" }
                    val key = "s/" + json.writeValueAsString(listOf(domain) + path.map { it.asText() })
                    val after = field(change, "after")
                    if (after.isNull && path.size() > 1) state.remove(key) else state[key] = after.deepCopy<JsonNode>()
                }
            }
            restored
        }
        require(owners.keys == heads.keys) { "certified owner without complete history" }
        return ReconstructedCut(owners, state, ordinal, offset)
    }

    fun validateActivation(
        config: JsonNode,
        sourceUuid: String,
        membership: List<JsonNode>,
        certificate: JsonNode?,
        coverage: List<JsonNode>,
        historyWrappers: Map<String, JsonNode>,
        semanticState: Map<String, JsonNode>,
    ): ReconstructedCut {
        val cut = reconstruct(config, sourceUuid, membership, certificate, coverage, historyWrappers)
        require(canonical(json.valueToTree(semanticState)) == canonical(json.valueToTree(cut.semanticState))) { "persistent semantic state/history mismatch" }
        return cut
    }

    private fun field(value: JsonNode, name: String): JsonNode {
        require(value.isObject && value.has(name)) { "missing cut field $name" }
        return value[name]
    }
    private fun number(value: JsonNode, name: String): Long {
        val node = field(value, name)
        require(node.isIntegralNumber && node.canConvertToLong() && node.asLong() >= 0) { "invalid cut integer $name" }
        return node.asLong()
    }
    private fun text(value: JsonNode, name: String): String {
        val node = field(value, name)
        require(node.isTextual && node.asText().isNotEmpty()) { "invalid cut text $name" }
        return node.asText()
    }
    private fun head(value: JsonNode) {
        number(value, "sequence"); text(value, "checksum")
        require(value.size() == 2) { "invalid owner head shape" }
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonNode): String = when {
        value.isObject -> value.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(value[it]) }
        value.isArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}
