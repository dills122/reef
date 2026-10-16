package com.reef.platform.calcify.finance

import com.reef.platform.calcify.MemoryLifecycleKv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lane snapshot of an evicting ledger (written on a clean close, read on the next start instead of the scan) must give a ledger that decides exactly like one restored
 * by the scan, and must never be used unless it is exactly the state at the store's head: a damaged, truncated, foreign, stale or newer snapshot, or one whose trie or open
 * work is not what the head decision committed to, falls back to the scan with the scan's result, and the file is gone after every start.
 */
class CapacitySnapshotTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val msft = AssetId("MSFT"); private val opening = AccountId("opening")
    private val binding = "b".repeat(64)
    private val dir: Path = Files.createTempDirectory("capacity-snapshot-test")
    @AfterTest fun cleanUp() { dir.toFile().deleteRecursively() }

    private fun config(mode: DecisionMode, accounts: Int, budgets: Int) = CapacityConfig((0 until accounts).map { AccountId("acct-$it") }.toSet(), usd, setOf(aapl, msft), opening, budgets, budgets, budgets, 40, mode)

    private fun randomInput(rnd: Random, mode: DecisionMode, accounts: Int, executions: List<ExecutionKey>, actions: Int): FinanceInput {
        val action = ActionKey("n", "d", "a${rnd.nextInt(actions)}")
        fun who() = AccountId(if (rnd.nextInt(20) == 0) "ghost" else "acct-${rnd.nextInt(accounts)}")
        val e = executions[rnd.nextInt(executions.size)]
        return when (rnd.nextInt(10)) {
            0, 1 -> FinanceInput.Fund(action, Authority.OPERATOR, who(), if (rnd.nextBoolean()) usd else if (rnd.nextBoolean()) aapl else msft, 1 + rnd.nextInt(25).toLong())
            2, 3, 4 -> FinanceInput.Settle(action, if (rnd.nextInt(15) == 0) Authority.TRADING else Authority.OPERATOR, e, rnd.nextInt(4))
            else -> {
                val capture = FinanceInput.Capture(action, e, who(), who(), 1L + rnd.nextInt(3), 1L + rnd.nextInt(4))
                if (mode == DecisionMode.CAPTURE_SETTLE && rnd.nextInt(4) != 0) CapacityCaptureMapping.captureSettle(capture) else capture
            }
        }
    }

    /** A live evicting lane: its ledger, the store its writes reach (in groups of one to three decisions, as records), and every input it has seen (for replays). */
    private class Lane(val cfg: CapacityConfig, val binding: String, val rnd: Random) {
        val kv = MemoryLifecycleKv(); val ledger = CapacityLedger(cfg, EntityBacking { kv.get(it) }); val seen = ArrayList<FinanceInput>()
        private val group = LinkedHashMap<String, ByteArray>()
        fun decide(input: FinanceInput) { seen += input; ledger.decide(input).decision?.let { d -> group.putAll(CapacityStore.writes(ledger, d, binding)) }; if (rnd.nextInt(3) == 0) flush() }
        fun flush() { kv.apply(group); group.clear(); ledger.persisted() }
    }

    private fun lane(mode: DecisionMode, seed: Long, budgets: Int, steps: Int, accounts: Int = 5): Pair<Lane, List<ExecutionKey>> {
        val lane = Lane(config(mode, accounts, budgets), binding, Random(seed))
        val executions = (0 until 25).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
        var f = 0
        for (i in 0 until accounts) for ((asset, amount) in listOf(usd to 8L, aapl to 3L, msft to 3L)) lane.decide(FinanceInput.Fund(ActionKey("n", "fund", "f${f++}"), Authority.OPERATOR, AccountId("acct-$i"), asset, amount))
        repeat(steps) { lane.decide(randomInput(lane.rnd, mode, accounts, executions, 300)) }
        lane.flush()
        return lane to executions
    }

    private fun copyOf(kv: MemoryLifecycleKv) = MemoryLifecycleKv().also { it.entries.putAll(kv.entries) }
    private fun entries(kv: MemoryLifecycleKv) = kv.entries.mapValues { it.value.toList() }

    /** Restores from the snapshot at [file]; returns the ledger and why the snapshot was not used (null when it was). The file must be gone afterwards. */
    private fun restoreWithSnapshot(cfg: CapacityConfig, kv: MemoryLifecycleKv, file: Path): Pair<CapacityLedger?, String?> {
        var why: String? = "not reported"
        val ledger = CapacityStore.restore(cfg, kv, binding, evictSettled = true, snapshot = file) { why = it }
        assertFalse(Files.exists(file), "the snapshot is deleted by the start that read it")
        return ledger to why
    }

    private fun assertSameState(expected: CapacityLedger, actual: CapacityLedger, what: String) {
        assertEquals(expected.sequence, actual.sequence, "$what sequence"); assertEquals(expected.chain, actual.chain, "$what chain"); assertEquals(expected.controls, actual.controls, "$what controls")
        assertEquals(expected.commitment(), actual.commitment(), "$what commitment"); assertEquals(expected.entityCount, actual.entityCount, "$what entities")
        assertEquals(expected.residentObjects, actual.residentObjects, "$what resident objects")
    }

    /**
     * Differential: ledger A restored by the scan, ledger B from the snapshot of the live lane (each on its own copy of the store), and a plain ledger C restored by the plain
     * scan, decide [steps] more random inputs, a third of them verbatim replays of inputs seen before the restart; they must agree on every disposition, decision and store
     * write. Then B is snapshotted again and restored again (a second restart from a snapshot of a snapshot-restored ledger) and must still equal A.
     */
    private fun differential(mode: DecisionMode, seeds: LongRange, budgets: Int, steps: Int, expect: List<Disposition>) {
        val seen = HashSet<Disposition>(); var replays = 0
        for (seed in seeds) {
            val (live, executions) = lane(mode, seed, budgets, 400)
            val file = dir.resolve("lane-$seed.snapshot")
            CapacityStore.writeSnapshot(live.ledger, binding, file)
            val kvA = copyOf(live.kv); val kvB = copyOf(live.kv)
            val a = CapacityStore.restore(live.cfg, kvA, binding, evictSettled = true)!!
            val plain = CapacityStore.restore(live.cfg, copyOf(live.kv), binding)!!
            var b: CapacityLedger = restoreWithSnapshot(live.cfg, kvB, file).let { (ledger, why) -> assertNull(why, "seed $seed: the snapshot at the head is used"); ledger!! }
            assertSameState(a, b, "seed $seed after restore"); assertSameState(live.ledger, b, "seed $seed against the live lane")
            val rnd = Random(seed * 31)
            val later = executions + (25 until 40).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }      // trades the lane has never seen as well
            for (round in 0 until 2) {
                repeat(steps) { n ->
                    val input = if (rnd.nextInt(3) == 0) live.seen[rnd.nextInt(live.seen.size)].also { replays++ } else randomInput(rnd, mode, 5, later, 400)
                    val x = a.decide(input); val y = b.decide(input); val z = plain.decide(input)
                    assertEquals(x.disposition, y.disposition, "mode ${mode.wire} seed $seed round $round step $n $input"); assertEquals(x.decision, y.decision, "decision seed $seed round $round step $n")
                    assertEquals(z.decision, y.decision, "plain ledger, seed $seed round $round step $n")
                    assertEquals(a.lastWrites.map { CapacityStore.entityKey(it) to CapacityStore.entityValue(it).toList() }, b.lastWrites.map { CapacityStore.entityKey(it) to CapacityStore.entityValue(it).toList() }, "writes seed $seed step $n")
                    seen += x.disposition
                    x.decision?.let { d -> kvA.apply(CapacityStore.writes(a, d, binding)) }; a.persisted()
                    y.decision?.let { d -> kvB.apply(CapacityStore.writes(b, d, binding)) }; b.persisted()
                }
                assertSameState(a, b, "seed $seed round $round"); assertEquals(entries(kvA), entries(kvB), "stored entries seed $seed round $round")
                assertEquals(plain.commitment(), b.commitment())
                CapacityStore.writeSnapshot(b, binding, file)
                val (again, whyAgain) = restoreWithSnapshot(live.cfg, kvB, file)
                assertNull(whyAgain, "seed $seed round $round: a snapshot of a snapshot-restored ledger is used"); b = again!!
                assertSameState(a, b, "seed $seed round $round after the second restart")
            }
        }
        assertTrue(replays > 100, "replays: $replays")
        for (d in expect) assertTrue(d in seen, "after the restart the differential never produced $d")
    }

    private val common = listOf(Disposition.CAPTURED, Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.PRIOR_RESULT, Disposition.ACTION_CONFLICT, Disposition.EXECUTION_ALREADY_CAPTURED,
        Disposition.EXECUTION_CONFLICT, Disposition.ALREADY_SETTLED, Disposition.PRIOR_ATTEMPT, Disposition.UNKNOWN_EXECUTION)

    @Test fun aSnapshotRestoreDecidesLikeAScanRestoreTwoStep() = differential(DecisionMode.TWO_STEP, 1L..30L, 10_000, 320, common)

    @Test fun aSnapshotRestoreDecidesLikeAScanRestoreCaptureSettle() = differential(DecisionMode.CAPTURE_SETTLE, 1L..30L, 10_000, 320, common)

    @Test fun aSnapshotRestoreDecidesLikeAScanRestoreAtBudgets() = differential(DecisionMode.CAPTURE_SETTLE, 1L..20L, 14, 320, listOf(Disposition.BUDGET_EXCEEDED, Disposition.PRIOR_RESULT, Disposition.ALREADY_SETTLED))

    // ---- tampering: every case falls back to the scan, with the scan's result ----

    private fun bytesOf(file: Path) = Files.readAllBytes(file)

    /** The live lane snapshotted at its head; the scan restore of the same store is the expected result of every fallback. */
    private fun fixture(seed: Long = 5, mode: DecisionMode = DecisionMode.CAPTURE_SETTLE): Triple<Lane, Path, ByteArray> {
        val (live, _) = lane(mode, seed, 10_000, 500)
        val file = dir.resolve("lane.snapshot"); CapacityStore.writeSnapshot(live.ledger, binding, file)
        return Triple(live, file, bytesOf(file))
    }

    private fun assertFallsBack(live: Lane, file: Path, bytes: ByteArray, what: String, reason: String? = null, kv: MemoryLifecycleKv = live.kv) {
        Files.write(file, bytes)
        val (ledger, why) = restoreWithSnapshot(live.cfg, kv, file)
        assertNotNull(why, "$what: the snapshot must not be used")
        if (reason != null) assertTrue(why.contains(reason), "$what: refused for '$why', expected '$reason'")
        assertSameState(CapacityStore.restore(live.cfg, kv, binding, evictSettled = true)!!, ledger!!, "$what fallback")
    }

    @Test fun aChangedOrTruncatedSnapshotFallsBackToTheScan() {
        val (live, file, bytes) = fixture()
        for (at in listOf(0, 3, 9, 40, 90, 150, 200, 300, bytes.size / 3, bytes.size / 2, bytes.size - 2_000, bytes.size - 100, bytes.size - 40, bytes.size - 33, bytes.size - 1))
            assertFallsBack(live, file, bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x20).toByte() }, "byte $at flipped")
        for (at in (0 until bytes.size step 997)) assertFallsBack(live, file, bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }, "bit at $at flipped")
        for (cut in listOf(0, 2, 4, 50, bytes.size / 2, bytes.size - 33, bytes.size - 32, bytes.size - 1)) assertFallsBack(live, file, bytes.copyOf(cut), "truncated to $cut")
        assertFallsBack(live, file, bytes + byteArrayOf(0), "a trailing byte", "trailing")
        // and the untouched file is used
        Files.write(file, bytes); assertNull(restoreWithSnapshot(live.cfg, live.kv, file).second)
    }

    @Test fun aSnapshotOfAnotherBindingConfigurationOrHistoryFallsBack() {
        val (live, file, _) = fixture()
        CapacityStore.writeSnapshot(live.ledger, "c".repeat(64), file); assertFallsBack(live, file, bytesOf(file), "another binding", "different binding")
        // another configuration under the same binding: the same history replayed by a lane with other budgets
        val other = Lane(live.cfg.copy(maxControls = 41), binding, Random(1)).also { o -> live.seen.forEach { o.decide(it) }; o.flush() }
        CapacityStore.writeSnapshot(other.ledger, binding, file); assertFallsBack(live, file, bytesOf(file), "another configuration", "different configuration")
        // another history at exactly the same sequence (same binding and configuration, other inputs): a recorded decision adds one, so the loop lands on it
        val executions = (0 until 25).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
        val twin = Lane(live.cfg, binding, Random(2)); val r = Random(99); var guard = 0
        while (twin.ledger.sequence < live.ledger.sequence && guard++ < 100_000) twin.decide(randomInput(r, DecisionMode.CAPTURE_SETTLE, 5, executions, 100_000))
        twin.flush(); assertEquals(live.ledger.sequence, twin.ledger.sequence)
        CapacityStore.writeSnapshot(twin.ledger, binding, file); assertFallsBack(live, file, bytesOf(file), "another history at the same sequence", "not at the store's head")
    }

    @Test fun aSnapshotOlderOrNewerThanTheStoreFallsBack() {
        val (live, file, bytes) = fixture()
        val before = copyOf(live.kv)
        // the store moved on after the snapshot (a crash after more records, or a changelog restore to a later head after a rebalance)
        val executions = (0 until 25).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
        repeat(60) { live.decide(randomInput(live.rnd, DecisionMode.CAPTURE_SETTLE, 5, executions, 400)) }; live.flush()
        assertTrue(live.ledger.sequence > CapacityStore.restore(live.cfg, before, binding, evictSettled = true)!!.sequence)
        assertFallsBack(live, file, bytes, "a snapshot older than the store", "not at the store's head")
        // the snapshot is ahead of the store: a dirty close after records whose transaction aborted, the state wiped and restored from the changelog to the older head
        CapacityStore.writeSnapshot(live.ledger, binding, file)
        assertFallsBack(live, file, bytesOf(file), "a snapshot newer than the store", "not at the store's head", kv = before)
    }

    /** Rewrites the trailers after a change, as someone who can write the file could: then only the checks against the head can refuse it. */
    private fun resealed(bytes: ByteArray, leaves: Int, change: (ByteArray) -> Unit): ByteArray {
        val b = bytes.copyOf(); change(b)
        val trieEnd = b.size - 32; val trieStart = trieEnd - (16 + 32 + leaves * 32 + (leaves - 1) * 80 + 32)
        fun sha(from: Int, to: Int) = java.security.MessageDigest.getInstance("SHA-256").also { it.update(b, from, to - from) }.digest()
        System.arraycopy(sha(trieStart, trieEnd - 32), 0, b, trieEnd - 32, 32)
        System.arraycopy(sha(0, b.size - 32), 0, b, b.size - 32, 32)
        return b
    }

    @Test fun aResealedSnapshotWhoseTrieOrOpenWorkIsNotTheCommittedStateFallsBack() {
        val (live, file, bytes) = fixture()
        val leaves = live.ledger.entityCount
        assertTrue(live.ledger.residentObjects > 0 && leaves > 100)
        val trieStart = bytes.size - 32 - (16 + 32 + leaves * 32 + (leaves - 1) * 80 + 32)
        Files.write(file, resealed(bytes, leaves) { }); assertNull(restoreWithSnapshot(live.cfg, live.kv, file).second, "resealing alone changes nothing")
        // the trie's root digest: the trie reads and has a valid shape, but its commitment is not the head decision's
        assertFallsBack(live, file, resealed(bytes, leaves) { it[trieStart + 16] = (it[trieStart + 16].toInt() xor 1).toByte() }, "trie root changed", "committed to")
        // a leaf digest deep in the trie: the stored root still matches, so only the resident-entity check (or a later decision) could see it; a leaf that is a resident
        // position is checked, so change the amount of a resident position instead
        val marker = "e/P/".toByteArray(Charsets.ISO_8859_1)
        val at = (0 until bytes.size - marker.size).first { i -> marker.indices.all { bytes[i + it] == marker[it] } }
        val keyLength = java.nio.ByteBuffer.wrap(bytes).getInt(at - 4); val valueLength = java.nio.ByteBuffer.wrap(bytes).getInt(at + keyLength)
        val lastValueByte = at + keyLength + 4 + valueLength - 1
        assertFallsBack(live, file, resealed(bytes, leaves) { it[lastValueByte] = (it[lastValueByte].toInt() xor 1).toByte() }, "resident position changed", "not in its committed state")
        // a count changed: the counts must add up to the trie's leaves
        val countsAt = 4 + (4 + CapacityStore.SNAPSHOT_VERSION.length) + (4 + 64) + (4 + 64) + 8 + 4 + (4 + 64)
        assertFallsBack(live, file, resealed(bytes, leaves) { b -> java.nio.ByteBuffer.wrap(b).putInt(countsAt, java.nio.ByteBuffer.wrap(b).getInt(countsAt) + 1) }, "execution count changed")
        // the sequence in the header: not the store's head
        val sequenceAt = countsAt - (8 + 4 + 4 + 64)
        assertFallsBack(live, file, resealed(bytes, leaves) { b -> java.nio.ByteBuffer.wrap(b).putLong(sequenceAt, java.nio.ByteBuffer.wrap(b).getLong(sequenceAt) - 1) }, "sequence changed")
    }

    @Test fun anEmptyStoreDiscardsASnapshotAndAPlainRestoreIgnoresIt() {
        val (live, file, bytes) = fixture()
        var why: String? = null
        assertNull(CapacityStore.restore(live.cfg, MemoryLifecycleKv(), binding, evictSettled = true, snapshot = file) { why = it })
        assertFalse(Files.exists(file)); assertEquals("the store is empty", why)
        Files.write(file, bytes)
        assertSameState(CapacityStore.restore(live.cfg, live.kv, binding)!!, CapacityStore.restore(live.cfg, live.kv, binding, evictSettled = false, snapshot = file)!!, "plain")
    }

    @Test fun onlyAnEvictingLedgerWithEverythingPersistedIsSnapshotted() {
        val cfg = config(DecisionMode.TWO_STEP, 3, 100)
        val out = java.io.DataOutputStream(java.io.ByteArrayOutputStream())
        kotlin.test.assertFailsWith<IllegalStateException> { CapacityLedger(cfg).writeSnapshot(out) }
        val evicting = CapacityLedger(cfg, EntityBacking { null })
        evicting.decide(FinanceInput.Fund(ActionKey("n", "fund", "f"), Authority.OPERATOR, AccountId("acct-0"), usd, 5))
        kotlin.test.assertFailsWith<IllegalStateException> { evicting.writeSnapshot(out) }
        evicting.persisted(); evicting.writeSnapshot(out)
    }

    // ---- through the Streams processor ----

    /**
     * Three evicting lanes on the same records: one restarts through a clean close (snapshot written, then read), one restarts without a close (scan), one never stops.
     * Published bytes and stored entries must be equal; the clean one must have restarted from its snapshot every time, and the file must be gone while it runs.
     * A close after a record that failed half way writes nothing.
     */
    @Test fun theProcessorWritesASnapshotOnCloseRestartsFromItAndPublishesAndStoresTheSameBytes() {
        for (mode in DecisionMode.entries) {
            val cfg = config(mode, 6, 100_000)
            val decoder = CapacityInputDecoder { _, _, _, raw ->
                val stream = java.io.ByteArrayInputStream(raw); val items = ArrayList<CapacityItem>()
                while (stream.available() > 0) items += CapacityItem.Run(FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceInputV1.parseDelimitedFrom(stream)))
                items
            }
            class StreamsLane(name: String) {
                val stateDir: java.io.File = dir.resolve("$name-${mode.wire}").toFile().also { it.mkdirs() }
                val pc = CapacityProcessorConfig(binding, cfg, "CAPTURE", "CONTROL", "DECISIONS", "snapshot-test", evictSettled = true)
                val context = org.apache.kafka.streams.processor.api.MockProcessorContext<String, ByteArray>(java.util.Properties(), org.apache.kafka.streams.processor.TaskId(0, 0), stateDir)
                val store = CapacityTopology.storeBuilder(pc, persistent = false).withLoggingDisabled().build().also { it.init(context.getStateStoreContext(), it); context.addStateStore(it) }
                var processor = CapacityProcessor(pc, decoder).also { it.init(context) }
                var offset = 0L; val published = ArrayList<Pair<String, List<Byte>>>()
                val file: java.io.File get() = java.io.File(stateDir, "calcify-${pc.storeName}.lane-snapshot")
                fun sendAll(topic: String, inputs: List<FinanceInput>) {
                    val raw = java.io.ByteArrayOutputStream().also { out -> inputs.forEach { FinanceWire.toProto(it).writeDelimitedTo(out) } }.toByteArray()
                    context.setRecordMetadata(topic, 0, offset++); processor.process(org.apache.kafka.streams.processor.api.Record(ByteArray(0), raw, 0L))
                    context.forwarded().forEach { published += it.record().key() to it.record().value().toList() }; context.resetForwards()
                }
                fun restart(clean: Boolean) { if (clean) processor.close(); processor = CapacityProcessor(pc, decoder).also { it.init(context) } }
                fun entries(): Map<String, List<Byte>> = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value.toList() } }
            }
            val clean = StreamsLane("clean"); val dirty = StreamsLane("dirty"); val steady = StreamsLane("steady")
            val all = listOf(clean, dirty, steady)
            val rnd = Random(91 + mode.ordinal); val executions = (0 until 600).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
            var f = 0
            for (i in 0 until 6) for ((asset, amount) in listOf(usd to 20L, aapl to 6L, msft to 6L)) { val input = FinanceInput.Fund(ActionKey("n", "fund", "f${f++}"), Authority.OPERATOR, AccountId("acct-$i"), asset, amount); all.forEach { it.sendAll("CONTROL", listOf(input)) } }
            var snapshotRestarts = 0
            repeat(900) { n ->
                val inputs = (0 until 1 + rnd.nextInt(3)).map { randomInput(rnd, mode, 6, executions, 500) }
                all.forEach { it.sendAll("CAPTURE", inputs) }
                if (n % 150 == 149) {
                    clean.restart(clean = true); dirty.restart(clean = false)
                    assertEquals("snapshot", clean.processor.restoredFrom, "mode ${mode.wire} step $n"); snapshotRestarts++
                    assertFalse(clean.file.exists(), "the snapshot is gone once the lane runs")
                    assertTrue(dirty.processor.restoredFrom.startsWith("scan"), dirty.processor.restoredFrom)
                    assertEquals(steady.processor.currentLedger().commitment(), clean.processor.currentLedger().commitment())
                }
            }
            for (lane in listOf(clean, dirty)) {
                assertEquals(steady.published, lane.published, "published decisions, mode ${mode.wire}")
                assertEquals(steady.entries(), lane.entries(), "stored entries, mode ${mode.wire}")
            }
            assertEquals(6, snapshotRestarts); assertTrue(steady.published.size > 300, "the schedule recorded ${steady.published.size} decisions")

            // a record that fails half way: the ledger has moved without the store, so the close writes nothing and the restart scans
            val failing = CapacityProcessor(clean.pc, CapacityInputDecoder { _, _, _, _ -> throw IllegalStateException("decoder failed") }).also { it.init(clean.context) }
            clean.context.setRecordMetadata("CAPTURE", 0, clean.offset++)
            kotlin.test.assertFailsWith<IllegalStateException> { failing.process(org.apache.kafka.streams.processor.api.Record(ByteArray(0), ByteArray(1), 0L)) }
            failing.close()
            assertFalse(clean.file.exists(), "no snapshot after a failed record")
        }
    }
}
