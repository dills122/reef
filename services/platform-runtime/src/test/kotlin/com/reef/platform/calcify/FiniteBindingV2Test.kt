package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Binding v2 against the independent Python reference bytes; v1 stays untouched and never confuses with v2. */
class FiniteBindingV2Test {
    private val root = Path.of("../../contracts/calcify")
    private fun raw(name: String) = Files.readAllBytes(root.resolve(name))
    private fun fixture(): ObjectNode = FiniteBindingContract.strictObject(raw("finite-binding-v2.json"), 16384)
    private fun parse(node: ObjectNode) = FiniteBindingContract.parseOpeningV2(FiniteBindingContract.encode(node))
    private fun topics(n: ObjectNode) = n["topics"] as ArrayNode
    private fun mutated(edit: (ObjectNode) -> Unit): ObjectNode = fixture().also(edit)

    @Test fun canonicalBytesAndDigestMatchTheIndependentReference() {
        val o = FiniteBindingContract.parseOpeningV2(raw("finite-binding-v2.json"))
        assertEquals(String(raw("finite-binding-v2.hex")).trim(), HexFormat.of().formatHex(o.canonicalBytes()))
        val manifest = FiniteBindingContract.strictObject(raw("finite-binding-v2-manifest.json"), 4096)
        assertEquals(manifest["digest"].textValue(), o.digest)
        assertEquals(manifest["bytes"].intValue(), o.canonicalBytes().size)
        assertEquals(manifest["financeConfigDigest"].textValue(), o.financeConfigDigest)
    }

    @Test fun captureBindingCarriesTheSingleV2DigestAndOtherwiseMatchesTheV1Mapping() {
        val v2 = FiniteBindingContract.parseOpeningV2(raw("finite-binding-v2.json"))
        val v1 = FiniteBindingContract.parseOpening(raw("finite-binding-v1.json"))
        val binding = v2.toCaptureBinding()
        assertEquals(v2.digest, binding.digest)
        assertNotEquals(v1.digest, binding.digest)
        assertEquals(v1.toCaptureBinding().copy(digest = v2.digest, generation = v2.generation), binding, "every other capture-lane field equals the v1 mapping (a v2 run uses a fresh source generation)")
    }

    @Test fun processorConfigIsDerivedFromTheOpeningWithOneDigestAndTheCheckedFinanceConfig() {
        val o = FiniteBindingContract.parseOpeningV2(raw("finite-binding-v2.json"))
        val config = com.reef.platform.calcify.finance.FinanceProcessorConfig.fromOpening(o)
        assertEquals(o.digest, config.bindingDigest); assertEquals(o.digest, config.captureBinding.digest)
        assertEquals(o.financeConfigDigest, config.finance.digest(), "the derived FinanceConfig is the one the opening committed to")
        assertEquals(o.topic("CAPTURE").name, config.captureTopic); assertEquals(o.topic("CONTROL").name, config.controlTopic); assertEquals(o.topic("FINANCE_DECISIONS").name, config.decisionsTopic)
    }

    @Test fun v1StaysFrozenAndTheTwoSchemasNeverConfuse() {
        val v1 = FiniteBindingContract.parseOpening(raw("finite-binding-v1.json"))
        assertEquals("1fe1749dba2b35803eabb41fc1f9106477147a2ad9966ecf2087eb5f78dc2c8a", v1.digest)
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseOpeningV2(raw("finite-binding-v1.json")) }
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseOpening(raw("finite-binding-v2.json")) }
    }

    @Test fun everyStructuralViolationIsRefused() {
        val cases: Map<String, (ObjectNode) -> Unit> = mapOf(
            "v1 schema" to { n -> n.put("schema", "calcify-finite-binding-v1") },
            "missing finance field" to { n -> n.remove("financeApplicationId") },
            "unknown field" to { n -> n.put("surprise", "x") },
            "five topics" to { n -> topics(n).remove(7); topics(n).remove(6); topics(n).remove(5) },
            "role order" to { n -> val a = topics(n); val x = a[5]; a.set(5, a[6]); a.set(6, x) },
            "duplicate topic name" to { n -> (topics(n)[6] as ObjectNode).put("name", topics(n)[5]["name"].textValue()) },
            "duplicate topic id" to { n -> (topics(n)[6] as ObjectNode).put("topicId", topics(n)[5]["topicId"].textValue()) },
            "finance changelog name" to { n -> (topics(n)[7] as ObjectNode).put("name", "other-changelog") },
            "config digest" to { n -> n.put("financeConfigDigest", "0".repeat(64)) },
            "budget captures" to { n -> (n["financeBudget"] as ObjectNode).put("captures", "127") },
            "nonzero genesis offset" to { n -> n.put("initialFinanceControlNextOffset", "1") },
            "opening is a party" to { n -> n.put("openingAccountId", "buyer-account") },
            "non-ascii opening" to { n -> n.put("openingAccountId", "ópening") },
            "assets equal" to { n -> n.put("baseAssetId", n["quoteCurrency"].textValue()) },
            "store name" to { n -> n.put("financeStoreName", "other") },
            "state version" to { n -> n.put("financeStateVersion", "calcify-finite-finance-state-v2") },
            "finance app prefixed by capture app" to { n -> n.put("financeApplicationId", "p3-fixture-capture-x") },
            "finance app is a prefix" to { n -> n.put("financeApplicationId", "p3-fixture") },
            "control id prefix-related to finance app" to { n -> n.put("controlTransactionalId", "p3-fixture-finance-writer") },
            "control id equals group" to { n -> n.put("controlTransactionalId", n["commandConsumerGroup"].textValue()) },
        )
        for ((name, edit) in cases) assertFailsWith<IllegalArgumentException>(name) { parse(mutated(edit)) }
    }

    @Test fun everyFieldChangesTheDigest() {
        val base = parse(fixture()).digest
        val edits: Map<String, (ObjectNode) -> Unit> = mapOf(
            "controlTransactionalId" to { n -> n.put("controlTransactionalId", n["controlTransactionalId"].textValue() + "x") },
            "control topic id" to { n -> (topics(n)[5] as ObjectNode).put("topicId", "AAAAAAAAAAAAAAAAAAAACQ") },
            "decisions topic name" to { n -> (topics(n)[6] as ObjectNode).put("name", topics(n)[6]["name"].textValue() + "x") },
            "source generation" to { n -> n.put("sourceGeneration", 3) },
        )
        for ((name, edit) in edits) assertNotEquals(base, parse(mutated(edit)).digest, name)
    }

    @Test fun v2FixtureFitsTheV1SizeCaps() {
        assertTrue(raw("finite-binding-v2.json").size <= 16384)
        assertTrue(FiniteBindingContract.parseOpeningV2(raw("finite-binding-v2.json")).canonicalBytes().size <= 16384)
    }
}
