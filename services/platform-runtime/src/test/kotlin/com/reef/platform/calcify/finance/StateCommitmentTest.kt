package com.reef.platform.calcify.finance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.HexFormat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The reference state commitment against the goldens produced by the independent Python encoder
 * (`scripts/dev/calcify-financial/state_commitment_reference.py`): per-entity keys and leaves, trie roots, the commitment, and the structural rules of
 * design sections 3 and 4.1 (order independence, no duplicate keys, bit conventions, sensitivity to every field).
 */
class StateCommitmentTest {
    private val hex = HexFormat.of()
    private val goldens: JsonNode = javaClass.getResourceAsStream("/calcify/state-commitment-goldens.json").use { ObjectMapper().readTree(it) }

    private fun texts(node: JsonNode) = node.map { it.asText() }

    private fun stateOf(case: JsonNode): FinanceState {
        val positions = LinkedHashMap<Pair<AccountId, AssetId>, Long>(); val executions = LinkedHashMap<ExecutionKey, Captured>()
        val obligations = LinkedHashMap<ExecutionKey, Obligation>(); val attempts = LinkedHashMap<Pair<ExecutionKey, Int>, Disposition>(); val actions = LinkedHashMap<ActionKey, ActionRecord>()
        for (e in case["entities"]) {
            val k = e["key"]; val v = e["value"]
            fun exec() = ExecutionKey(k[0].asText(), k[1].asText(), k[2].asText(), k[3].asText())
            when (e["tag"].asText()) {
                "P" -> positions[AccountId(k[0].asText()) to AssetId(k[1].asText())] = v[0].asLong()
                "X" -> executions[exec()] = Captured(AccountId(v[0].asText()), AccountId(v[1].asText()), v[2].asLong(), v[3].asLong())
                "O" -> obligations[exec()] = Obligation(ObligationStatus.valueOf(v[0].asText()), v[1].asLong(), v[2].asLong())
                "A" -> attempts[exec() to k[4].asInt()] = Disposition.valueOf(v[0].asText())
                "K" -> actions[ActionKey(k[0].asText(), k[1].asText(), k[2].asText())] = ActionRecord(v[0].asText(), Disposition.valueOf(v[1].asText()))
                else -> error("unknown tag")
            }
        }
        return FinanceState(positions, executions, obligations, attempts, actions, case["sequence"].asLong(), "0".repeat(64), case["controls"].asInt())
    }

    @Test fun theLabelsMatchTheSpecification() {
        assertEquals("calcify-finance-state-v2-patricia", StateCommitment.LABEL)
        assertEquals(StateCommitment.LABEL, goldens["label"].asText())
    }

    @Test fun everyGoldenCaseMatchesTheIndependentEncoder() {
        for (case in goldens["cases"]) {
            val name = case["name"].asText(); val state = stateOf(case)
            assertEquals(case["entities"].size(), StateCommitment.entities(state).size, "$name: no golden entity was lost to a duplicate key when the state was rebuilt")
            assertEquals(case["commitment"].asText(), StateCommitment.reference(state), "$name: commitment")
            if (case.has("entityKeys")) {
                val entities = StateCommitment.entities(state)
                assertEquals(case["entities"].size(), entities.size, "$name: entity count")
                assertEquals(texts(case["entityKeys"]).sorted(), entities.map { hex.formatHex(StateCommitment.keyOf(it)) }.sorted(), "$name: trie keys")
                assertEquals(texts(case["entityLeaves"]).sorted(), entities.map { hex.formatHex(StateCommitment.leafOf(it)) }.sorted(), "$name: leaf digests")
                assertEquals(case["trie"].asText(), hex.formatHex(StateCommitment.trieRoot(entities.map { StateCommitment.keyOf(it) to StateCommitment.leafOf(it) })), "$name: trie root")
            }
        }
    }

    @Test fun theTrieStructureMatchesTheIndependentEncoderForCraftedKeys() {
        for (case in goldens["trieCases"]) {
            val pairs = case["pairs"].map { hex.parseHex(it["key"].asText()) to hex.parseHex(it["leaf"].asText()) }
            assertEquals(case["root"].asText(), hex.formatHex(StateCommitment.trieRoot(pairs)), case["name"].asText())
            assertEquals(case["root"].asText(), hex.formatHex(StateCommitment.trieRoot(pairs.reversed())), "${case["name"].asText()} (reversed input)")
            assertEquals(case["root"].asText(), hex.formatHex(StateCommitment.trieRoot(pairs.shuffled(Random(5)))), "${case["name"].asText()} (shuffled input)")
        }
    }

    @Test fun theEmptySetAndASingleLeafHaveNoBranch() {
        assertEquals(hex.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(3))), hex.formatHex(StateCommitment.emptyTrie))
        val e = StateCommitment.Entity("P", Canon.bytes { str("a"); str("USD") }, Canon.bytes { long(1) })
        assertEquals(hex.formatHex(StateCommitment.leafOf(e)), hex.formatHex(StateCommitment.trieRoot(listOf(StateCommitment.keyOf(e) to StateCommitment.leafOf(e)))))
    }

    @Test fun theResultIsAFunctionOfTheEntitySetNotOfItsOrder() {
        val case = goldens["cases"].first { it["name"].asText() == "large-1000-entities" }; val state = stateOf(case)
        val entities = StateCommitment.entities(state)
        val expected = case["commitment"].asText()
        for (seed in 1..5) assertEquals(expected, StateCommitment.reference(entities.shuffled(Random(seed)), state.sequence, state.controls))
    }

    @Test fun twoEntitiesWithTheSameTrieKeyAreRefusedNeverOverwritten() {
        val a = StateCommitment.Entity("P", Canon.bytes { str("a"); str("USD") }, Canon.bytes { long(1) })
        val b = StateCommitment.Entity("P", Canon.bytes { str("b"); str("USD") }, Canon.bytes { long(2) })
        assertFailsWith<IllegalArgumentException> { StateCommitment.reference(listOf(a, b), 1, 0) { _ -> ByteArray(32) { 7 } } }
        assertFailsWith<IllegalArgumentException> { StateCommitment.reference(listOf(a, a), 1, 0) }
    }

    @Test fun anActionDigestThatIsNotLowercaseHexIsRefused() {
        val base = stateOf(goldens["cases"].first { it["name"].asText() == "single-K" })
        val key = base.actions.keys.single()
        for (bad in listOf("A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64), "")) {
            assertFailsWith<IllegalArgumentException>("digest [$bad]") { StateCommitment.entities(base.copy(actions = mapOf(key to ActionRecord(bad, Disposition.CAPTURED)))) }
        }
    }

    @Test fun everyFieldOfEveryEntityClassAndBothScalarsChangeTheCommitment() {
        val state = stateOf(goldens["cases"].first { it["name"].asText() == "one-of-each-class" })
        val base = StateCommitment.reference(state)
        val ex = state.executions.keys.single(); val obligation = state.obligations.getValue(ex); val captured = state.executions.getValue(ex)
        val action = state.actions.keys.single(); val record = state.actions.getValue(action); val position = state.positions.keys.single(); val attempt = state.attempts.keys.single()
        val mutants = mapOf(
            "sequence" to state.copy(sequence = state.sequence + 1), "controls" to state.copy(controls = state.controls + 1),
            "position amount" to state.copy(positions = mapOf(position to state.positions.getValue(position) + 1)),
            "position account" to state.copy(positions = mapOf((AccountId("acct-2") to position.second) to state.positions.getValue(position))),
            "position asset" to state.copy(positions = mapOf((position.first to AssetId("AAPL")) to state.positions.getValue(position))),
            "execution buyer" to state.copy(executions = mapOf(ex to captured.copy(buyer = AccountId("buyer-9")))),
            "execution seller" to state.copy(executions = mapOf(ex to captured.copy(seller = AccountId("seller-9")))),
            "execution quantity" to state.copy(executions = mapOf(ex to captured.copy(quantity = captured.quantity + 1))),
            "execution price" to state.copy(executions = mapOf(ex to captured.copy(priceNanos = captured.priceNanos + 1))),
            "execution key" to state.copy(executions = mapOf(ex.copy(executionId = "exec-2") to captured)),
            "obligation status" to state.copy(obligations = mapOf(ex to obligation.copy(status = ObligationStatus.SETTLED))),
            "obligation cash" to state.copy(obligations = mapOf(ex to obligation.copy(cashResidual = obligation.cashResidual + 1))),
            "obligation shares" to state.copy(obligations = mapOf(ex to obligation.copy(shareResidual = obligation.shareResidual + 1))),
            "attempt number" to state.copy(attempts = mapOf((attempt.first to attempt.second + 1) to state.attempts.getValue(attempt))),
            "attempt disposition" to state.copy(attempts = mapOf(attempt to Disposition.INSUFFICIENT_CASH)),
            "action digest" to state.copy(actions = mapOf(action to record.copy(digest = "b".repeat(64)))),
            "action disposition" to state.copy(actions = mapOf(action to record.copy(disposition = Disposition.FUNDED))),
            "action id" to state.copy(actions = mapOf(action.copy(actionId = "c2") to record)),
            "dropped position" to state.copy(positions = emptyMap()),
        )
        for ((name, mutant) in mutants) assertNotEquals(base, StateCommitment.reference(mutant), name)
        assertEquals(base, StateCommitment.reference(state.copy(chain = "f".repeat(64))), "the chain head is not part of the committed state")
    }

    @Test fun aSameValuedEntityInADifferentClassHasADifferentKey() {
        val position = StateCommitment.Entity("P", Canon.bytes { str("x"); str("y") }, Canon.bytes { long(1) })
        val other = StateCommitment.Entity("K", position.keyBytes, position.valueBytes)
        assertNotEquals(hex.formatHex(StateCommitment.keyOf(position)), hex.formatHex(StateCommitment.keyOf(other)))
    }

    @Test fun aRealLedgerStateCommitsDeterministicallyAndMovesWithEveryRecordedDecision() {
        val buyer = AccountId("b"); val seller = AccountId("s"); val usd = AssetId("USD"); val aapl = AssetId("AAPL")
        val ledger = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("opening")))
        var state = ledger.genesis(); var last = StateCommitment.reference(state); var moved = 0
        val rnd = Random(3)
        repeat(60) { n ->
            val key = ExecutionKey("r", "s", "AAPL", "e${rnd.nextInt(5)}")
            val input = when (rnd.nextInt(3)) {
                0 -> FinanceInput.Fund(ActionKey("n", "ctl", "f$n"), Authority.OPERATOR, if (rnd.nextBoolean()) buyer else seller, if (rnd.nextBoolean()) usd else aapl, 1L + rnd.nextInt(1000))
                1 -> FinanceInput.Capture(ActionKey("n", "cap", "c$n"), key, buyer, seller, 1L, 1_000L)
                else -> FinanceInput.Settle(ActionKey("n", "rep", "s$n"), Authority.OPERATOR, key, 1)
            }
            val outcome = ledger.decide(state, input)
            if (outcome.decision != null) { state = outcome.state; val now = StateCommitment.reference(state); assertNotEquals(last, now, "step $n"); last = now; moved++ }
            assertEquals(StateCommitment.reference(state), StateCommitment.reference(state), "deterministic")
        }
        assertTrue(moved >= 10, "the schedule recorded decisions")
    }
}
