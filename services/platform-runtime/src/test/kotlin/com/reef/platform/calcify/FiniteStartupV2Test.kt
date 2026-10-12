package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Startup witness v2 against the same golden fixture the Go parser reads; v1 stays strict. */
class FiniteStartupV2Test {
    private val root = Path.of("../../contracts/calcify")
    private fun raw(name: String) = Files.readAllBytes(root.resolve(name))
    private fun fixture(): ObjectNode = FiniteBindingContract.strictObject(raw("finite-startup-v2.json"), FiniteBindingContract.WIRE_BYTES)
    private val digest get() = FiniteBindingContract.strictObject(raw("finite-binding-v2-manifest.json"), 4096)["digest"].textValue()
    private val nonce = "fixture-nonce-01"
    private fun parse(node: JsonNode) = FiniteBindingContract.parseStartupV2(FiniteBindingContract.encode(node), digest, nonce)
    private fun cuts(n: ObjectNode) = n["cuts"] as ObjectNode
    private fun ranges(n: ObjectNode) = cuts(n)["ranges"] as ArrayNode
    private fun finance(n: ObjectNode) = cuts(n)["financeGroup"] as ObjectNode

    @Test fun parsesTheGoldenWitnessAndRefusesChallengeMismatch() {
        val w = FiniteBindingContract.parseStartupV2(raw("finite-startup-v2.json"), digest, nonce)
        assertEquals(8, w.cuts["ranges"].size())
        assertEquals("p3-fixture-finance", w.cuts["financeGroup"]["groupId"].textValue())
        assertEquals(digest, w.opening.digest)
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseStartupV2(raw("finite-startup-v2.json"), digest, "other-nonce") }
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseStartupV2(raw("finite-startup-v2.json"), "0".repeat(64), nonce) }
    }

    @Test fun versionsNeverConfuse() {
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseStartup(raw("finite-startup-v2.json"), digest, nonce) }
        val v1Digest = FiniteBindingContract.parseOpening(raw("finite-binding-v1.json")).digest
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseStartupV2(raw("finite-startup-v1.json"), v1Digest, nonce) }
    }

    @Test fun everyStructuralViolationIsRefused() {
        val mutations: Map<String, (ObjectNode) -> Unit> = mapOf(
            "five ranges" to { n -> ranges(n).remove(7); ranges(n).remove(6); ranges(n).remove(5) },
            "nine ranges" to { n -> ranges(n).add(ranges(n)[7].deepCopy<JsonNode>()) },
            "swapped role order" to { n -> val a = ranges(n)[5]; val b = ranges(n)[6]; ranges(n).set(5, b); ranges(n).set(6, a) },
            "begin above committed" to { n -> (ranges(n)[6] as ObjectNode).put("beginningOffset", "1") },
            "committed above end" to { n -> (ranges(n)[7] as ObjectNode).put("readCommittedEndOffset", "1") },
            "nonzero partition" to { n -> (ranges(n)[5] as ObjectNode).put("partition", 1) },
            "finance group id" to { n -> finance(n).put("groupId", "p3-fixture-other") },
            "finance capture past end" to { n -> finance(n).put("captureNextOffset", "1") },
            "finance control past end" to { n -> finance(n).put("controlNextOffset", "1") },
            "finance offset negative" to { n -> finance(n).put("controlNextOffset", "-1") },
            "finance offset number" to { n -> finance(n).put("captureNextOffset", 0) },
            "missing finance group" to { n -> cuts(n).remove("financeGroup") },
            "unknown finance field" to { n -> finance(n).put("extra", "0") },
            "command group beyond" to { n -> (cuts(n)["commandGroup"] as ObjectNode).put("nextOffset", "99") },
            "capture group beyond" to { n -> (cuts(n)["captureSourceGroup"] as ObjectNode).put("nextOffset", "1") },
            "unresolved accounting" to { n -> (n["run"] as ObjectNode).put("unresolvedPublications", "1") },
            "schema v1" to { n -> n.put("schema", "calcify-finite-startup-v1") },
            "unknown root field" to { n -> n.put("extra", 1) },
        )
        for ((name, edit) in mutations) {
            val node = fixture().also(edit)
            assertFailsWith<IllegalArgumentException>(name) { parse(node) }
        }
    }

    @Test fun stayWithinTheWireAndHeaderBudgets() {
        val bytes = raw("finite-startup-v2.json")
        assertTrue(bytes.size <= FiniteBindingContract.WIRE_BYTES)
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseStartupV2(bytes + ByteArray(FiniteBindingContract.WIRE_BYTES), digest, nonce) }
        val header = fixture().also { it.set<JsonNode>("journal", com.fasterxml.jackson.databind.ObjectMapper().createArrayNode()) }
        assertTrue(FiniteBindingContract.encode(header).size <= FiniteBindingContract.HEADER_BYTES)
    }

    @Test fun v1StaysStrictAboutExtraCutsAfterV2() {
        val v1 = FiniteBindingContract.strictObject(raw("finite-startup-v1.json"), FiniteBindingContract.WIRE_BYTES)
        val v1Digest = FiniteBindingContract.parseOpening(raw("finite-binding-v1.json")).digest
        (v1["cuts"] as ObjectNode).putObject("financeGroup").put("groupId", "x").put("captureNextOffset", "0").put("controlNextOffset", "0")
        assertFailsWith<IllegalArgumentException> { FiniteBindingContract.parseStartup(FiniteBindingContract.encode(v1), v1Digest, nonce) }
    }

    private fun config(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs).let { m -> { k -> m[k] } }
    private val history = arrayOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
    private val budget get() = FiniteBindingContract.parseOpeningV2(raw("finite-binding-v2.json")).budget

    @Test fun topicRulesRequireRetainedHistoryAndCompactedChangelogs() {
        val b = budget
        for (role in listOf("COMMAND", "SOURCE", "CAPTURE", "OWNERSHIP", "CONTROL", "FINANCE_DECISIONS")) {
            FiniteTopicRulesV2.check(role, b, config(*history, "max.message.bytes" to "100000000"))
            // Any retention loss, wrong cleanup or small record limit is refused.
            for (bad in listOf("retention.ms" to "86400000", "retention.bytes" to "1000", "cleanup.policy" to "compact")) {
                val broken = mapOf(*history, "max.message.bytes" to "100000000") + bad
                assertFailsWith<IllegalArgumentException>("$role $bad") { FiniteTopicRulesV2.check(role, b) { broken[it] } }
            }
            assertFailsWith<IllegalArgumentException>(role) { FiniteTopicRulesV2.check(role, b, config(*history, "max.message.bytes" to "1")) }
            assertFailsWith<IllegalArgumentException>("$role missing") { FiniteTopicRulesV2.check(role, b, config(*history)) }
        }
        for (role in listOf("CAPTURE_CHANGELOG", "FINANCE_CHANGELOG")) {
            FiniteTopicRulesV2.check(role, b, config("cleanup.policy" to "compact", "max.message.bytes" to "100000000"))
            assertFailsWith<IllegalArgumentException>(role) { FiniteTopicRulesV2.check(role, b, config("cleanup.policy" to "delete", "max.message.bytes" to "100000000")) }
            assertFailsWith<IllegalArgumentException>("$role small") { FiniteTopicRulesV2.check(role, b, config("cleanup.policy" to "compact", "max.message.bytes" to "1")) }
        }
        assertFailsWith<IllegalArgumentException> { FiniteTopicRulesV2.check("OTHER", b, config(*history, "max.message.bytes" to "100000000")) }
    }

    @Test fun controlAndDecisionLimitsAreExactlyTheCompiledMinimums() {
        val b = budget
        FiniteTopicRulesV2.check("CONTROL", b, config(*history, "max.message.bytes" to FiniteTopicRulesV2.CONTROL_MIN_RECORD_BYTES.toString()))
        assertFailsWith<IllegalArgumentException> { FiniteTopicRulesV2.check("CONTROL", b, config(*history, "max.message.bytes" to (FiniteTopicRulesV2.CONTROL_MIN_RECORD_BYTES - 1).toString())) }
        FiniteTopicRulesV2.check("FINANCE_DECISIONS", b, config(*history, "max.message.bytes" to FiniteTopicRulesV2.DECISION_MIN_RECORD_BYTES.toString()))
        assertFailsWith<IllegalArgumentException> { FiniteTopicRulesV2.check("FINANCE_DECISIONS", b, config(*history, "max.message.bytes" to (FiniteTopicRulesV2.DECISION_MIN_RECORD_BYTES - 1).toString())) }
    }
}
