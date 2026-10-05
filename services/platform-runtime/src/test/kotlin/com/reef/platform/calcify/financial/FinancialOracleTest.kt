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

    @Test
    fun `malformed identical retry preserves first context and all business state`() {
        val opening = fixtures["cases"][0]["genesisBalances"]
        val oracle = FinancialOracle(opening, fixtures["policy"])
        val kernel = FinancialKernel(opening, fixtures["policy"])
        val malformed = fixtures["cases"][0]["steps"][0]["input"].deepCopy<ObjectNode>()
        (malformed["payload"] as ObjectNode).put("quantity", "NaN")
        assertEquals("INVALID_INPUT", oracle.execute(malformed)["disposition"].asText())
        assertEquals("INVALID_INPUT", kernel.execute(malformed)["disposition"].asText())
        oracle.assertMatches(kernel.businessView(), "malformed first decision")
        val activate = mapper.valueToTree<JsonNode>(mapOf("namespace" to "sprint1", "domain" to malformed["domain"].asText(), "actionId" to "change-policy", "kind" to "ACTIVATE_POLICY", "payload" to mapOf("policy" to "gross-p2")))
        assertEquals("POLICY_ACTIVATED", oracle.execute(activate)["disposition"].asText())
        assertEquals("POLICY_ACTIVATED", kernel.execute(activate)["disposition"].asText())
        oracle.assertMatches(kernel.businessView(), "policy activation before retry")
        val referenceBefore = oracle.businessView()
        val kernelBefore = kernel.businessView()
        val historyBefore = kernel.historySequence()
        val reference = oracle.execute(malformed)
        val actual = kernel.execute(malformed)
        for (result in listOf(reference, actual)) {
            assertEquals("PRIOR_RESULT", result["disposition"].asText())
            assertEquals("gross-p1", result["selectedPolicy"].asText())
            assertEquals(true, result["noNewAttempt"].asBoolean())
        }
        assertEquals(referenceBefore, oracle.businessView(), "reference retry must preserve full state and context")
        assertEquals(kernelBefore, kernel.businessView(), "kernel retry must preserve full state and context")
        assertEquals(historyBefore, kernel.historySequence())
        oracle.assertMatches(kernel.businessView(), "malformed retry")
    }

    @Test
    fun `funding checks exact credit and opening debit boundaries for both assets`() {
        for ((asset, suffix) in listOf("USD_NANO" to "Cash", "ACME_SHARE" to "Shares")) {
            for (openingBoundary in listOf(true, false)) {
                for (amount in listOf("1", "2")) {
                    val genesis = mapper.valueToTree<JsonNode>(mapOf("buyerCash" to "0", "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to "0")) as ObjectNode
                    genesis.put("buyer$suffix", if (openingBoundary) Long.MAX_VALUE.toString() else (Long.MAX_VALUE - 1).toString())
                    val oracle = FinancialOracle(genesis, fixtures["policy"])
                    val kernel = FinancialKernel(genesis, fixtures["policy"])
                    val input = mapper.valueToTree<JsonNode>(mapOf("namespace" to "sprint1", "domain" to "domain-1", "actionId" to "fund-$asset-$openingBoundary-$amount", "kind" to "FUND", "payload" to mapOf("account" to if (openingBoundary) "seller" else "buyer", "asset" to asset, "amount" to amount, "authority" to "opening-resource-owner")))
                    val label = "$asset openingBoundary=$openingBoundary amount=$amount"
                    val before = oracle.businessView()
                    val expected = if (amount == "1") "FUNDED" else "BALANCE_OVERFLOW"
                    assertEquals(expected, oracle.execute(input)["disposition"].asText(), "$label reference")
                    assertEquals(expected, kernel.execute(input)["disposition"].asText(), "$label kernel")
                    oracle.assertMatches(kernel.businessView(), label)
                    val after = oracle.businessView()
                    if (amount == "1") {
                        assertEquals(if (openingBoundary) Long.MIN_VALUE.toString() else (-Long.MAX_VALUE).toString(), after["balances"]["opening$suffix"].asText(), label)
                        assertEquals(if (openingBoundary) "1" else Long.MAX_VALUE.toString(), after["balances"][if (openingBoundary) "seller$suffix" else "buyer$suffix"].asText(), label)
                    } else {
                        for (field in listOf("balances", "versions", "effects", "obligations")) assertEquals(before[field], after[field], "$label atomic rejection $field")
                    }
                }
            }
        }
    }

    @Test
    fun `synthetic capture accepts zero and positive price and refuses negative price`() {
        val opening = fixtures["cases"][0]["genesisBalances"]
        for (price in listOf("0", "-1", "1")) {
            val oracle = FinancialOracle(opening, fixtures["policy"])
            val kernel = FinancialKernel(opening, fixtures["policy"])
            val capture = fixtures["cases"][0]["steps"][0]["input"].deepCopy<ObjectNode>()
            (capture["payload"] as ObjectNode).put("quantity", "5").put("priceNanos", price)
            val expected = if (price == "-1") "INVALID_INPUT" else "CAPTURED"
            assertEquals(expected, oracle.execute(capture)["disposition"].asText(), "price=$price reference")
            assertEquals(expected, kernel.execute(capture)["disposition"].asText(), "price=$price kernel")
            oracle.assertMatches(kernel.businessView(), "price=$price capture")
            if (price == "-1") {
                assertEquals(0, oracle.businessView()["obligations"].size())
            } else {
                val obligation = oracle.businessView()["obligations"].elements().next()
                assertEquals(if (price == "0") "0" else "5", obligation["cashResidual"].asText())
                val settle = fixtures["cases"][0]["steps"][1]["input"]
                assertEquals("SETTLED", oracle.execute(settle)["disposition"].asText())
                assertEquals("SETTLED", kernel.execute(settle)["disposition"].asText())
                oracle.assertMatches(kernel.businessView(), "price=$price settlement")
                assertEquals("5", oracle.businessView()["balances"]["buyerShares"].asText())
                if (price == "0") {
                    assertEquals(opening["buyerCash"], oracle.businessView()["balances"]["buyerCash"])
                    assertEquals(opening["sellerCash"], oracle.businessView()["balances"]["sellerCash"])
                }
            }
        }
    }

    @Test
    fun `consumed signed64 bounds reject before effects and preserve rejection retries`() {
        val maximum = Long.MAX_VALUE.toString()
        val beyond = "9223372036854775808"
        val below = "-9223372036854775809"
        val captureTemplate = fixtures["cases"][0]["steps"][0]["input"]
        data class Boundary(val kind: String, val field: String, val value: String, val expected: String)
        val boundaries = listOf(
            Boundary("CAPTURE", "dueTick", maximum, "CAPTURED"), Boundary("CAPTURE", "dueTick", beyond, "AMOUNT_OVERFLOW"), Boundary("CAPTURE", "dueTick", "-1", "INVALID_INPUT"),
            Boundary("SETTLE", "attempt", maximum, "SETTLED"), Boundary("SETTLE", "attempt", beyond, "AMOUNT_OVERFLOW"), Boundary("SETTLE", "attempt", "0", "INVALID_INPUT"), Boundary("SETTLE", "attempt", "-1", "INVALID_INPUT"),
            Boundary("FUND", "amount", maximum, "FUNDED"), Boundary("FUND", "amount", beyond, "AMOUNT_OVERFLOW"), Boundary("FUND", "amount", "0", "INVALID_INPUT"), Boundary("FUND", "amount", "-1", "INVALID_INPUT"),
            Boundary("CLOCK", "tick", maximum, "CLOCK_ADVANCED"), Boundary("CLOCK", "tick", beyond, "AMOUNT_OVERFLOW"), Boundary("CLOCK", "tick", Long.MIN_VALUE.toString(), "INVALID_INPUT"), Boundary("CLOCK", "tick", below, "AMOUNT_OVERFLOW"), Boundary("CLOCK", "tick", "-1", "INVALID_INPUT")
        )
        for (boundary in boundaries) {
            val genesis = if (boundary.kind == "FUND") mapper.valueToTree<JsonNode>(mapOf("buyerCash" to "0", "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to "0")) else fixtures["cases"][0]["genesisBalances"]
            val oracle = FinancialOracle(genesis, fixtures["policy"])
            val kernel = FinancialKernel(genesis, fixtures["policy"])
            if (boundary.kind == "SETTLE") {
                oracle.execute(captureTemplate); kernel.execute(captureTemplate)
                oracle.assertMatches(kernel.businessView(), "settle boundary setup")
            }
            val payload = when (boundary.kind) {
                "CAPTURE" -> captureTemplate["payload"].deepCopy<ObjectNode>()
                "SETTLE" -> fixtures["cases"][0]["steps"][1]["input"]["payload"].deepCopy<ObjectNode>()
                "FUND" -> mapper.valueToTree<ObjectNode>(mapOf("account" to "buyer", "asset" to "USD_NANO", "authority" to "opening-resource-owner"))
                else -> mapper.createObjectNode()
            }.put(boundary.field, boundary.value)
            val input = mapper.valueToTree<JsonNode>(mapOf("namespace" to "sprint1", "domain" to "domain-1", "actionId" to "boundary", "kind" to boundary.kind, "payload" to payload))
            val before = oracle.businessView()
            assertEquals(boundary.expected, oracle.execute(input)["disposition"].asText(), "$boundary reference")
            assertEquals(boundary.expected, kernel.execute(input)["disposition"].asText(), "$boundary kernel")
            oracle.assertMatches(kernel.businessView(), boundary.toString())
            if (boundary.expected in setOf("AMOUNT_OVERFLOW", "INVALID_INPUT")) {
                for (field in listOf("balances", "effects", "versions", "obligations", "attempts", "dueQueue", "logicalTick", "continuation")) assertEquals(before[field], oracle.businessView()[field], "$boundary no effect $field")
                val rejected = oracle.businessView()
                val history = kernel.historySequence()
                assertEquals("PRIOR_RESULT", oracle.execute(input)["disposition"].asText())
                assertEquals("PRIOR_RESULT", kernel.execute(input)["disposition"].asText())
                assertEquals(rejected, oracle.businessView(), "$boundary retry full state")
                assertEquals(history, kernel.historySequence())
                oracle.assertMatches(kernel.businessView(), "$boundary retry")
            }
        }
    }

    @Test
    fun `missing empty null and numeric envelope identities reject before missing policy`() {
        val template = fixtures["cases"][0]["steps"][0]["input"]
        val invalid = mutableListOf<ObjectNode>()
        for (field in listOf("namespace", "domain", "actionId")) {
            invalid.add(template.deepCopy<ObjectNode>().apply { remove(field) })
            invalid.add(template.deepCopy<ObjectNode>().put(field, ""))
            invalid.add(template.deepCopy<ObjectNode>().putNull(field))
            invalid.add(template.deepCopy<ObjectNode>().put(field, 7))
        }
        invalid.add(template.deepCopy<ObjectNode>().put("requestedPolicy", "missing-policy").apply { (get("payload") as ObjectNode).put("quantity", "NaN") })
        invalid.add(template.deepCopy<ObjectNode>().put("requestedPolicy", "missing-policy").apply { remove("payload") })
        for ((index, input) in invalid.withIndex()) {
            val oracle = FinancialOracle(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
            val kernel = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
            val before = oracle.businessView()
            assertEquals("INVALID_INPUT", oracle.execute(input)["disposition"].asText(), "identity=$index reference")
            assertEquals("INVALID_INPUT", kernel.execute(input)["disposition"].asText(), "identity=$index kernel")
            oracle.assertMatches(kernel.businessView(), "identity=$index")
            for (field in listOf("balances", "effects", "versions", "executions", "obligations", "dueQueue")) assertEquals(before[field], oracle.businessView()[field], "identity=$index no effect $field")
            val rejected = oracle.businessView()
            val history = kernel.historySequence()
            assertEquals("PRIOR_RESULT", oracle.execute(input)["disposition"].asText())
            assertEquals("PRIOR_RESULT", kernel.execute(input)["disposition"].asText())
            assertEquals(rejected, oracle.businessView())
            assertEquals(history, kernel.historySequence())
            oracle.assertMatches(kernel.businessView(), "identity=$index retry context")
        }
        val oracle = FinancialOracle(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
        val kernel = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
        oracle.execute(template); kernel.execute(template)
        val conflict = template.deepCopy<ObjectNode>().put("requestedPolicy", "missing-policy").apply { (get("payload") as ObjectNode).put("quantity", "NaN") }
        assertEquals("ACTION_CONFLICT", oracle.execute(conflict)["disposition"].asText())
        assertEquals("ACTION_CONFLICT", kernel.execute(conflict)["disposition"].asText())
        oracle.assertMatches(kernel.businessView(), "conflict precedes malformed input and missing policy")
    }

    @Test
    fun `typed envelope identities never alias valid text identities in either order`() {
        val template = fixtures["cases"][0]["steps"][0]["input"]
        for (field in listOf("namespace", "domain", "actionId")) {
            for (numeric in listOf(true, false)) {
                for (invalidFirst in listOf(true, false)) {
                    val invalid = template.deepCopy<ObjectNode>().apply { if (numeric) put(field, 7) else putNull(field) }
                    val valid = template.deepCopy<ObjectNode>().put(field, if (numeric) "7" else "null")
                    val oracle = FinancialOracle(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
                    val kernel = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
                    for ((input, expected) in if (invalidFirst) listOf(invalid to "INVALID_INPUT", valid to "CAPTURED") else listOf(valid to "CAPTURED", invalid to "INVALID_INPUT")) {
                        assertEquals(expected, oracle.execute(input)["disposition"].asText(), "$field numeric=$numeric invalidFirst=$invalidFirst reference")
                        assertEquals(expected, kernel.execute(input)["disposition"].asText(), "$field numeric=$numeric invalidFirst=$invalidFirst kernel")
                        oracle.assertMatches(kernel.businessView(), "$field numeric=$numeric invalidFirst=$invalidFirst")
                    }
                    assertEquals(2, oracle.businessView()["dedup"].size(), "distinct typed identities")
                }
            }
        }
    }

    @Test
    fun `broker reference stages malformed numeric missing payload and missing identity without losing authority`() {
        val capture = fixtures["cases"][0]["steps"][0]["input"].deepCopy<ObjectNode>().put("domain", "A")
        val clock = mapper.valueToTree<JsonNode>(mapOf("namespace" to "sprint1", "domain" to "A", "actionId" to "clock", "kind" to "CLOCK", "payload" to mapOf("tick" to "1")))
        val malformed = capture.deepCopy().put("actionId", "bad-numeric").apply { (get("payload") as ObjectNode).put("quantity", "NaN") }
        val missingPayload = capture.deepCopy().put("actionId", "missing-payload").apply { remove("payload") }
        val missingIdentity = capture.deepCopy().apply { remove("actionId") }
        val missingKind = capture.deepCopy().put("actionId", "missing-kind").apply { remove("kind") }
        val malformedContinue = capture.deepCopy().put("actionId", "bad-continue").put("kind", "CONTINUE").apply { remove("payload") }
        val missingWork = capture.deepCopy().put("actionId", "missing-work").put("kind", "CONTINUE").apply { set<JsonNode>("payload", mapper.valueToTree(mapOf("clockAction" to "clock"))) }
        val continuation = mapper.valueToTree<JsonNode>(mapOf("namespace" to "sprint1", "domain" to "A", "actionId" to "continue", "kind" to "CONTINUE", "payload" to mapOf("clockAction" to "clock", "workId" to capture["payload"]["executionId"].asText())))
        val dispatch = listOf("EXECUTE" to capture, "EXECUTE" to clock, "EXECUTE" to malformed, "EXECUTE" to malformed, "EXECUTE" to missingPayload, "EXECUTE" to missingIdentity, "EXECUTE" to missingKind, "STAGE" to malformedContinue, "STAGE" to missingWork, "EXECUTE" to continuation, "DRAIN" to mapper.nullNode(), "DRAIN" to mapper.nullNode(), "DRAIN" to mapper.nullNode(), "DRAIN" to mapper.nullNode(), "DRAIN" to mapper.nullNode(), "DRAIN" to mapper.nullNode())
        val envelopes = dispatch.mapIndexed { index, (mode, input) -> mapper.valueToTree<JsonNode>(mapOf("domain" to "A", "inputOrdinal" to index, "mode" to mode, "input" to input)) }
        val config = mapper.valueToTree<JsonNode>(mapOf("inputs" to envelopes, "genesis" to mapOf("A" to mapOf("balances" to fixtures["cases"][0]["genesisBalances"])), "policy" to fixtures["policy"]))
        val kernel = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
        var emitted = 0
        val outputs = dispatch.mapIndexed { index, (mode, input) ->
            val result = when (mode) { "DRAIN" -> kernel.drainStaged(1).single(); "STAGE" -> kernel.stage(input); else -> kernel.execute(input) }
            val records = kernel.history().drop(emitted)
            emitted += records.size
            mapper.valueToTree<JsonNode>(mapOf("domain" to "A", "inputOrdinal" to index, "records" to records, "disposition" to result["disposition"].asText()))
        }
        assertEquals(listOf("CAPTURED", "CLOCK_ADVANCED", "STAGED", "STAGED", "STAGED", "STAGED", "STAGED", "STAGED", "STAGED", "SETTLED", "INVALID_INPUT", "INVALID_INPUT", "INVALID_INPUT", "INVALID_INPUT", "INVALID_INPUT", "INVALID_INPUT"), outputs.map { it["disposition"].asText() })
        FinancialBrokerProbe.OutputVerifier(config).also { verifier -> outputs.forEach(verifier::accept); verifier.requirePrefix(outputs.size) }
        val omitted = outputs.map { it.deepCopy<ObjectNode>() }
        omitted[2].set<JsonNode>("records", mapper.createArrayNode())
        assertFailsWith<IllegalArgumentException> { FinancialBrokerProbe.OutputVerifier(config).also { verifier -> omitted.forEach(verifier::accept) } }
        val changedConfig = config.deepCopy<ObjectNode>()
        (changedConfig["inputs"][2]["input"]["payload"] as ObjectNode).put("quantity", "Infinity")
        assertFailsWith<IllegalArgumentException> { FinancialBrokerProbe.OutputVerifier(changedConfig).also { verifier -> outputs.forEach(verifier::accept) } }
    }

    @Test
    fun `execution resolution requires textual locators and complete scope or unique unscoped identity`() {
        val template = fixtures["cases"][0]["steps"][0]["input"]
        for (kind in listOf("SETTLE", "CONTINUE")) {
            for (variant in listOf("numeric-locator", "partial-one", "partial-two", "complete-wrong", "complete-correct", "partial-ambiguous", "numeric-clock")) {
                if (kind == "SETTLE" && variant == "numeric-clock") continue
                val oracle = FinancialOracle(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
                val kernel = FinancialKernel(fixtures["cases"][0]["genesisBalances"], fixtures["policy"])
                fun apply(input: JsonNode, expected: String) {
                    assertEquals(expected, oracle.execute(input)["disposition"].asText(), "$kind $variant reference")
                    assertEquals(expected, kernel.execute(input)["disposition"].asText(), "$kind $variant kernel")
                    oracle.assertMatches(kernel.businessView(), "$kind $variant input=$input")
                }
                val capture = template.deepCopy<ObjectNode>().apply { (get("payload") as ObjectNode).put("executionId", "17") }
                apply(capture, "CAPTURED")
                if (variant == "partial-ambiguous") {
                    val other = capture.deepCopy().put("actionId", "capture-other").apply { (get("payload") as ObjectNode).put("runId", "other-run") }
                    apply(other, "CAPTURED")
                }
                if (kind == "CONTINUE") apply(mapper.valueToTree(mapOf("namespace" to "sprint1", "domain" to "domain-1", "actionId" to "17", "kind" to "CLOCK", "payload" to mapOf("tick" to "1"))), "CLOCK_ADVANCED")
                val payload = mapper.createObjectNode().put(if (kind == "CONTINUE") "workId" else "executionId", "17")
                if (kind == "CONTINUE") payload.put("clockAction", "17") else payload.put("attempt", "1")
                when (variant) {
                    "numeric-locator" -> payload.put(if (kind == "CONTINUE") "workId" else "executionId", 17)
                    "numeric-clock" -> payload.put("clockAction", 17)
                    "partial-one" -> payload.put("runId", "wrong-run")
                    "partial-two" -> payload.put("runId", "wrong-run").put("venueSessionId", "wrong-session")
                    "partial-ambiguous" -> payload.put("runId", capture["payload"]["runId"].asText())
                    "complete-wrong", "complete-correct" -> {
                        for (field in listOf("runId", "venueSessionId", "instrumentId")) payload.put(field, capture["payload"][field].asText())
                        if (variant == "complete-wrong") payload.put("runId", "wrong-run")
                    }
                }
                val input = mapper.valueToTree<JsonNode>(mapOf("namespace" to "sprint1", "domain" to "domain-1", "actionId" to "resolve", "kind" to kind, "payload" to payload))
                val expected = if (variant in setOf("numeric-locator", "numeric-clock", "complete-wrong", "partial-ambiguous")) "INVALID_INPUT" else "SETTLED"
                val before = oracle.businessView()
                apply(input, expected)
                if (expected == "INVALID_INPUT") {
                    for (field in listOf("balances", "effects", "versions", "obligations", "attempts", "dueQueue", "continuation")) assertEquals(before[field], oracle.businessView()[field], "$kind $variant no effect $field")
                    val rejected = oracle.businessView()
                    val history = kernel.historySequence()
                    apply(input, "PRIOR_RESULT")
                    assertEquals(rejected, oracle.businessView())
                    assertEquals(history, kernel.historySequence())
                }
            }
        }
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
