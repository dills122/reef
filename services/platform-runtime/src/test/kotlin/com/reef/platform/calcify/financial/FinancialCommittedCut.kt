package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** Isolated activation requests built only from committed result records. */
internal object FinancialCommittedCut {
    private val json = ObjectMapper()
    data class Cut(val certificate: JsonNode, val coverage: List<JsonNode>, val histories: Map<String, JsonNode>, val snapshots: List<Map<String, JsonNode>>)
    fun build(config: JsonNode, manifest: JsonNode, outputs: List<JsonNode>): Cut {
        require(outputs.isNotEmpty() && outputs.size <= manifest.path("membership").size()) { "missing committed input prefix" }
        val uuid = manifest.path("inputTopicId").asText()
        require(uuid.isNotEmpty()) { "missing source generation" }
        val authority = sha(canonical(json.valueToTree(mapOf("policy" to config["policy"], "genesis" to config["genesis"]))))
        val verifier = FinancialBrokerProbe.OutputVerifier(config)
        val heads = linkedMapOf<String, JsonNode>()
        val histories = linkedMapOf<String, JsonNode>()
        val coverage = mutableListOf<JsonNode>()
        val state = linkedMapOf<String, JsonNode>()
        val snapshots = mutableListOf<Map<String, JsonNode>>()
        outputs.forEachIndexed { ordinal, output ->
            val member = manifest["membership"][ordinal]
            val domain = output.path("domain").asText()
            require(output.path("inputOrdinal").isIntegralNumber && output["inputOrdinal"].canConvertToLong() && output["inputOrdinal"].asLong() == ordinal.toLong() &&
                output.path("inputOffset").isIntegralNumber && output["inputOffset"].canConvertToLong() && output["inputOffset"].asLong() == member.path("offset").asLong() &&
                domain == member.path("domain").asText() && member.path("payloadSha256").asText() == sha(config["inputs"][ordinal].toString())) { "committed output/source membership mismatch" }
            verifier.accept(output)
            val source = mapOf("ordinal" to ordinal.toLong(), "offset" to member["offset"].asLong(), "uuid" to uuid, "payloadSha256" to member["payloadSha256"].asText())
            val before = heads[domain] ?: json.valueToTree(mapOf("sequence" to 0L, "checksum" to "GENESIS"))
            val records = output["records"].toList()
            for (record in records) {
                val key = "h/$domain/${record["sequence"].asLong().toString().padStart(20, '0')}"
                require(histories.put(key, json.valueToTree(mapOf("domain" to domain, "record" to record, "source" to source))) == null) { "duplicate committed history" }
                for (change in record["changes"]) {
                    val path = change["path"]
                    val stateKey = "s/" + json.writeValueAsString(listOf(domain) + path.map { it.asText() })
                    if (change["after"].isNull && path.size() > 1) state.remove(stateKey) else state[stateKey] = change["after"].deepCopy<JsonNode>()
                }
            }
            val after = records.lastOrNull()?.let { json.valueToTree<JsonNode>(mapOf("sequence" to it["sequence"].asLong(), "checksum" to it["checksum"].asText())) } ?: before
            heads[domain] = after
            coverage.add(json.valueToTree(source + mapOf("authoritySha256" to authority, "domain" to domain, "headBefore" to before, "headAfter" to after,
                "history" to records.map { json.valueToTree<JsonNode>(mapOf("sequence" to it["sequence"].asLong(), "checksum" to it["checksum"].asText())) })))
            snapshots.add(state.toMap())
        }
        verifier.requirePrefix(outputs.size)
        val certificate = json.valueToTree<JsonNode>(mapOf("ordinal" to outputs.lastIndex.toLong(), "offset" to outputs.last()["inputOffset"].asLong(), "sourceUuid" to uuid, "authoritySha256" to authority, "owners" to heads))
        FinancialPartitionCut.validateActivation(config, uuid, manifest["membership"].toList(), certificate, coverage, histories, snapshots.last())
        return Cut(certificate, coverage, histories, snapshots)
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonNode): String = when {
        value.isObject -> value.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(value[it]) }
        value.isArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}
