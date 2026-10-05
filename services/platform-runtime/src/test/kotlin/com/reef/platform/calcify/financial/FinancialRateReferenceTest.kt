package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tests actual Reference binding; no copied economics or CompactReferenceFixture. */
class FinancialRateReferenceTest {
    private val json = ObjectMapper()
    private val policy = json.readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").toFile())["policy"]
    private val balances = json.readTree("""{"buyerCash":"100000000000","sellerCash":"0","buyerShares":"0","sellerShares":"2000"}""")
    private fun canonical(n: JsonNode): String = when {
        n.isObject -> n.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(n[it]) }
        n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
        else -> n.toString()
    }
    private fun digest(n: JsonNode) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(n).toByteArray(Charsets.UTF_8)))
    private fun newReference() = FinancialRateProbe.Reference(balances, policy)
    private fun action(id: Int, kind: String): JsonNode {
        val execution = "bounded-$id"
        val payload = if (kind == "CAPTURE") mapOf("executionId" to execution, "runId" to "e4", "venueSessionId" to "venue", "instrumentId" to "ACME",
            "buyOrderId" to "buyer-$execution", "sellOrderId" to "seller-$execution", "buyerAccount" to "buyer", "sellerAccount" to "seller",
            "cashAsset" to "USD_NANO", "securityAsset" to "ACME_SHARE", "quantity" to "1", "priceNanos" to "10000000", "dueTick" to "0")
        else mapOf("executionId" to execution, "runId" to "e4", "venueSessionId" to "venue", "instrumentId" to "ACME", "attempt" to "1")
        return json.valueToTree(mapOf("namespace" to "financial-e4", "domain" to "hot", "actionId" to "$execution-$kind", "kind" to kind, "payload" to payload))
    }
    private fun smallHistory(): List<JsonNode> {
        val kernel = FinancialKernel(balances, policy, retainHistory = false)
        val records = mutableListOf(kernel.lastRecord()!!.deepCopy<JsonNode>())
        for ((id, kind) in listOf(0 to "CAPTURE", 0 to "SETTLE", 1 to "CAPTURE")) {
            kernel.execute(action(id, kind), detailed = false); records.add(kernel.lastRecord()!!.deepCopy<JsonNode>())
        }
        return records
    }
    private fun resign(record: ObjectNode): ObjectNode {
        record.remove("checksum"); record.put("checksum", digest(record)); return record
    }
    private fun retainedOwner(reference: FinancialRateProbe.Reference): FinancialCanonicalLeafOwner =
        FinancialRateProbe.Reference::class.java.getDeclaredField("owner").apply { isAccessible = true }.get(reference) as FinancialCanonicalLeafOwner
    private fun assertRetainsOnlyCanonicalStrings(reference: FinancialRateProbe.Reference, expectedLeaves: Int) {
        val store = retainedOwner(reference)
        assertEquals(expectedLeaves, store.retainedLeafCount())
        val categories = FinancialCanonicalLeafOwner::class.java.getDeclaredField("categories").apply { isAccessible = true }.get(store) as Map<*, *>
        assertEquals(13, categories.size)
        categories.values.forEach { entries -> (entries as Map<*, *>).forEach { (key, value) -> assertTrue(key is String && value is String) } }
        val roots = FinancialCanonicalLeafOwner::class.java.getDeclaredField("roots").apply { isAccessible = true }.get(store) as Map<*, *>
        roots.forEach { (key, value) -> assertTrue(key is String && value is String) }
    }

    @Test fun boundedCohortKeepsEveryFactAndMatchesEveryPrefixAndCompleteReplay() {
        val kernel = FinancialKernel(balances, policy, retainHistory = false)
        val oracle = FinancialOracle(balances, policy)
        val reference = newReference()
        val histories = mutableListOf(kernel.lastRecord()!!.deepCopy<JsonNode>())
        reference.accept(histories[0])
        assertEquals(digest(kernel.ownerView()), reference.ownerDigest(), "GENESIS complete owner")
        oracle.assertMatches(reference.businessView(), "GENESIS")
        var inputs = 0
        var checkedOraclePrefixes = 0
        fun execute(id: Int, kind: String) {
            val input = action(id, kind)
            kernel.execute(input, detailed = false)
            oracle.execute(input)
            val record = kernel.lastRecord()!!.deepCopy<JsonNode>()
            reference.accept(record) // Actual Reference checks complete deltas, journals and chain per action.
            histories.add(record); inputs++
            assertEquals(digest(kernel.ownerView()), reference.ownerDigest(), "complete owner prefix=$inputs $kind")
            assertEquals(record["checksum"].asText(), reference.chain, "chain prefix=$inputs")
            assertEquals(inputs + 1L, reference.historyRecords, "record count prefix=$inputs")
            if (inputs <= 40) {
                oracle.assertMatches(reference.businessView(), "independent wide-arithmetic prefix=$inputs")
                checkedOraclePrefixes++
            }
        }
        repeat(1000) { execute(it, "CAPTURE"); execute(it, "SETTLE") }
        repeat(100) { execute(1000 + it, "CAPTURE") }
        assertEquals(2100, inputs); assertEquals(2101, histories.size); assertEquals(2101L, reference.historyRecords)
        oracle.assertMatches(reference.businessView(), "independent wide-arithmetic final 1000settled100pending")
        checkedOraclePrefixes++
        assertEquals(41, checkedOraclePrefixes)
        val finalBusiness = reference.businessView()
        assertEquals(canonical(kernel.businessView()), canonical(finalBusiness))
        assertEquals(1100, finalBusiness["executions"].size())
        assertEquals(2100, finalBusiness["dedup"].size())
        assertEquals(1000, finalBusiness["effects"].size())
        assertEquals(1000, finalBusiness["attempts"].size())
        assertEquals(100, finalBusiness["dueQueue"].size())
        assertEquals(100, finalBusiness["obligations"].count { it["status"].asText() == "PENDING" })
        finalBusiness["executions"].forEach { assertEquals(13, it.size(), "all CAPTURE context fields") }
        finalBusiness["effects"].forEach { assertEquals(4, it.size(), "all gross DvP legs") }
        finalBusiness["dueQueue"].forEach { assertEquals("0", it["tick"].asText()); assertEquals(0, it["priority"].asInt()); assertTrue(it.has("workId")) }
        finalBusiness["dedup"].forEach { assertTrue(it.has("digest") && it.has("context") && it.has("disposition")) }
        assertRetainsOnlyCanonicalStrings(reference, 9712) // 1000×9 settled +100×7 pending +12 genesis balance/version leaves.
        val expectedDigest = reference.ownerDigest(); val expectedChain = reference.chain
        reference.clear()
        assertEquals(0, retainedOwner(reference).retainedLeafCount())
        assertEquals(digest(json.createObjectNode()), reference.ownerDigest())
        val restored = newReference()
        histories.forEach { restored.accept(it) }
        assertEquals(expectedDigest, restored.ownerDigest())
        assertEquals(expectedChain, restored.chain)
        assertEquals(2101L, restored.historyRecords)
        assertEquals(canonical(finalBusiness), canonical(restored.businessView()), "every complete-replay logical business leaf")
        assertRetainsOnlyCanonicalStrings(restored, 9712)
    }

    @Test fun completeDeltaJournalAndHistoryMutantsFailBeforeAnyWrite() {
        val history = smallHistory()
        val mutants = linkedMapOf<String, Pair<String?, (ObjectNode) -> Unit>>(
            "missing delta" to ("independent complete semantic delta mismatch" to { r: ObjectNode -> (r["changes"] as ArrayNode).remove(0); Unit }),
            "extra delta" to ("independent complete semantic delta mismatch" to { r: ObjectNode -> (r["changes"] as ArrayNode).add(r["changes"][0].deepCopy<JsonNode>()); Unit }),
            "wrong before" to ("independent complete semantic delta mismatch" to { r: ObjectNode -> (r["changes"][0] as ObjectNode).put("before", "wrong"); Unit }),
            "wrong after" to ("independent complete semantic delta mismatch" to { r: ObjectNode -> (r["changes"][0] as ObjectNode).put("after", "wrong"); Unit }),
            "omitted context" to ("independent complete semantic delta mismatch" to { r: ObjectNode -> val dedup = r["changes"].first { it["path"][0].asText() == "dedup" }; (dedup["after"] as ObjectNode).remove("context"); Unit }),
            "changed context" to ("independent complete semantic delta mismatch" to { r: ObjectNode -> val dedup = r["changes"].first { it["path"][0].asText() == "dedup" }; (dedup["after"]["context"] as ObjectNode).put("logicalTick", "999"); Unit }),
            "journal leg" to ("independent journal mismatch" to { r: ObjectNode -> (r["journalGroups"]["USD_NANO"][0] as ObjectNode).put("amount", "999"); Unit }),
            "history gap" to (null to { r: ObjectNode -> r.put("sequence", 4); Unit }),
            "prior sequence" to (null to { r: ObjectNode -> r.put("priorSequence", 0); Unit }),
            "prior checksum" to (null to { r: ObjectNode -> r.put("priorChecksum", "wrong"); Unit }),
        )
        for ((label, mutation) in mutants) {
            val reference = newReference(); history.take(2).forEach { reference.accept(it) }
            val beforeDigest = reference.ownerDigest(); val beforeChain = reference.chain
            val mutant = history[2].deepCopy<ObjectNode>(); mutation.second(mutant); resign(mutant)
            val failure = assertFailsWith<IllegalArgumentException>(label) { reference.accept(mutant) }
            mutation.first?.let { assertTrue(failure.message?.contains(it) == true, "$label rejection reason") }
            assertEquals(beforeDigest, reference.ownerDigest(), "$label owner unchanged")
            assertEquals(beforeChain, reference.chain, "$label chain unchanged")
            assertEquals(2L, reference.historyRecords, "$label record count unchanged")
        }
        val checksum = newReference(); history.take(2).forEach { checksum.accept(it) }
        val before = checksum.ownerDigest()
        val failure = assertFailsWith<IllegalArgumentException> { checksum.accept(history[2].deepCopy<ObjectNode>().put("checksum", "tampered")) }
        assertTrue(failure.message?.contains("history checksum") == true)
        assertEquals(before, checksum.ownerDigest()); assertEquals(2L, checksum.historyRecords)
    }

    @Test fun duplicateAndReorderedEconomicActionsFailWithoutAdvancingOwner() {
        val history = smallHistory()
        val duplicate = newReference(); history.take(3).forEach { duplicate.accept(it) }
        val duplicateDigest = duplicate.ownerDigest(); val duplicateChain = duplicate.chain
        val failure = assertFailsWith<IllegalArgumentException> { duplicate.accept(history[1]) }
        assertTrue(failure.message?.contains("duplicate timed economic action") == true)
        assertEquals(duplicateDigest, duplicate.ownerDigest()); assertEquals(duplicateChain, duplicate.chain); assertEquals(3L, duplicate.historyRecords)
        val reordered = newReference(); reordered.accept(history[0])
        val before = reordered.ownerDigest()
        assertFailsWith<IllegalArgumentException> { reordered.accept(history[3]) } // Distinct CAPTURE reaches exact sequence check.
        assertEquals(before, reordered.ownerDigest()); assertEquals(1L, reordered.historyRecords)
        // Legacy SETTLE-before-CAPTURE behavior fails at missing obligation before history checks; preserve and name it.
        assertFailsWith<NullPointerException> { reordered.accept(history[2]) }
        assertEquals(before, reordered.ownerDigest()); assertEquals(1L, reordered.historyRecords)
    }

    @Test fun truncatedExtraAndReorderedCompleteCommittedCutsFailExpectedBoundary() {
        val history = smallHistory()
        val expected = newReference(); history.forEach { expected.accept(it) }
        fun verifyCompleteCut(records: List<JsonNode>) {
            val restored = newReference(); records.forEach { restored.accept(it) }
            require(restored.historyRecords == expected.historyRecords && restored.chain == expected.chain && restored.ownerDigest() == expected.ownerDigest()) {
                "complete result-only restore mismatch"
            }
        }
        verifyCompleteCut(history)
        val truncated = assertFailsWith<IllegalArgumentException> { verifyCompleteCut(history.dropLast(1)) }
        assertTrue(truncated.message?.contains("complete result-only restore mismatch") == true)
        val extra = assertFailsWith<IllegalArgumentException> { verifyCompleteCut(history + listOf(history.last())) }
        assertTrue(extra.message?.contains("duplicate timed economic action") == true)
        assertFailsWith<IllegalArgumentException> { verifyCompleteCut(listOf(history[0], history[3], history[1], history[2])) }
    }

    @Test fun sameCountChangedExecutionDedupOrEffectFactsChangeActualReferenceDigest() {
        val history = smallHistory()
        val kernel = FinancialKernel.replay(history)
        val expectedOwner = kernel.ownerView()
        for (category in listOf("executions", "dedup", "effects")) {
            val reference = newReference(); history.forEach { reference.accept(it) }
            assertEquals(digest(expectedOwner), reference.ownerDigest())
            val owner = retainedOwner(reference)
            val count = owner.retainedLeafCount()
            val key = expectedOwner[category].fieldNames().next()
            val leaf = owner.get(listOf(category, key))
            when (category) {
                "executions" -> (leaf as ObjectNode).put("buyOrderId", "changed-order")
                "dedup" -> (leaf["context"] as ObjectNode).put("logicalTick", "999")
                "effects" -> (leaf[0] as ObjectNode).put("amount", "999")
            }
            owner.write(listOf(category, key), leaf)
            assertEquals(count, owner.retainedLeafCount(), "$category same category-leaf count")
            assertNotEquals(digest(expectedOwner), reference.ownerDigest(), "$category changed fact must affect complete digest")
        }
    }
}
