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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FinancialPartitionCutTest {
    private val json = ObjectMapper()
    private val fixtures = json.readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").readText())
    private val uuid = "accepted-topic-generation"

    @Test fun mixedA10B27CannotActivateAtQuartetEnd() {
        val cut = quartet()
        val mixed = cut.states[0].filterKeys { domainOf(it) == "A" } + cut.states[3].filterKeys { domainOf(it) == "B" }
        assertEquals("10", mixed.getValue(stateKey("A", "balances", "buyerCash")).asText())
        assertEquals("27", mixed.getValue(stateKey("B", "balances", "buyerCash")).asText())
        assertFailsWith<IllegalArgumentException> { activate(cut, state = mixed) }
    }

    @Test fun completeHistoryRebuildsA15B27AndEachFundingEffectOnce() {
        val cut = quartet()
        val reconstructed = reconstruct(cut)
        assertEquals("15", reconstructed.owners.getValue("A").ownerView()["balances"]["buyerCash"].asText())
        assertEquals("27", reconstructed.owners.getValue("B").ownerView()["balances"]["buyerCash"].asText())
        for ((domain, expected) in mapOf("A" to 15L, "B" to 27L)) {
            val owner = reconstructed.owners.getValue(domain).ownerView()
            val fundingLegs = owner["effects"].flatMap { it.toList() }.filter { it["account"].asText() == "buyer" }
            assertEquals(expected, fundingLegs.sumOf { it["amount"].asLong() })
            assertEquals(2, owner["effects"].size())
            assertEquals(2, owner["dedup"].size())
            assertEquals(3L, owner["historySeq"].asLong(), "balance frontier differs from domain history sequence")
        }
        assertEquals(cut.states.last(), reconstructed.semanticState)
        assertEquals(reconstructed.semanticState, activate(cut).semanticState)
        assertEquals(3L, reconstructed.ordinal)
        assertEquals(8L, reconstructed.offset)
        val capturedGroupPosition = 10L // Includes transaction/control offsets; distinct evidence.
        assertNotEquals(reconstructed.ordinal, reconstructed.offset)
        assertNotEquals(reconstructed.offset!! + 1, capturedGroupPosition)
    }

    @Test fun missingAPlus5HistoryRefusesActivationAndReconstruction() {
        val cut = quartet()
        val missing = cut.histories.filterKeys { it != "h/A/00000000000000000003" }
        assertFailsWith<IllegalArgumentException> { activate(cut, histories = missing) }
        assertFailsWith<IllegalArgumentException> { reconstruct(cut, histories = missing) }
    }

    @Test fun mixedOwnerCertificateRefusesEvenWithCompleteSemanticState() {
        val cut = quartet()
        val certificate = cut.certificate.deepCopy<ObjectNode>()
        (certificate["owners"] as ObjectNode).set<JsonNode>("A", cut.coverage[0]["headAfter"])
        assertFailsWith<IllegalArgumentException> { activate(cut, certificate = certificate) }
    }

    @Test fun sourceGenerationAndAuthorityMustMatchEveryCutRow() {
        val cut = quartet()
        for (field in listOf("sourceUuid", "authoritySha256")) {
            val certificate = cut.certificate.deepCopy<ObjectNode>().put(field, "other")
            assertFailsWith<IllegalArgumentException> { activate(cut, certificate = certificate) }
        }
        for (field in listOf("uuid", "authoritySha256")) {
            val rows = cut.coverage.map { it.deepCopy<ObjectNode>() }
            rows[2].put(field, "other")
            assertFailsWith<IllegalArgumentException> { activate(cut, coverage = rows) }
        }
    }

    @Test fun coverageMustRepresentExactAcceptedMembershipDespitePhysicalGaps() {
        val cut = quartet()
        assertEquals(listOf(2L, 3L, 7L, 8L), cut.membership.map { it["offset"].asLong() })
        assertFailsWith<IllegalArgumentException> { activate(cut, coverage = cut.coverage.filterIndexed { index, _ -> index != 2 }) }
        for (field in listOf("ordinal", "offset", "domain", "payloadSha256")) {
            val members = cut.membership.map { it.deepCopy<ObjectNode>() }
            when (field) {
                "ordinal", "offset" -> members[2].put(field, 99L)
                else -> members[2].put(field, "other")
            }
            assertFailsWith<IllegalArgumentException> { activate(cut, membership = members) }
        }
    }

    @Test fun historyReferenceMustBindSourceAndEntireOwnerChain() {
        val cut = quartet()
        for (field in listOf("ordinal", "offset", "uuid", "payloadSha256")) {
            val histories = cut.histories.mapValues { (_, value) -> value.deepCopy<ObjectNode>() }
            val source = histories.getValue("h/A/00000000000000000003")["source"] as ObjectNode
            if (field in setOf("ordinal", "offset")) source.put(field, 99L) else source.put(field, "other")
            assertFailsWith<IllegalArgumentException> { activate(cut, histories = histories) }
        }
        val corrupt = cut.histories.mapValues { (_, value) -> value.deepCopy<ObjectNode>() }
        (corrupt.getValue("h/A/00000000000000000003")["record"] as ObjectNode).put("checksum", "corrupt")
        assertFailsWith<IllegalArgumentException> { activate(cut, histories = corrupt) }
        val rows = cut.coverage.map { it.deepCopy<ObjectNode>() }
        rows[2].set<JsonNode>("headBefore", cut.coverage[0]["headBefore"])
        assertFailsWith<IllegalArgumentException> { activate(cut, coverage = rows) }
        assertFailsWith<IllegalArgumentException> { activate(cut, histories = cut.histories + ("h/A/extra" to cut.histories.values.first())) }
    }

    @Test fun acceptedSuffixDoesNotInvalidateCertifiedEarlierPrefix() {
        val cut = quartet()
        val certificate = cut.certificate.deepCopy<ObjectNode>().apply {
            put("ordinal", 1L); put("offset", 3L)
            set<JsonNode>("owners", node(mapOf("A" to cut.coverage[0]["headAfter"], "B" to cut.coverage[1]["headAfter"])))
        }
        val histories = cut.histories.filterValues { it["source"]["ordinal"].asLong() < 2 }
        val prefix = activate(cut, certificate = certificate, coverage = cut.coverage.take(2), histories = histories, state = cut.states[1])
        assertEquals("10", prefix.owners.getValue("A").ownerView()["balances"]["buyerCash"].asText())
        assertEquals("20", prefix.owners.getValue("B").ownerView()["balances"]["buyerCash"].asText())
    }

    @Test fun pendingPhaseStagingAndDedupStateRequireExactAgreement() {
        val fixture = fixtures["cases"].last()
        val steps = fixture["steps"].map { it["input"] }
        val envelopes = steps.take(3).map { envelope("domain-1", "EXECUTE", it) } + listOf(envelope("domain-1", "STAGE", steps[5]))
        val cut = fixtureCut(node(mapOf("domain-1" to mapOf("balances" to fixture["genesisBalances"]))), envelopes)
        val owner = activate(cut).owners.getValue("domain-1").ownerView()
        assertTrue(!owner["continuation"].isNull)
        assertEquals(1, owner["stagedInputs"].size())
        assertTrue(owner["dedup"].size() > 0)
        assertEquals(owner, reconstruct(cut).owners.getValue("domain-1").ownerView())
        for (field in listOf("continuation", "stagedInputs", "dedup", "logicalTick", "nextDelivery", "dueQueue")) {
            val lost = cut.states.last().filterKeys { json.readTree(it.removePrefix("s/"))[1].asText() != field }
            assertTrue(lost.size < cut.states.last().size, "fixture must contain $field")
            assertFailsWith<IllegalArgumentException>(field) { activate(cut, state = lost) }
        }
        assertFailsWith<IllegalArgumentException> { activate(cut, state = cut.states.last() + (stateKey("domain-1", "deliveryCursor") to node(1L))) }
    }

    @Test fun numericCutsCannotCoerceStringsFractionsOverflowNegativesOrNull() {
        val cut = quartet()
        val malformed = listOf(json.readTree("\"3\""), json.readTree("3.75"), json.readTree("9223372036854775808"), node(-1L), json.nullNode())
        for (value in malformed) {
            for (field in listOf("ordinal", "offset")) {
                val certificate = cut.certificate.deepCopy<ObjectNode>().apply { set<JsonNode>(field, value) }
                assertFailsWith<IllegalArgumentException> { activate(cut, certificate = certificate) }
                val rows = cut.coverage.map { it.deepCopy<ObjectNode>() }
                rows[2].set<JsonNode>(field, value)
                assertFailsWith<IllegalArgumentException> { activate(cut, coverage = rows) }
                val members = cut.membership.map { it.deepCopy<ObjectNode>() }
                members[2].set<JsonNode>(field, value)
                assertFailsWith<IllegalArgumentException> { activate(cut, membership = members) }
            }
            val certificate = cut.certificate.deepCopy<ObjectNode>()
            (certificate["owners"]["A"] as ObjectNode).set<JsonNode>("sequence", value)
            assertFailsWith<IllegalArgumentException> { activate(cut, certificate = certificate) }
        }
    }

    @Test fun incompleteOrWronglyShapedCertificatesCannotActivate() {
        val cut = quartet()
        for (field in listOf("ordinal", "offset", "sourceUuid", "authoritySha256", "owners")) {
            val missing = cut.certificate.deepCopy<ObjectNode>().apply { remove(field) }
            assertFailsWith<IllegalArgumentException> { activate(cut, certificate = missing) }
        }
        for (field in listOf("headBefore", "headAfter", "history", "domain", "uuid")) {
            val rows = cut.coverage.map { it.deepCopy<ObjectNode>() }
            rows[2].putNull(field)
            assertFailsWith<IllegalArgumentException> { activate(cut, coverage = rows) }
        }
    }

    @Test fun emptyStoreIsOnlyValidWithoutCertificateOrHistory() {
        val cut = quartet()
        val empty = FinancialPartitionCut.validateActivation(cut.config, uuid, cut.membership, null, emptyList(), emptyMap(), emptyMap())
        assertTrue(empty.owners.isEmpty())
        assertFailsWith<IllegalArgumentException> { FinancialPartitionCut.validateActivation(cut.config, uuid, cut.membership, null, cut.coverage, cut.histories, cut.states.last()) }
        assertFailsWith<IllegalArgumentException> { FinancialPartitionCut.validateActivation(cut.config, uuid, cut.membership, null, emptyList(), emptyMap(), cut.states.last()) }
    }

    @Test fun genesisCannotSubstituteDifferentPolicyOrOpeningBalances() {
        val cut = quartet()
        val changed = cut.config.deepCopy<ObjectNode>()
        (changed["genesis"]["A"]["balances"] as ObjectNode).put("buyerCash", "99")
        val authority = sha(canonical(node(mapOf("policy" to changed["policy"], "genesis" to changed["genesis"]))))
        val certificate = cut.certificate.deepCopy<ObjectNode>().put("authoritySha256", authority)
        val rows = cut.coverage.map { it.deepCopy<ObjectNode>().put("authoritySha256", authority) }
        assertFailsWith<IllegalArgumentException> { FinancialPartitionCut.validateActivation(changed, uuid, cut.membership, certificate, rows, cut.histories, cut.states.last()) }
    }

    private data class FixtureCut(val config: JsonNode, val membership: List<JsonNode>, val certificate: JsonNode, val coverage: List<JsonNode>, val histories: Map<String, JsonNode>, val states: List<Map<String, JsonNode>>)
    private fun quartet(): FixtureCut {
        val balances = mapOf("buyerCash" to "0", "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to "0")
        val genesis = node(mapOf("A" to mapOf("balances" to balances), "B" to mapOf("balances" to balances)))
        val envelopes = listOf("A" to "10", "B" to "20", "A" to "5", "B" to "7").mapIndexed { index, (domain, amount) ->
            envelope(domain, "EXECUTE", node(mapOf("namespace" to "quartet", "domain" to domain, "actionId" to "fund-$index", "kind" to "FUND", "payload" to mapOf("account" to "buyer", "asset" to "USD_NANO", "amount" to amount, "authority" to "opening-resource-owner"))))
        }
        return fixtureCut(genesis, envelopes)
    }

    private fun fixtureCut(genesis: JsonNode, envelopes: List<JsonNode>): FixtureCut {
        val config = node(mapOf("genesis" to genesis, "policy" to fixtures["policy"]))
        val authority = sha(canonical(config))
        val kernels = linkedMapOf<String, FinancialKernel>()
        val heads = linkedMapOf<String, JsonNode>()
        val histories = linkedMapOf<String, JsonNode>()
        val state = linkedMapOf<String, JsonNode>()
        val states = mutableListOf<Map<String, JsonNode>>()
        val membership = mutableListOf<JsonNode>()
        val coverage = mutableListOf<JsonNode>()
        val offsets = listOf(2L, 3L, 7L, 8L)
        for ((ordinal, envelope) in envelopes.withIndex()) {
            val domain = envelope["domain"].asText()
            val source = mapOf("ordinal" to ordinal.toLong(), "offset" to offsets[ordinal], "uuid" to uuid, "payloadSha256" to sha(envelope.toString()))
            membership.add(node(source + mapOf("domain" to domain)))
            val records = mutableListOf<JsonNode>()
            val beforeHead = heads[domain] ?: node(mapOf("sequence" to 0L, "checksum" to "GENESIS"))
            val kernel = kernels.getOrPut(domain) { FinancialKernel(genesis[domain]["balances"], fixtures["policy"]).also { records.add(it.lastRecord()!!) } }
            val before = kernel.historySequence()
            if (envelope["mode"].asText() == "STAGE") kernel.stage(envelope["input"]) else kernel.execute(envelope["input"])
            if (kernel.historySequence() != before) records.add(kernel.lastRecord()!!)
            for (record in records) {
                val key = "h/$domain/${record["sequence"].asLong().toString().padStart(20, '0')}"
                histories[key] = node(mapOf("domain" to domain, "record" to record, "source" to source))
                for (change in record["changes"]) {
                    val path = change["path"].map { it.asText() }
                    val stateKey = stateKey(domain, *path.toTypedArray())
                    if (change["after"].isNull && path.size > 1) state.remove(stateKey) else state[stateKey] = change["after"].deepCopy<JsonNode>()
                }
            }
            val after = node(mapOf("sequence" to kernel.historySequence(), "checksum" to kernel.lastRecord()!!["checksum"].asText()))
            heads[domain] = after
            coverage.add(node(source + mapOf("authoritySha256" to authority, "domain" to domain, "headBefore" to beforeHead, "headAfter" to after, "history" to records.map { node(mapOf("sequence" to it["sequence"].asLong(), "checksum" to it["checksum"].asText())) })))
            states.add(state.toMap())
        }
        val certificate = node(mapOf("ordinal" to (envelopes.size - 1).toLong(), "offset" to offsets[envelopes.lastIndex], "sourceUuid" to uuid, "authoritySha256" to authority, "owners" to heads))
        return FixtureCut(config, membership, certificate, coverage, histories, states)
    }

    private fun activate(cut: FixtureCut, certificate: JsonNode = cut.certificate, coverage: List<JsonNode> = cut.coverage, histories: Map<String, JsonNode> = cut.histories, state: Map<String, JsonNode> = cut.states.last(), membership: List<JsonNode> = cut.membership) =
        FinancialPartitionCut.validateActivation(cut.config, uuid, membership, certificate, coverage, histories, state)
    private fun reconstruct(cut: FixtureCut, histories: Map<String, JsonNode> = cut.histories) = FinancialPartitionCut.reconstruct(cut.config, uuid, cut.membership, cut.certificate, cut.coverage, histories)
    private fun envelope(domain: String, mode: String, input: JsonNode) = node(mapOf("domain" to domain, "mode" to mode, "input" to input))
    private fun stateKey(domain: String, vararg path: String) = "s/" + json.writeValueAsString(listOf(domain) + path)
    private fun domainOf(key: String) = json.readTree(key.removePrefix("s/"))[0].asText()
    private fun node(value: Any): JsonNode = json.valueToTree(value)
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonNode): String = when {
        value.isObject -> value.fieldNames().asSequence().toList().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(value[it]) }
        value.isArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}
