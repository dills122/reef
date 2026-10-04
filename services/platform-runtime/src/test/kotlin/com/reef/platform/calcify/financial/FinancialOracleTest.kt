package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinancialOracleTest {
    private val mapper = ObjectMapper()
    private val fixtures = mapper.readTree(Files.readString(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json")))

    @Test
    fun `independent BigInteger reference matches all frozen expectations and every business prefix`() {
        var inputs = 0
        assertEquals(20, fixtures["cases"].size())
        fixtures["cases"].forEach { case ->
            val oracle = FinancialOracle(case["genesisBalances"], fixtures["policy"])
            val kernel = FinancialKernel(case["genesisBalances"], fixtures["policy"])
            case["steps"].forEachIndexed { index, step ->
                val label = "${case["id"].asText()} prefix ${index + 1}"
                val expected = oracle.execute(step["input"])
                val actual = kernel.execute(step["input"])
                step["expected"].fields().forEachRemaining { (key, value) ->
                    assertEquals(value, expected[key], "$label oracle frozen $key")
                    assertEquals(value, actual[key], "$label kernel frozen $key")
                }
                oracle.assertMatches(kernel.businessView(), label)
                val recorded = oracle.businessView()["dedup"][step["actionKey"].asText()]
                if (expected["disposition"].asText() != "ACTION_CONFLICT") assertEquals(step["normalizedDigest"].asText(), recorded["digest"].asText(), "$label frozen normalized digest")
                inputs++
            }
        }
        assertEquals(52, inputs)
    }

    @Test
    fun `three frozen xorshift seeds cover 300 traces and each of 19200 input prefixes`() {
        var prefixes = 0
        for ((label, inputs) in generatedTraces()) {
            try {
                compareTrace(inputs, label)
            } catch (failure: AssertionError) {
                val minimal = minimize(inputs)
                val path = Path.of(System.getProperty("java.io.tmpdir"), "reef-sprint1-proof", "oracle", "counterexample-${label.replace(" ", "-").replace("=", "-")}.json")
                Files.createDirectories(path.parent)
                Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("label" to label, "originalLength" to inputs.size, "minimalInputs" to minimal, "failure" to failure.message)))
                throw AssertionError("$label retained deletion-minimal counterexample $path", failure)
            }
            prefixes += inputs.size
        }
        assertEquals(19200, prefixes)
    }

    /** Shared only as immutable input data for independent kernel replay/crash proof. */
    internal fun generatedTraces(): Sequence<Pair<String, List<JsonNode>>> = sequence {
        for (seed in listOf(1, 42, 20261003)) {
            val random = XorShift32(seed)
            repeat(100) { trace -> yield("seed=$seed trace=$trace" to generatedTrace(random, trace)) }
        }
    }

    private fun compareTrace(inputs: List<JsonNode>, label: String) {
        val opening = fixtures["cases"][0]["genesisBalances"]
        val oracle = FinancialOracle(opening, fixtures["policy"])
        val kernel = FinancialKernel(opening, fixtures["policy"])
        inputs.forEachIndexed { index, input ->
            val reference = oracle.execute(input)
            val actual = kernel.execute(input)
            assertEquals(reference["disposition"], actual["disposition"], "$label prefix=$index disposition input=$input")
            oracle.assertMatches(kernel.businessView(), "$label prefix=$index input=$input")
        }
    }

    /** Deterministic deletion reduction: every surviving input is required to reproduce mismatch. */
    private fun minimize(original: List<JsonNode>): List<JsonNode> {
        var current = original
        var index = 0
        while (index < current.size) {
            val candidate = current.filterIndexed { position, _ -> position != index }
            if (runCatching { compareTrace(candidate, "shrink") }.exceptionOrNull() is AssertionError) {
                current = candidate; index = 0
            } else index++
        }
        return current
    }

    private fun generatedTrace(random: XorShift32, trace: Int): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        val template = fixtures["cases"][0]["steps"][0]["input"]
        fun action(id: String, kind: String, payload: Map<String, String>): JsonNode = mapper.valueToTree(mapOf("namespace" to "sprint1", "domain" to "domain-1", "actionId" to id, "kind" to kind, "payload" to payload))
        repeat(8) { block ->
            fun capture(suffix: String): ObjectNode {
                val id = "exec-$trace-$block-$suffix"
                val input = (template as ObjectNode).deepCopy()
                input.put("actionId", "capture-$block-$suffix")
                (input["payload"] as ObjectNode).put("executionId", id).put("quantity", (random.next(12) + 1).toString()).put("priceNanos", ((random.next(20) + 1).toLong() * 1_000_000_000).toString()).put("dueTick", (block + 1).toString())
                return input
            }
            val captureA = capture("a")
            val captureB = capture("b")
            val idA = captureA["payload"]["executionId"].asText()
            val idB = captureB["payload"]["executionId"].asText()
            result.add(captureA); result.add(captureB)
            result.add(action("clock-$block", "CLOCK", mapOf("tick" to (block + 1).toString())))
            val continueA = action("continue-$block-a", "CONTINUE", mapOf("clockAction" to "clock-$block", "workId" to idA))
            val continueB = action("continue-$block-b", "CONTINUE", mapOf("clockAction" to "clock-$block", "workId" to idB))
            result.add(continueA); result.add(continueB)
            when (random.next(5)) {
                0 -> result.add(continueB.deepCopy<JsonNode>())
                1 -> result.add(action("continue-$block-b", "SETTLE", mapOf("executionId" to idB, "attempt" to "2")))
                2 -> result.add(action("fresh-$block", "SETTLE", mapOf("executionId" to idA, "attempt" to "2")))
                3 -> result.add(captureA.deepCopy().put("actionId", "recapture-$block"))
                else -> result.add(action("policy-$block", "ACTIVATE_POLICY", mapOf("policy" to if (random.next(2) == 0) "gross-p1" else "gross-p2")))
            }
            result.add(action("fund-cash-$block", "FUND", mapOf("account" to "buyer", "asset" to "USD_NANO", "amount" to ((random.next(30) + 1).toLong() * 1_000_000_000).toString(), "authority" to "opening-resource-owner")))
            result.add(action("fund-shares-$block", "FUND", mapOf("account" to "seller", "asset" to "ACME_SHARE", "amount" to (random.next(10) + 1).toString(), "authority" to "opening-resource-owner")))
        }
        return result
    }

    private class XorShift32(seed: Int) {
        private var state = seed.also { require(it != 0) }
        fun next(bound: Int): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state.toLong() and 0xffffffffL).rem(bound).toInt()
        }
    }

    @Test
    fun `broker observer refuses omitted final business record and omitted whole history`() {
        val inputs = fixtures["cases"][0]["steps"].mapIndexed { index, step ->
            val input = step["input"].deepCopy<ObjectNode>(); input.put("domain", "A")
            mapper.valueToTree<JsonNode>(mapOf("domain" to "A", "mode" to "EXECUTE", "inputOrdinal" to index, "input" to input))
        }
        val config = mapper.valueToTree<JsonNode>(mapOf("inputs" to inputs, "genesis" to mapOf("A" to mapOf("balances" to fixtures["cases"][0]["genesisBalances"])), "policy" to fixtures["policy"]))
        val kernel = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
        val genesis = kernel.lastRecord()!!
        val outputs = inputs.mapIndexed { ordinal, envelope ->
            val result = kernel.execute(envelope["input"])
            mapper.valueToTree<JsonNode>(mapOf("domain" to "A", "inputOrdinal" to ordinal, "records" to (if (ordinal == 0) listOf(genesis, kernel.lastRecord()!!) else listOf(kernel.lastRecord()!!)), "disposition" to result["disposition"].asText()))
        }
        FinancialBrokerProbe.OutputVerifier(config).also { verifier -> outputs.forEach(verifier::accept); verifier.requirePrefix(2) }
        val omittedFinal = outputs.map { it.deepCopy<ObjectNode>() }
        omittedFinal.last().set<JsonNode>("records", mapper.createArrayNode())
        assertFailsWith<IllegalArgumentException> { FinancialBrokerProbe.OutputVerifier(config).also { verifier -> omittedFinal.forEach(verifier::accept) } }
        val omittedAll = outputs.map { it.deepCopy<ObjectNode>().apply { set<JsonNode>("records", mapper.createArrayNode()) } }
        assertFailsWith<IllegalArgumentException> { FinancialBrokerProbe.OutputVerifier(config).also { verifier -> omittedAll.forEach(verifier::accept) } }
    }

    @Test
    fun `duplicate discharge and unbalanced one leg are rejected by independent reference`() {
        val case = fixtures["cases"][0]
        val oracle = FinancialOracle(case["genesisBalances"], fixtures["policy"])
        val kernel = FinancialKernel(case["genesisBalances"], fixtures["policy"])
        case["steps"].forEach { oracle.execute(it["input"]); kernel.execute(it["input"]) }
        val correct = kernel.businessView()
        oracle.assertMatches(correct, "control")
        val duplicate = (correct as ObjectNode).deepCopy()
        val cash = duplicate["balances"] as ObjectNode
        cash.put("buyerCash", "0"); cash.put("sellerCash", "100000000000")
        cash.put("buyerShares", "10"); cash.put("sellerShares", "0")
        val duplicateFailure = assertFailsWith<AssertionError> { oracle.assertMatches(duplicate, "duplicate-discharge") }
        assertTrue(duplicateFailure.message!!.contains("reference="), "balanced duplicate discharge must fail economic parity")
        val oneLeg = (correct as ObjectNode).deepCopy()
        (oneLeg["balances"] as ObjectNode).put("buyerCash", "49999999999")
        val oneLegFailure = assertFailsWith<AssertionError> { oracle.assertMatches(oneLeg, "one-leg-mutation") }
        assertTrue(oneLegFailure.message!!.contains("unbalanced Cash"), "one-leg debit must fail cash conservation")
    }
}
