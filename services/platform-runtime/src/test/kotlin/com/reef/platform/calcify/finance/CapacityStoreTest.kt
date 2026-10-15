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
        val entityKeys = kv.entries.keys.filter { it.startsWith("e/") || it.startsWith("x/") }
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

    /** The store as a processor with checkpointEvery = [k] leaves it: every decision's entities, head and meta only when the sequence crosses a multiple of k (and for the first decision). */
    private fun runCheckpointed(inputs: List<FinanceInput>, k: Int): Triple<CapacityLedger, MemoryLifecycleKv, List<Decision>> {
        val ledger = CapacityLedger(config); val kv = MemoryLifecycleKv(); val decisions = ArrayList<Decision>()
        for (i in inputs) {
            val before = ledger.sequence
            val d = ledger.decide(i).decision ?: continue
            val w = CapacityStore.writes(ledger, d, binding)
            kv.apply(if (before == 0L || ledger.sequence / k > before / k) w else w.filterKeys { it != CapacityStore.HEAD && it != CapacityStore.META })
            decisions += d
        }
        return Triple(ledger, kv, decisions)
    }

    @Test fun aStoreBetweenCheckpointsRestoresFromTheDecisionLogHeadAndEqualsNeverStopped() {
        var ahead = 0; var atCheckpoint = 0
        for (k in listOf(2, 5, 16)) for (seed in 1..40L) {
            val rnd = Random(seed); val inputs = (0 until 140).map { input(rnd) }; val cut = rnd.nextInt(1, inputs.size)
            val (stopped, kv, decisions) = runCheckpointed(inputs.take(cut), k)
            if (decisions.isEmpty()) continue
            val meta = CapacityStore.Meta.parse(kv.entries.getValue(CapacityStore.META))
            if (meta.sequence != stopped.sequence) {
                ahead++
                assertFailsWith<IllegalArgumentException>("k=$k seed=$seed: ahead of the checkpoint, no log head") { CapacityStore.restore(config, kv, binding) }
                assertFailsWith<IllegalArgumentException>("k=$k seed=$seed: log head unknown") { CapacityStore.restore(config, kv, binding, decisions.last(), logHeadKnown = false) }
            } else atCheckpoint++
            val restored = assertNotNull(CapacityStore.restore(config, kv, binding, decisions.last(), logHeadKnown = true), "k=$k seed=$seed")
            assertEquals(stopped.snapshot(), restored.snapshot()); assertEquals(stopped.chain, restored.chain); assertEquals(stopped.commitment(), restored.commitment())
            val reference = CapacityLedger(config); inputs.take(cut).forEach { reference.decide(it) }
            assertEquals(inputs.drop(cut).map { reference.decide(it).decision }, inputs.drop(cut).map { restored.decide(it).decision }, "k=$k seed=$seed: continues exactly as the ledger that never stopped")
        }
        assertTrue(ahead > 40 && atCheckpoint > 5, "ahead=$ahead atCheckpoint=$atCheckpoint")
    }

    private fun refusedWith(kv: MemoryLifecycleKv, what: String, logHead: Decision?, known: Boolean) {
        val failure = runCatching { CapacityStore.restore(config, kv, binding, logHead, known) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException || failure is NoSuchElementException, "$what: expected a refusal, got $failure")
    }

    @Test fun aStoreBetweenCheckpointsIsRefusedWhenTheLogHeadOrTheEntitiesAreNotTheCommittedState() {
        val rnd = Random(77); val inputs = generateSequence { input(rnd) }.take(200).toList()
        var tried = 0
        for (cut in 100..140) {
            val (_, kv, decisions) = runCheckpointed(inputs.take(cut), 16)
            if (CapacityStore.Meta.parse(kv.entries.getValue(CapacityStore.META)).sequence == decisions.last().sequence) continue
            tried++
            val last = decisions.last()
            assertNotNull(CapacityStore.restore(config, kv, binding, last, true))
            refusedWith(kv, "an earlier log head", decisions[decisions.size - 2], true)
            refusedWith(kv, "an empty log", null, true)
            val forged = last.copy(stateDigest = "e".repeat(64), checksum = CapacityLedger.checksumOf(last.sequence, last.action, last.inputDigest, last.disposition, last.legs, last.priorChain, "e".repeat(64)))
            refusedWith(kv, "a self-consistent log head over another commitment", forged, true)
            refusedWith(kv, "a log head whose checksum does not recompute", last.copy(checksum = "c".repeat(64)), true)
            val flipped = copy(kv); val key = flipped.entries.keys.filter { it.startsWith("e/") || it.startsWith("x/") }.shuffled(Random(cut)).first(); val v = flipped.entries.getValue(key); v[v.size - 1] = (v[v.size - 1].toInt() xor 1).toByte()
            refusedWith(flipped, "a flipped entity byte", last, true)
            val removed = copy(kv); removed.entries.remove(removed.entries.keys.first { it.startsWith("e/K/") }); refusedWith(removed, "a removed action record", last, true)
            val behind = copy(kv); behind.entries[CapacityStore.META] = CapacityStore.Meta.parse(behind.entries.getValue(CapacityStore.META)).let { it.copy(sequence = last.sequence + 1).bytes() }
            refusedWith(behind, "entities behind the checkpoint", last, true)
        }
        assertTrue(tried > 10, "tried=$tried")
    }

    @Test fun aStoreAtItsCheckpointMustAlsoBeTheHeadOfTheLogWhenTheLogIsKnown() {
        val rnd = Random(5); val inputs = generateSequence { input(rnd) }.take(120).toList()
        val (_, kv, decisions) = run(inputs)
        assertNotNull(CapacityStore.restore(config, kv, binding, decisions.last(), true))
        refusedWith(kv, "a log that moved on", decisions.last().copy(sequence = decisions.last().sequence + 1), true)
        refusedWith(kv, "an empty log", null, true)
    }

    @Test fun anExecutionIsOneStoreRecordWhetherItsCaptureAndFirstSettlementShareARecordOrNot() {
        val shared = CapacityLedger(config); val kv = MemoryLifecycleKv()
        for (f in listOf(FinanceInput.Fund(ActionKey("n", "f", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), FinanceInput.Fund(ActionKey("n", "f", "f2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 10))) {
            val d = shared.decide(f).decision!!; kv.apply(CapacityStore.writes(shared, d, binding))
        }
        // One record, two decisions: the capture and the settlement are folded into one execution put (plus the two action records and four positions).
        val entities = LinkedHashMap<String, StateCommitment.Entity>(); var last: Decision? = null
        val e1 = ExecutionKey("r", "s", "AAPL", "e1")
        for (i in listOf(FinanceInput.Capture(ActionKey("n", "capture", "e1"), e1, AccountId("acct-0"), AccountId("acct-1"), 2, 5), FinanceInput.Settle(ActionKey("n", "settle", "e1/1"), Authority.OPERATOR, e1, 1))) {
            last = shared.decide(i).decision!!; for (e in shared.lastWrites) entities[CapacityStore.entityKey(e)] = e
        }
        val folded = CapacityStore.storeWrites(shared, entities)
        assertEquals(1, folded.keys.count { it.startsWith("x/") }, "capture, obligation and first attempt are one put")
        assertEquals(0, folded.keys.count { it.startsWith("e/X/") || it.startsWith("e/O/") || it.startsWith("e/A/") })
        assertEquals(4 + 2 + 1, folded.size, "four positions, two actions, one execution")
        val w = LinkedHashMap(folded); w[CapacityStore.HEAD] = FinanceWire.toProto(last!!).toByteArray(); w[CapacityStore.META] = CapacityStore.Meta(binding, config.digest(), shared.sequence, shared.chain, shared.controls).bytes()
        kv.apply(w)
        assertEquals(shared.snapshot(), CapacityStore.restore(config, kv, binding)!!.snapshot())
        // The same execution split over two records (capture now, settlement later) restores to the same state: the second record completes the execution record from the ledger.
        val split = CapacityLedger(config); val kv2 = MemoryLifecycleKv()
        for (i in listOf(FinanceInput.Fund(ActionKey("n", "f", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), FinanceInput.Fund(ActionKey("n", "f", "f2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 10),
            FinanceInput.Capture(ActionKey("n", "capture", "e1"), e1, AccountId("acct-0"), AccountId("acct-1"), 2, 5), FinanceInput.Settle(ActionKey("n", "settle", "e1/1"), Authority.OPERATOR, e1, 1))) {
            val d = split.decide(i).decision!!; kv2.apply(CapacityStore.writes(split, d, binding))
        }
        assertEquals(1, kv2.entries.keys.count { it.startsWith("x/") })
        assertEquals(shared.snapshot(), CapacityStore.restore(config, kv2, binding)!!.snapshot()); assertEquals(shared.chain, split.chain)
        // A failed first attempt, then a successful second one: the first stays in the execution record, the second is an `e/A/` entity.
        val retry = CapacityLedger(config); val kv3 = MemoryLifecycleKv()
        for (i in listOf(FinanceInput.Fund(ActionKey("n", "f", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), FinanceInput.Capture(ActionKey("n", "capture", "e1"), e1, AccountId("acct-0"), AccountId("acct-1"), 2, 5),
            FinanceInput.Settle(ActionKey("n", "settle", "e1/1"), Authority.OPERATOR, e1, 1), FinanceInput.Fund(ActionKey("n", "f", "f2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 10), FinanceInput.Settle(ActionKey("n", "settle", "e1/2"), Authority.OPERATOR, e1, 2))) {
            val d = retry.decide(i).decision!!; kv3.apply(CapacityStore.writes(retry, d, binding))
        }
        assertEquals(1, kv3.entries.keys.count { it.startsWith("e/A/") }, "attempt 2 only")
        assertEquals(retry.snapshot(), CapacityStore.restore(config, kv3, binding)!!.snapshot())
        // A damaged execution record is refused, however it is damaged.
        val key = kv.entries.keys.first { it.startsWith("x/") }; val good = kv.entries.getValue(key)
        for ((what, bad) in listOf("truncated" to good.copyOf(good.size - 3), "trailing byte" to good + byteArrayOf(0), "empty" to ByteArray(0), "no economics" to byteArrayOf(0, 0, 0, 0) + good.copyOfRange(4 + java.nio.ByteBuffer.wrap(good).int, good.size))) {
            val t = copy(kv); t.entries[key] = bad; refused(t, what)
        }
    }

    @Test fun theWritesOfOneDecisionAreItsEntitiesTheHeadAndTheMeta() {
        val ledger = CapacityLedger(config)
        val d = ledger.decide(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 50)).decision!!
        val writes = CapacityStore.writes(ledger, d, binding)
        assertEquals(setOf(CapacityStore.HEAD, CapacityStore.META), writes.keys.filterNot { it.startsWith("e/") || it.startsWith("x/") }.toSet())
        assertEquals(3, writes.keys.count { it.startsWith("e/") }, "two positions and the action record")
        assertEquals(emptyList(), ledger.also { it.decide(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 50)) }.lastWrites, "a refusal writes nothing")
        assertFailsWith<IllegalArgumentException> { CapacityStore.writes(ledger, d.copy(sequence = 9), binding) }
    }
}
