package com.reef.platform.calcify.finance

import com.reef.platform.calcify.MemoryLifecycleKv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persisting and restoring the capacity ledger: restore-then-continue equals never-stopped at every cut of random schedules, and anything that is not exactly the
 * state the head decision committed to is refused (design verification item 15 for the synchronous profile).
 */
class CapacityStoreTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val opening = AccountId("opening")
    private val binding = "b".repeat(64)
    private val accounts = (0 until 6).map { AccountId("acct-$it") }.toSet()
    private val config = CapacityConfig(accounts, usd, setOf(aapl), opening, 60, 60, 120, 30)
    private val keys = (0 until 15).map { ExecutionKey("r", "s", "AAPL", "e$it") }

    private fun input(rnd: Random): FinanceInput {
        val a = ActionKey("n", "d", "a${rnd.nextInt(90)}"); val who = { AccountId(if (rnd.nextInt(15) == 0) "ghost" else "acct-${rnd.nextInt(6)}") }
        return when (rnd.nextInt(3)) {
            0 -> FinanceInput.Capture(a, keys[rnd.nextInt(keys.size)], who(), who(), rnd.nextInt(4).toLong(), rnd.nextInt(6).toLong())
            1 -> FinanceInput.Fund(a, if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, who(), if (rnd.nextBoolean()) usd else aapl, rnd.nextInt(8).toLong())
            else -> FinanceInput.Settle(a, Authority.OPERATOR, keys[rnd.nextInt(keys.size)], rnd.nextInt(3))
        }
    }

    /** Runs [inputs] against a fresh ledger and a store updated by each recorded decision's writes; returns the ledger, the store and the decisions. */
    private fun run(inputs: List<FinanceInput>): Triple<CapacityLedger, MemoryLifecycleKv, List<Decision>> {
        val ledger = CapacityLedger(config); val kv = MemoryLifecycleKv(); val decisions = ArrayList<Decision>()
        for (i in inputs) ledger.decide(i).decision?.let { d -> kv.apply(CapacityStore.writes(ledger, d, binding)); decisions += d }
        return Triple(ledger, kv, decisions)
    }

    @Test fun restoreThenContinueEqualsNeverStoppedAtEveryCutOfRandomSchedules() {
        var restores = 0
        for (seed in 1..60L) {
            val rnd = Random(seed); val inputs = (0 until 140).map { input(rnd) }
            val cut = rnd.nextInt(0, inputs.size)
            val (stopped, kv, _) = run(inputs.take(cut))
            val restored = CapacityStore.restore(config, kv, binding)
            val reference = CapacityLedger(config); inputs.take(cut).forEach { reference.decide(it) }
            if (restored == null) { assertEquals(0L, reference.sequence, "seed $seed"); continue }
            restores++
            assertEquals(stopped.snapshot(), restored.snapshot(), "seed $seed: restored state equals the state at the cut")
            assertEquals(reference.snapshot(), restored.snapshot()); assertEquals(stopped.chain, restored.chain); assertEquals(stopped.commitment(), restored.commitment())
            val tailA = inputs.drop(cut).map { restored.decide(it).decision }; val tailB = inputs.drop(cut).map { reference.decide(it).decision }
            assertEquals(tailB, tailA, "seed $seed: the restored ledger continues exactly as the one that never stopped")
        }
        assertTrue(restores > 40, "restores=$restores")
    }

    @Test fun restoreInAdmitModeDerivesTheAdmittedAccountsFromThePositions() {
        val admit = CapacityConfig(null, usd, setOf(aapl), opening, 60, 60, 120, 30)
        for (seed in 1..40L) {
            val rnd = Random(seed); val inputs = (0 until 140).map { input(rnd) }; val cut = rnd.nextInt(10, inputs.size)
            val stopped = CapacityLedger(admit); val kv = MemoryLifecycleKv()
            inputs.take(cut).forEach { i -> stopped.decide(i).decision?.let { d -> kv.apply(CapacityStore.writes(stopped, d, binding)) } }
            val restored = CapacityStore.restore(admit, kv, binding) ?: continue
            val never = CapacityLedger(admit); inputs.take(cut).forEach { never.decide(it) }
            assertEquals(never.snapshot(), restored.snapshot(), "seed $seed")
            assertEquals(inputs.drop(cut).map { never.decide(it).decision }, inputs.drop(cut).map { restored.decide(it).decision }, "seed $seed: admission survives a restore")
        }
    }

    @Test fun anEmptyStoreIsGenesisAndAStoreWithEntriesButNoMetaIsRefused() {
        assertNull(CapacityStore.restore(config, MemoryLifecycleKv(), binding))
        val stray = MemoryLifecycleKv().also { it.entries["e/P/00"] = ByteArray(1) }
        assertFailsWith<IllegalArgumentException> { CapacityStore.restore(config, stray, binding) }
    }

    private fun refused(kv: MemoryLifecycleKv, what: String, cfg: CapacityConfig = config, bindingDigest: String = binding) {
        val failure = runCatching { CapacityStore.restore(cfg, kv, bindingDigest) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException || failure is com.google.protobuf.InvalidProtocolBufferException || failure is java.util.NoSuchElementException, "$what: expected a refusal, got $failure")
    }

    private fun copy(kv: MemoryLifecycleKv) = MemoryLifecycleKv().also { c -> kv.entries.forEach { (k, v) -> c.entries[k] = v.copyOf() } }

    @Test fun anythingThatIsNotExactlyTheCommittedStateIsRefused() {
        val rnd = Random(42); val inputs = generateSequence { input(rnd) }.take(200).toList()
        val (ledger, kv, decisions) = run(inputs); assertTrue(decisions.size > 40)
        assertNotNull(CapacityStore.restore(config, kv, binding), "the untouched store restores")
        val entityKeys = kv.entries.keys.filter { it.startsWith("e/") }
        assertTrue(entityKeys.size > 30)

        for (key in entityKeys.shuffled(Random(1)).take(25)) {                       // a changed byte in any sampled entity value
            val t = copy(kv); val v = t.entries.getValue(key); v[v.size - 1] = (v[v.size - 1].toInt() xor 1).toByte(); refused(t, "flip last byte of $key")
        }
        for (key in entityKeys.shuffled(Random(2)).take(15)) { val t = copy(kv); t.entries.remove(key); refused(t, "remove $key") }
        run { val t = copy(kv); t.entries["e/P/" + CapacityStore.entityKey(StateCommitment.Entity("P", Canon.bytes { str("acct-1"); str("MSFT") }, ByteArray(0))).removePrefix("e/P/")] = t.entries.getValue(entityKeys.first { it.startsWith("e/P/") }); refused(t, "extra position in an unknown asset") }
        run { val t = copy(kv); t.entries["e/Z/00"] = ByteArray(10); refused(t, "unknown class") }
        run { val t = copy(kv); t.entries.remove(CapacityStore.HEAD); refused(t, "head missing") }
        run { val t = copy(kv); t.entries.remove(CapacityStore.META); refused(t, "meta missing") }
        run { val t = copy(kv); val m = CapacityStore.Meta.parse(t.entries.getValue(CapacityStore.META)); t.entries[CapacityStore.META] = m.copy(controls = m.controls + 1).bytes(); refused(t, "controls differ") }
        run { val t = copy(kv); val m = CapacityStore.Meta.parse(t.entries.getValue(CapacityStore.META)); t.entries[CapacityStore.META] = m.copy(sequence = m.sequence - 1).bytes(); refused(t, "sequence differs") }
        run { val t = copy(kv); val m = CapacityStore.Meta.parse(t.entries.getValue(CapacityStore.META)); t.entries[CapacityStore.META] = m.copy(chain = "c".repeat(64)).bytes(); refused(t, "chain differs") }
        refused(kv, "another binding", bindingDigest = "d".repeat(64))
        refused(kv, "another configuration", cfg = config.copy(maxControls = 31))
        run {                                                                         // the head of an earlier decision with the entities of a later one
            val t = copy(kv); t.entries[CapacityStore.HEAD] = FinanceWire.toProto(decisions[decisions.size - 2]).toByteArray(); refused(t, "an earlier head")
        }
        run {                                                                         // the right entities under a forged head carrying another commitment
            val t = copy(kv); val last = decisions.last(); val forged = last.copy(stateDigest = "e".repeat(64), checksum = CapacityLedger.checksumOf(last.sequence, last.action, last.inputDigest, last.disposition, last.legs, last.priorChain, "e".repeat(64)))
            t.entries[CapacityStore.HEAD] = FinanceWire.toProto(forged).toByteArray()
            t.entries[CapacityStore.META] = CapacityStore.Meta.parse(t.entries.getValue(CapacityStore.META)).copy(chain = forged.checksum).bytes(); refused(t, "a self-consistent head over a different commitment")
        }
        assertEquals(ledger.snapshot(), CapacityStore.restore(config, kv, binding)!!.snapshot(), "the original store is untouched by the tamper copies")
    }

    @Test fun theWritesOfOneDecisionAreItsEntitiesTheHeadAndTheMeta() {
        val ledger = CapacityLedger(config)
        val d = ledger.decide(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 50)).decision!!
        val writes = CapacityStore.writes(ledger, d, binding)
        assertEquals(setOf(CapacityStore.HEAD, CapacityStore.META), writes.keys.filterNot { it.startsWith("e/") }.toSet())
        assertEquals(3, writes.keys.count { it.startsWith("e/") }, "two positions and the action record")
        assertEquals(emptyList(), ledger.also { it.decide(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 50)) }.lastWrites, "a refusal writes nothing")
        assertFailsWith<IllegalArgumentException> { CapacityStore.writes(ledger, d.copy(sequence = 9), binding) }
    }
}
