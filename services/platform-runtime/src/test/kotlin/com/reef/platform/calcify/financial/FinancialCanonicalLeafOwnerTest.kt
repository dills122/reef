package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FinancialCanonicalLeafOwnerTest {
    private val json = ObjectMapper()
    private val fixtures = json.readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").toFile())
    private fun genesis(): ObjectNode = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"]).ownerView() as ObjectNode
    private fun canonical(n: JsonNode): String = when {
        n.isObject -> n.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(n[it]) }
        n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
        else -> n.toString()
    }
    private fun digest(n: JsonNode) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(n).toByteArray(Charsets.UTF_8)))
    private fun reversedObjects(n: JsonNode): JsonNode = when {
        n.isObject -> json.createObjectNode().apply { n.fieldNames().asSequence().toList().reversed().forEach { set<JsonNode>(it, reversedObjects(n[it])) } }
        n.isArray -> json.createArrayNode().apply { n.forEach { add(reversedObjects(it)) } }
        else -> n.deepCopy<JsonNode>()
    }

    @Test fun canonicalDigestPreservesGenesisEmptyCategoriesAndExactRootTypes() {
        val expected = genesis()
        val owner = FinancialCanonicalLeafOwner(expected)
        assertEquals(digest(expected), owner.ownerDigest())
        assertEquals(canonical(expected), canonical(owner.materialize()))
        listOf("executions", "obligations", "attempts", "dedup", "effects", "dueQueue", "stagedInputs").forEach {
            assertTrue(owner.materialize().has(it)); assertEquals(0, owner.materialize()[it].size())
        }
        assertTrue(owner.get(listOf("continuation")).isNull)
        assertTrue(owner.get(listOf("historySeq")).isIntegralNumber)
        val integralDigest = owner.ownerDigest()
        owner.write(listOf("historySeq"), json.nodeFactory.textNode(expected["historySeq"].asText()))
        assertNotEquals(integralDigest, owner.ownerDigest())
        assertTrue(owner.get(listOf("historySeq")).isTextual)
        owner.write(listOf("policy"), json.nodeFactory.textNode("not-an-object"))
        assertTrue(owner.get(listOf("policy")).isTextual)
    }

    @Test fun escapedKeysNestedNullsAndInsertionOrdersMatchCanonicalLegacyBytes() {
        val expected = genesis()
        val leaf = json.readTree("""{"z":null,"a":[1,"1",{"quote\"\\\n":"é😀"}],"boolean":true}""")
        val escaped = "escaped\"\\\n\t-é😀"
        (expected["executions"] as ObjectNode).set<JsonNode>(escaped, leaf)
        (expected["executions"] as ObjectNode).set<JsonNode>("a", leaf.deepCopy<JsonNode>())
        (expected["executions"] as ObjectNode).set<JsonNode>("z", leaf.deepCopy<JsonNode>())
        val forward = FinancialCanonicalLeafOwner(expected)
        val reverse = FinancialCanonicalLeafOwner(reversedObjects(expected))
        assertEquals(digest(expected), forward.ownerDigest())
        assertEquals(digest(expected), reverse.ownerDigest())
        assertEquals(canonical(expected), canonical(reverse.materialize()))
        assertTrue(forward.get(listOf("executions", escaped))["z"].isNull)
        assertEquals("é😀", forward.get(listOf("executions", escaped))["a"][2]["quote\"\\\n"].asText())
    }

    @Test fun constructorWriteReadAndMaterializedCopiesCannotAliasRetainedFacts() {
        val source = genesis()
        val owner = FinancialCanonicalLeafOwner(source)
        val genesisDigest = owner.ownerDigest()
        source.removeAll()
        assertEquals(genesisDigest, owner.ownerDigest())
        val leaf = json.readTree("""{"executionId":"isolated","nullable":null,"nested":{"status":"PENDING"}}""") as ObjectNode
        owner.write(listOf("executions", "isolated"), leaf)
        val writtenDigest = owner.ownerDigest()
        leaf.put("executionId", "changed-source")
        (leaf["nested"] as ObjectNode).put("status", "CHANGED")
        assertEquals(writtenDigest, owner.ownerDigest())
        val read = owner.get(listOf("executions", "isolated")) as ObjectNode
        read.put("executionId", "changed-read")
        (read["nested"] as ObjectNode).removeAll()
        assertEquals(writtenDigest, owner.ownerDigest())
        owner.materialize().removeAll()
        assertEquals(writtenDigest, owner.ownerDigest())
        assertEquals("isolated", owner.get(listOf("executions", "isolated"))["executionId"].asText())
    }

    @Test fun leafNullDeletesRootNullRemainsAndClearDropsEveryRetainedLeaf() {
        val owner = FinancialCanonicalLeafOwner(genesis())
        val initialCount = owner.retainedLeafCount()
        owner.write(listOf("dueQueue", "pending"), json.readTree("""{"tick":"0","priority":0,"workId":"pending"}"""))
        assertTrue(owner.contains("dueQueue", "pending"))
        owner.write(listOf("dueQueue", "pending"), json.nullNode())
        assertFalse(owner.contains("dueQueue", "pending"))
        assertEquals(initialCount, owner.retainedLeafCount())
        owner.write(listOf("continuation"), json.nullNode())
        assertTrue(owner.materialize().has("continuation"))
        assertTrue(owner.get(listOf("continuation")).isNull)
        assertFailsWith<IllegalArgumentException> { owner.get(listOf("executions")) }
        assertFailsWith<IllegalArgumentException> { owner.write(listOf("executions"), json.createObjectNode()) }
        owner.clear()
        assertEquals(0, owner.retainedLeafCount())
        assertEquals("{}", canonical(owner.materialize()))
        assertEquals(digest(json.createObjectNode()), owner.ownerDigest())
    }
}
