package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinancialCommittedCutTest {
    private val json = ObjectMapper()
    private fun node(value: Any): JsonNode = json.valueToTree(value)
    private data class Fixture(val config: JsonNode, val manifest: JsonNode, val outputs: List<JsonNode>)

    @Test fun `committed quartet proves exact mixed balances refusal and complete activation`() {
        val fixture = quartet()
        val cut = FinancialCommittedCut.build(fixture.config, fixture.manifest, fixture.outputs)
        val mixed = cut.snapshots.first().filterKeys { json.readTree(it.removePrefix("s/"))[0].asText() == "A" } +
            cut.snapshots.last().filterKeys { json.readTree(it.removePrefix("s/"))[0].asText() == "B" }
        fun balance(state: Map<String, JsonNode>, domain: String) = state.getValue("s/" + json.writeValueAsString(listOf(domain, "balances", "buyerCash"))).asText()
        assertEquals("10", balance(mixed, "A")); assertEquals("27", balance(mixed, "B"))
        val membership = fixture.manifest["membership"].toList()
        assertFailsWith<IllegalArgumentException> { FinancialPartitionCut.validateActivation(fixture.config, "topic-uuid", membership, cut.certificate, cut.coverage, cut.histories, mixed) }
        val restored = FinancialPartitionCut.validateActivation(fixture.config, "topic-uuid", membership, cut.certificate, cut.coverage, cut.histories, cut.snapshots.last())
        assertEquals("15", restored.owners.getValue("A").ownerView()["balances"]["buyerCash"].asText())
        assertEquals("27", restored.owners.getValue("B").ownerView()["balances"]["buyerCash"].asText())
        assertFailsWith<IllegalArgumentException> { FinancialPartitionCut.reconstruct(fixture.config, "topic-uuid", membership, cut.certificate, cut.coverage, cut.histories.filterKeys { it != "h/A/00000000000000000003" }) }
    }

    @Test fun `committed outputs must match actual accepted physical source membership`() {
        val fixture = quartet()
        for (field in listOf("inputOffset", "inputOrdinal", "domain")) {
            val outputs = fixture.outputs.map { it.deepCopy<ObjectNode>() }
            if (field == "domain") outputs[2].put(field, "B") else outputs[2].put(field, 100L)
            assertFailsWith<IllegalArgumentException> { FinancialCommittedCut.build(fixture.config, fixture.manifest, outputs) }
        }
        assertFailsWith<IllegalArgumentException> { FinancialCommittedCut.build(fixture.config, fixture.manifest, fixture.outputs.drop(1)) }
    }

    @Test fun `committed cut rejects economically wrong but structurally valid output`() {
        val fixture = quartet()
        val outputs = fixture.outputs.map { it.deepCopy<ObjectNode>() }
        outputs[3].put("disposition", "REJECTED")
        assertFailsWith<IllegalArgumentException> { FinancialCommittedCut.build(fixture.config, fixture.manifest, outputs) }
    }

    private fun quartet(): Fixture {
        val fixtures = json.readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").readText())
        val balances = mapOf("buyerCash" to "0", "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to "0")
        val inputs = listOf("A" to "10", "B" to "20", "A" to "5", "B" to "7").mapIndexed { ordinal, (domain, amount) ->
            node(mapOf("domain" to domain, "mode" to "EXECUTE", "inputOrdinal" to ordinal, "input" to mapOf("namespace" to "quartet", "domain" to domain, "actionId" to "fund-$ordinal", "kind" to "FUND", "payload" to mapOf("account" to "buyer", "asset" to "USD_NANO", "amount" to amount, "authority" to "opening-resource-owner"))))
        }
        val config = node(mapOf("policy" to fixtures["policy"], "genesis" to mapOf("A" to mapOf("balances" to balances), "B" to mapOf("balances" to balances)), "inputs" to inputs))
        val offsets = listOf(2L, 3L, 7L, 8L)
        val membership = inputs.mapIndexed { ordinal, input -> node(mapOf("ordinal" to ordinal, "offset" to offsets[ordinal], "domain" to input["domain"].asText(), "payloadSha256" to MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray()).joinToString("") { "%02x".format(it) })) }
        val manifest = node(mapOf("inputTopicId" to "topic-uuid", "membership" to membership))
        val owners = mutableMapOf<String, FinancialKernel>()
        val outputs = inputs.mapIndexed { ordinal, envelope ->
            val domain = envelope["domain"].asText(); val records = mutableListOf<JsonNode>()
            val owner = owners.getOrPut(domain) { FinancialKernel(node(balances), fixtures["policy"]).also { records.add(it.lastRecord()!!) } }
            val result = owner.execute(envelope["input"], detailed = false); records.add(owner.lastRecord()!!)
            node(mapOf("domain" to domain, "inputOrdinal" to ordinal, "inputOffset" to offsets[ordinal], "records" to records, "disposition" to result["disposition"].asText()))
        }
        return Fixture(config, manifest, outputs)
    }
}
