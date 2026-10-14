package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import com.reef.platform.calcify.MemoryLifecycleKv
import com.reef.platform.calcify.StreamsLifecycleKv
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1

/** S2-A2: the finance processor on a mock context (restore, tamper, crash, budget) and on TopologyTestDriver (end to end). */
class FinanceProcessorTest {
    private val buyer = AccountId("buyer-account"); private val seller = AccountId("seller-account")
    private val usd = AssetId("USD"); private val aapl = AssetId("fixture-base-AAPL")
    private val captureBinding = FiniteLifecycleFixtures.binding()
    private val finance = FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("p3-opening"))
    private val bindingDigest = captureBinding.digest
    private val cfg = FinanceProcessorConfig(bindingDigest, captureBinding, finance, "CAPTURE", "CONTROL", "DECISIONS", "finance-test")
    private val ledger = FinanceLedger(finance)
    private val envelopes = FiniteLifecycleFixtures.sequence().completedRecordsList
    private val price = 100_000_000_000L

    private fun fund(id: String, account: AccountId, asset: AssetId, amount: Long) = FinanceInput.Fund(ActionKey("p3", "control", id), Authority.OPERATOR, account, asset, amount)
    private fun settle(exec: String, attempt: Int) = FinanceInput.Settle(ActionKey("p3", "repair", "$exec-$attempt"), Authority.OPERATOR, ExecutionKey("p3-run", "p3-session", "AAPL", exec), attempt)
    private fun bytes(i: FinanceInput) = FinanceWire.toProto(i).toByteArray()
    private val executionIds = envelopes.flatMap { FinanceCaptureMapping.map(it, captureBinding) }.map { (it as FinanceCaptureMapping.Mapped.Ready).input.execution.executionId }

    /** A processor on a mock context over an in-memory store that can be pre-populated before init (what a restored changelog gives). */
    private inner class Harness(preload: Map<String, ByteArray> = emptyMap(), val config: FinanceProcessorConfig = cfg) {
        val context = MockProcessorContext<String, ByteArray>()
        val store: KeyValueStore<String, ByteArray> = FinanceTopology.storeBuilder(config, persistent = false).withLoggingDisabled().build()
        val processor: FinanceProcessor
        init {
            store.init(context.getStateStoreContext(), store)
            context.addStateStore(store)
            preload.forEach { (k, v) -> store.put(k, v) }
            processor = FinanceProcessor(config)
            processor.init(context)
        }
        var captureOffset = 0L; var controlOffset = 0L
        fun send(topic: String, offset: Long, value: ByteArray) {
            context.setRecordMetadata(topic, 0, offset)
            processor.process(Record(ByteArray(0), value, 0L))
        }
        fun capture(value: ByteArray) = send("CAPTURE", captureOffset++, value)
        fun control(value: ByteArray) = send("CONTROL", controlOffset++, value)
        fun decisions(): List<Decision> = context.forwarded().map { FinanceWire.fromProto(FinanceDecisionV1.parseFrom(it.record().value())) }
        fun snapshot(): Map<String, ByteArray> = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value } }
        fun kv() = StreamsLifecycleKv(store)
        fun entries() = FinanceStore.entries(kv())
    }

    private fun fundBoth(h: Harness) {
        h.control(bytes(fund("open-seller", seller, aapl, 6)))
        h.control(bytes(fund("open-buyer", buyer, usd, 6 * price)))
    }
    private fun feedAllCaptures(h: Harness) = envelopes.forEach { h.capture(it.toByteArray()) }

    @Test fun packetScenarioFundThenCaptureThenThreeRepairsSettlesEachExecutionOnce() {
        val h = Harness()
        fundBoth(h); feedAllCaptures(h)
        executionIds.forEach { h.control(bytes(settle(it, 1))) }
        val d = h.decisions()
        assertEquals(listOf(Disposition.FUNDED, Disposition.FUNDED) + List(3) { Disposition.CAPTURED } + List(3) { Disposition.SETTLED }, d.map { it.disposition })
        assertTrue(ledger.verifyChain(d))
        val entries = h.entries()
        assertEquals(2 + envelopes.size - 2 + 3 + 3, entries.size, "10 NO_INPUT + 3 captures + 2 funds + 3 repairs")
        assertEquals(10, entries.count { it.kind == FinanceStore.EntryKind.NO_INPUT })
        assertEquals(listOf(0, 1), entries.filter { it.sourceRole == "CAPTURE" && it.sourceOffset == 2L }.map { it.memberOrdinal })
        assertEquals(d.size, entries.count { it.decisionChecksum.isNotEmpty() })
        // The stored state equals the decided state: restoring from the store reproduces the ledger's final state.
        val restored = Harness(h.snapshot()).entries().size
        assertEquals(entries.size, restored)
    }

    @Test fun twoInterleavingsAreBothValidAndEachIsReproducibleFromItsRecordedHistory() {
        val a = Harness().also { fundBoth(it); feedAllCaptures(it); executionIds.forEach { e -> it.control(bytes(settle(e, 1))) } }
        val b = Harness().also { feedAllCaptures(it); executionIds.forEach { e -> it.control(bytes(settle(e, 1))) }; fundBoth(it); executionIds.forEach { e -> it.control(bytes(settle(e, 2))) } }
        assertTrue(a.decisions().all { it.disposition != Disposition.INSUFFICIENT_CASH })
        assertTrue(b.decisions().any { it.disposition == Disposition.INSUFFICIENT_CASH || it.disposition == Disposition.INSUFFICIENT_SHARES })
        assertNotEquals(a.decisions().map { it.checksum }, b.decisions().map { it.checksum })
        for (h in listOf(a, b)) assertTrue(ledger.verifyChain(h.decisions()))
        // Replay the recorded INPUT history (the decisions' own carried inputs) through a fresh ledger: byte-equal decisions.
        for (h in listOf(a, b)) {
            val inputs = h.context.forwarded().map { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.record().value())).second }
            var s = ledger.genesis(); val replay = mutableListOf<Decision>()
            for (i in inputs) ledger.decide(s, i).also { s = it.state; it.decision?.let(replay::add) }
            assertEquals(h.decisions(), replay)
        }
    }

    @Test fun restartRestoresVerifiedStateFromTheStoreAndContinuesIdentically() {
        val straight = Harness().also { fundBoth(it); feedAllCaptures(it); executionIds.forEach { e -> it.control(bytes(settle(e, 1))) } }
        val first = Harness().also { fundBoth(it); feedAllCaptures(it) }
        val second = Harness(first.snapshot()).also { it.captureOffset = first.captureOffset; it.controlOffset = first.controlOffset }
        executionIds.forEach { second.control(bytes(settle(it, 1))) }
        assertEquals(straight.decisions().map { it.checksum }, (first.decisions() + second.decisions()).map { it.checksum })
        assertEquals(straight.entries().map { it.entryChain }, second.entries().map { it.entryChain })
    }

    @Test fun redeliveringARecordIsRecordedAsPriorResultAndChangesNoLedgerState() {
        val h = Harness()
        val f = bytes(fund("open-seller", seller, aapl, 6))
        h.control(f); val after = h.snapshot().filterKeys { !it.startsWith("h/") && it != "meta" }
        h.send("CONTROL", 0L, f)     // same source position again: a producer-side duplicate or reprocess
        assertEquals(1, h.decisions().size)
        assertEquals(listOf("FUNDED", "PRIOR_RESULT"), h.entries().map { it.outcome })
        assertEquals(after, h.snapshot().filterKeys { !it.startsWith("h/") && it != "meta" })
    }

    @Test fun badBodiesAreInvalidInputEntriesNotStallsOrSkips() {
        val h = Harness()
        val capturedFund = bytes(fund("x", seller, aapl, 1))
        h.control(byteArrayOf(1, 2, 3))                                                // unparseable
        h.control(bytes(FinanceInput.Capture(ActionKey("a", "b", "c"), ExecutionKey("p3-run", "p3-session", "AAPL", "e"), buyer, seller, 1, 1)))  // Capture on CONTROL
        h.capture(capturedFund)                                                         // Fund body on CAPTURE
        h.capture(envelopes[2].toBuilder().setFiniteBindingDigest("0".repeat(64)).build().toByteArray()) // wrong binding
        h.capture(envelopes[2].toBuilder().setContentDigest("0".repeat(64)).build().toByteArray())       // wrong content digest
        assertEquals(0, h.decisions().size)
        assertEquals(List(5) { "INVALID_INPUT" }, h.entries().map { it.outcome })
        assertTrue(h.entries().all { it.kind == FinanceStore.EntryKind.INPUT && it.decisionChecksum.isEmpty() })
        // The processor is still live afterwards.
        h.control(capturedFund); assertEquals(1, h.decisions().size)
    }

    @Test fun anInvalidReadyMemberIsAnInvalidEntryAndItsSiblingStillBooks() {
        val e = envelopes[2]
        val i = e.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val broken = e.toBuilder().setMembers(i, e.getMembers(i).toBuilder().also { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("0") }).build()
        val rebuilt = broken.toBuilder().setContentDigest(FiniteLifecycleContract.digest(broken)).build()
        val h = Harness(); fundBoth(h); h.capture(ByteArray(0).let { envelopes[0].toByteArray() }); h.capture(envelopes[1].toByteArray()); h.capture(rebuilt.toByteArray())
        val tail = h.entries().takeLast(2)
        assertEquals(listOf("INVALID_INPUT", "CAPTURED"), tail.map { it.outcome })
        assertEquals(listOf(0, 1), tail.map { it.memberOrdinal })
    }

    @Test fun historyBoundHaltsFailClosedAndTheRejectedRecordLeavesTheStoreUntouched() {
        val h = Harness()
        repeat(FinanceStore.MAX_HISTORY_ENTRIES) { h.control(byteArrayOf(it.toByte())) }
        val before = h.snapshot()
        assertFailsWith<FinanceHistoryBudgetExceeded> { h.control(byteArrayOf(9)) }
        assertEquals(before.keys, h.snapshot().keys)
        assertTrue(before.all { (k, v) -> h.snapshot()[k]!!.contentEquals(v) })
    }

    @Test fun aRecordThatFailsMidwayLeavesNoPartialWrites() {
        // Two ready members in one envelope; the second input forces the ledger past a pinned budget by construction is not possible
        // here, so inject the failure through the history bound: the envelope needs two entries but only one slot remains.
        val h = Harness()
        repeat(FinanceStore.MAX_HISTORY_ENTRIES - 1) { h.control(byteArrayOf(it.toByte())) }
        val before = h.snapshot()
        assertFailsWith<FinanceHistoryBudgetExceeded> { h.capture(envelopes[2].toByteArray()) }
        assertEquals(before.keys, h.snapshot().keys, "the first member's entry and entities were not written")
    }

    @Test fun restoreRefusesEveryTamperedStore() {
        val h = Harness().also { fundBoth(it); feedAllCaptures(it); executionIds.forEach { e -> it.control(bytes(settle(e, 1))) } }
        val good = h.snapshot()
        Harness(good) // clean restore passes
        fun refuses(name: String, mutate: MutableMap<String, ByteArray>.() -> Unit) {
            val bad = good.toMutableMap().apply(mutate)
            assertFailsWith<IllegalArgumentException>(name) { Harness(bad) }
        }
        val position = good.keys.first { it.startsWith("position/") }
        refuses("position changed") { this[position] = FinanceStoreTestSupport.positionBytes(123) }
        refuses("position removed") { remove(position) }
        refuses("execution removed") { remove(keys.first { it.startsWith("execution/") }) }
        refuses("obligation removed") { remove(keys.first { it.startsWith("obligation/") }) }
        refuses("attempt removed") { remove(keys.first { it.startsWith("attempt/") }) }
        refuses("action removed") { remove(keys.first { it.startsWith("action/") }) }
        refuses("head removed") { remove(FinanceStore.HEAD) }
        refuses("meta removed") { remove(FinanceStore.META) }
        refuses("history entry removed") { remove(keys.first { it.startsWith("h/") }) }
        refuses("last history entry removed") { remove(keys.last { it.startsWith("h/") }) }
        refuses("history entry reordered") { val ks = keys.filter { it.startsWith("h/") }; val a = this[ks[0]]!!; this[ks[0]] = this[ks[1]]!!; this[ks[1]] = a }
        refuses("history entry rewritten") {
            val k = keys.first { it.startsWith("h/") }
            this[k] = FinanceStore.Entry.parse(this[k]!!).let { it.copy(inputDigest = "f".repeat(64)) }.bytes()
        }
        refuses("meta controls changed") { this[FinanceStore.META] = FinanceStore.Meta.parse(this[FinanceStore.META]!!).let { it.copy(controls = it.controls + 1) }.bytes() }
        refuses("meta sequence changed") { this[FinanceStore.META] = FinanceStore.Meta.parse(this[FinanceStore.META]!!).let { it.copy(sequence = it.sequence - 1) }.bytes() }
        refuses("meta binding changed") { this[FinanceStore.META] = FinanceStore.Meta.parse(this[FinanceStore.META]!!).copy(bindingDigest = "0".repeat(64)).bytes() }
        refuses("garbage entity") { this[position] = byteArrayOf(0) }
        refuses("unknown entity version") { this[position] = Canon.bytes { str("calcify-finance-entity-v9"); long(1) } }
        // An entity-only forgery that recomputes nothing is caught; so is a store with entities and no meta at all.
        assertFailsWith<IllegalArgumentException> { Harness(good.filterKeys { it.startsWith("position/") }) }
    }

    /** Rewrites one history entry and re-chains everything after it, so the store stays internally consistent (chain, meta head) but differs from the truth. */
    private fun forge(snapshot: Map<String, ByteArray>, index: Int, transform: (FinanceStore.Entry) -> FinanceStore.Entry): MutableMap<String, ByteArray> {
        val out = snapshot.toMutableMap()
        val keys = out.keys.filter { it.startsWith("h/") }.sorted()
        var prev = if (index == 0) FinanceStore.genesisEntryChain(ledger) else FinanceStore.Entry.parse(out[keys[index - 1]]!!).entryChain
        for (i in index until keys.size) {
            val original = FinanceStore.Entry.parse(out[keys[i]]!!)
            val e = (if (i == index) transform(original) else original).copy(prevEntryChain = prev)
            val chain = FinanceStore.entryChain(prev, e.position, e.sourceRole, e.sourcePartition, e.sourceOffset, e.memberOrdinal, e.kind, e.inputDigest, e.outcome, e.decisionChecksum)
            out[keys[i]] = e.copy(entryChain = chain).bytes(); prev = chain
        }
        out[FinanceStore.META] = FinanceStore.Meta.parse(out[FinanceStore.META]!!).copy(entryChainHead = prev).bytes()
        return out
    }

    @Test fun historyVerificationChecksEachRuleIndependently() {
        val h = fullRun(); val good = h.snapshot(); val kv = MemoryLifecycleKv().also { it.apply(good) }
        val meta = FinanceStore.Meta.parse(good[FinanceStore.META]!!)
        val head = FinanceWire.fromProto(FinanceDecisionV1.parseFrom(good[FinanceStore.HEAD]!!))
        val entries = FinanceStore.entries(kv)
        FinanceStore.verifyHistory(ledger, entries, meta, head)
        fun refuses(name: String, e: List<FinanceStore.Entry> = entries, m: FinanceStore.Meta = meta, d: Decision? = head) =
            assertFailsWith<IllegalArgumentException>(name) { FinanceStore.verifyHistory(ledger, e, m, d) }
        refuses("meta head", m = meta.copy(entryChainHead = "0".repeat(64)))
        refuses("meta length", m = meta.copy(historyPosition = meta.historyPosition - 1))
        refuses("recorded vs sequence", m = meta.copy(sequence = meta.sequence - 1))
        refuses("head decision", d = null)
        refuses("prev link only", e = entries.toMutableList().also { it[3] = it[3].copy(prevEntryChain = "1".repeat(64)) })
        refuses("position only", e = entries.toMutableList().also { it[3] = it[3].copy(position = 99) })
        refuses("chain value only", e = entries.toMutableList().also { it[3] = it[3].copy(entryChain = "2".repeat(64)) })
        refuses("NO_INPUT with a decision", e = entries.toMutableList().also { val i = it.indexOfFirst { x -> x.kind == FinanceStore.EntryKind.NO_INPUT }; it[i] = it[i].copy(decisionChecksum = "3".repeat(64)) })
        refuses("NO_INPUT with wrong outcome", e = entries.toMutableList().also { val i = it.indexOfFirst { x -> x.kind == FinanceStore.EntryKind.NO_INPUT }; it[i] = it[i].copy(outcome = "FUNDED") })
    }

    @Test fun entityDecodingIsStrictAboutVersionAndTrailingBytes() {
        val good = fullRun().snapshot()
        val key = good.keys.first { it.startsWith("position/") }
        val value = good[key]!!
        assertFailsWith<IllegalArgumentException>("trailing byte") { Harness(good.toMutableMap().also { it[key] = value + byteArrayOf(0) }) }
        val original = java.io.DataInputStream(java.io.ByteArrayInputStream(value)).let { it.readNBytes(it.readInt()); it.readLong() }
        assertFailsWith<IllegalArgumentException>("other version, same payload") {
            Harness(good.toMutableMap().also { it[key] = Canon.bytes { str("calcify-finance-entity-v9"); long(original) } })
        }
    }

    @Test fun historyBoundIsExactlyThreeHundredTwelve() {
        assertEquals(312, FinanceStore.MAX_HISTORY_ENTRIES)
        val h = Harness()
        repeat(312) { h.control(byteArrayOf(it.toByte())) }
        assertEquals(312, h.entries().size)
        assertFailsWith<FinanceHistoryBudgetExceeded> { h.control(byteArrayOf(1)) }
    }

    @Test fun captureEnvelopeIdentityIsCheckedIndependentlyOfItsContentDigest() {
        fun resealed(e: reef.contracts.calcify.v1.FiniteLifecycleCaptureV1) = e.toBuilder().setContentDigest(FiniteLifecycleContract.digest(e)).build()
        val h = Harness(); fundBoth(h)
        h.capture(resealed(envelopes[2].toBuilder().setFiniteBindingDigest("0".repeat(64)).build()).toByteArray())
        h.capture(resealed(envelopes[2].toBuilder().setSchema("calcify-finite-lifecycle-capture-v0").build()).toByteArray())
        assertEquals(listOf("INVALID_INPUT", "INVALID_INPUT"), h.entries().takeLast(2).map { it.outcome })
        assertEquals(2, h.decisions().size, "only the two funds were decided")
        h.capture(envelopes[2].toByteArray())      // the genuine envelope still books
        assertEquals(4, h.decisions().size)
    }

    @Test fun restoreCutCrossChecksHistoryAgainstTheDecisionLogEvenWhenTheStoreIsInternallyConsistent() {
        val h = fullRun()
        val index = FinanceStore.entries(MemoryLifecycleKv().also { it.apply(h.snapshot()) }).indexOfFirst { it.decisionChecksum.isNotEmpty() }
        val inputForged = forge(h.snapshot(), index) { it.copy(inputDigest = "a".repeat(64)) }
        assertEquals(RestoreRefusal.FINANCE_HISTORY_CROSS_CHECK, refusal(evidence(h, store = inputForged)), "input digest differs from the decision's")
        val checksumForged = forge(h.snapshot(), index) { it.copy(decisionChecksum = "b".repeat(64)) }
        assertEquals(RestoreRefusal.FINANCE_HISTORY_CROSS_CHECK, refusal(evidence(h, store = checksumForged)), "decision checksum differs from the log")
    }

    @Test fun pinnedStreamsConfigurationIsEnforcedAndRefusesWeakerSettings() {
        val good = FinanceTopology.streamsProperties("finance", "localhost:1", "/tmp/x")
        FinanceTopology.verifyPinned(good)
        for ((k, v) in mapOf("processing.guarantee" to "at_least_once", "num.stream.threads" to 2, "num.standby.replicas" to 1, "auto.offset.reset" to "latest")) {
            assertFailsWith<IllegalArgumentException>(k) { FinanceTopology.verifyPinned(java.util.Properties().apply { putAll(good); put(k, v) }) }
        }
        assertFailsWith<IllegalArgumentException> { FinanceTopology.verifyPinned(java.util.Properties().apply { putAll(good); remove("max.task.idle.ms") }) }
        assertEquals(262_144, good["producer.batch.size"]); assertEquals(20, good["producer.linger.ms"])
        for (batch in listOf(16_384, 262_145, 1_048_576)) assertFailsWith<IllegalArgumentException>("batch $batch") { FinanceTopology.verifyPinned(java.util.Properties().apply { putAll(good); put("producer.batch.size", batch) }) }
        for (linger in listOf(0, 19, 21, 500)) assertFailsWith<IllegalArgumentException>("linger $linger") { FinanceTopology.verifyPinned(java.util.Properties().apply { putAll(good); put("producer.linger.ms", linger) }) }
        for (k in listOf("producer.batch.size", "producer.linger.ms")) assertFailsWith<IllegalArgumentException>("missing $k") { FinanceTopology.verifyPinned(java.util.Properties().apply { putAll(good); remove(k) }) }
    }

    @Test fun storeKeysAreLocaleIndependent() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA-u-nu-arab"))
            assertEquals("h/00000000000000000007", FinanceStore.historyKey(7))
            assertTrue(FinanceStore.attemptKey(ExecutionKey("r", "s", "i", "e"), 3).endsWith("/0000000003"))
        } finally { java.util.Locale.setDefault(saved) }
    }

    @Test fun anUndecodableHeadOrMalformedKeyIsAStableRefusalNotAnUncodedCrash() {
        val h = fullRun(); val good = h.snapshot()
        assertEquals(RestoreRefusal.FINANCE_STORE_INVALID, refusal(evidence(h, store = good.toMutableMap().also { it[FinanceStore.HEAD] = byteArrayOf(0x0a, 0x7f) })))
        assertEquals(RestoreRefusal.FINANCE_STORE_INVALID, refusal(evidence(h, store = good.toMutableMap().also { it["position/zz"] = good[good.keys.first { k -> k.startsWith("position/") }]!! })))
    }

    @Test fun anotherBindingNeverAdoptsThisStore() {
        val h = Harness().also { fundBoth(it) }
        val other = FiniteLifecycleContract.sha("binding-v1".toByteArray())
        val otherConfig = cfg.copy(bindingDigest = other, captureBinding = captureBinding.copy(digest = other))
        assertFailsWith<IllegalArgumentException> { Harness(h.snapshot(), otherConfig) }
    }

    @Test fun theFinanceBindingDigestMustEqualTheCaptureLanesDigest() {
        assertFailsWith<IllegalArgumentException> { cfg.copy(bindingDigest = FiniteLifecycleContract.sha("different".toByteArray())) }
    }

    @Test fun entryChainMatchesAnIndependentEncoderGolden() {
        val h = Harness(); h.control(bytes(fund("open-seller", seller, aapl, 6)))
        val e = h.entries().single()
        // Independent framing: big-endian int32 length + UTF-8 for text, int64/int32 raw, exactly as spec section 8 states.
        val out = java.io.ByteArrayOutputStream(); val d = java.io.DataOutputStream(out)
        fun frame(s: String) { val b = s.toByteArray(Charsets.UTF_8); d.writeInt(b.size); d.write(b) }
        val genesis = java.security.MessageDigest.getInstance("SHA-256").digest(java.io.ByteArrayOutputStream().also { o ->
            java.io.DataOutputStream(o).also { x -> val a = "calcify-finance-history-genesis-v1".toByteArray(); x.writeInt(a.size); x.write(a); val c = ledger.genesisChain.toByteArray(); x.writeInt(c.size); x.write(c) } }.toByteArray())
        val genesisHex = java.util.HexFormat.of().formatHex(genesis)
        frame("calcify-finance-history-entry-v1"); frame(genesisHex); d.writeLong(1); frame("CONTROL"); d.writeInt(0); d.writeLong(0); d.writeInt(0)
        frame("INPUT"); frame(e.inputDigest); frame("FUNDED"); frame(e.decisionChecksum)
        val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(out.toByteArray()))
        assertEquals(expected, e.entryChain)
        assertEquals(genesisHex, e.prevEntryChain)
    }

    // ---- restore cut (spec section 8): evidence in, stable refusal code out ----
    private fun evidence(h: Harness, store: Map<String, ByteArray> = h.snapshot(), decisions: List<ByteArray> = h.context.forwarded().map { it.record().value() },
                         capture: Long? = h.captureOffset, control: Long? = h.controlOffset, bindingDigest: String = this.bindingDigest) =
        FinanceRestoreEvidence(bindingDigest, MemoryLifecycleKv().also { it.apply(store) }, decisions, capture, control, h.captureOffset, h.controlOffset)
    private fun refusal(evidence: FinanceRestoreEvidence) = assertFailsWith<FinanceRestoreRefused> { FinanceRestoreCut.verify(ledger, evidence) }.code
    private fun fullRun() = Harness().also { fundBoth(it); feedAllCaptures(it); executionIds.forEach { e -> it.control(bytes(settle(e, 1))) } }

    @Test fun restoreCutAcceptsTheConsistentEvidenceAndReturnsTheHeadDecision() {
        val h = fullRun()
        val cut = FinanceRestoreCut.verify(ledger, evidence(h))
        assertEquals(8L, cut.state.sequence); assertEquals(h.decisions().last(), cut.headDecision)
        val genesis = FinanceRestoreCut.verify(ledger, FinanceRestoreEvidence(bindingDigest, MemoryLifecycleKv(), emptyList(), null, null, 0, 0))
        assertEquals(ledger.genesis(), genesis.state); assertEquals(null, genesis.headDecision)
    }

    @Test fun restoreCutRefusesEveryInconsistentEvidenceWithAStableCode() {
        val h = fullRun()
        val log = h.context.forwarded().map { it.record().value() }
        fun code(name: String, e: FinanceRestoreEvidence, expected: RestoreRefusal) = assertEquals(expected, refusal(e), name)
        code("offset ahead", evidence(h, capture = h.captureOffset + 1), RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY)
        code("control ahead", evidence(h, control = h.controlOffset + 3), RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY)
        code("history ahead", evidence(h, capture = h.captureOffset - 1), RestoreRefusal.FINANCE_HISTORY_AHEAD_OF_OFFSET)
        code("control history ahead", evidence(h, control = h.controlOffset - 1), RestoreRefusal.FINANCE_HISTORY_AHEAD_OF_OFFSET)
        code("expired absent", evidence(h, capture = null), RestoreRefusal.FINANCE_GROUP_OFFSETS_EXPIRED)
        code("expired reset", evidence(h, control = 0L), RestoreRefusal.FINANCE_GROUP_OFFSETS_EXPIRED)
        code("decision truncated", evidence(h, decisions = log.dropLast(1)), RestoreRefusal.FINANCE_STORE_DISAGREES_WITH_DECISIONS)
        code("decision removed from middle", evidence(h, decisions = log.filterIndexed { i, _ -> i != 3 }), RestoreRefusal.FINANCE_DECISION_LOG_INVALID)
        code("decision reordered", evidence(h, decisions = log.toMutableList().also { val t = it[0]; it[0] = it[1]; it[1] = t }), RestoreRefusal.FINANCE_DECISION_LOG_INVALID)
        code("decision garbage", evidence(h, decisions = log + byteArrayOf(1, 2)), RestoreRefusal.FINANCE_DECISION_LOG_INVALID)
        code("store lost", evidence(h, store = emptyMap()), RestoreRefusal.FINANCE_STORE_LOST)
        code("store tampered", evidence(h, store = h.snapshot().toMutableMap().also { it.remove(FinanceStore.HEAD) }), RestoreRefusal.FINANCE_STORE_INVALID)
        code("other binding", evidence(h, bindingDigest = FiniteLifecycleContract.sha("other".toByteArray())), RestoreRefusal.FINANCE_STORE_INVALID)
        code("genesis with committed offset", FinanceRestoreEvidence(bindingDigest, MemoryLifecycleKv(), emptyList(), 5L, null, 0, 0), RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY)
        code("decision log from another run", evidence(h, decisions = Harness().also { x -> x.control(bytes(fund("open-seller", seller, aapl, 7))) }.context.forwarded().map { it.record().value() }),
            RestoreRefusal.FINANCE_STORE_DISAGREES_WITH_DECISIONS)
    }

    @Test fun theGroupMayCommitPastAbortedRecordsAndMarkersButNeverPastAVisibleOne() {
        val h = fullRun()
        fun withVisible(capture: Long, control: Long, end: Long, visible: Long?) = FinanceRestoreEvidence(bindingDigest, MemoryLifecycleKv().also { it.apply(h.snapshot()) },
            h.context.forwarded().map { it.record().value() }, capture, control, end, end, firstVisibleAtOrAfter = { _, _ -> visible })
        val c = h.captureOffset; val k = h.controlOffset
        // Aborted tail: nothing visible after history, committed moved past it (Redpanda/Kafka marker offsets).
        FinanceRestoreCut.verify(ledger, withVisible(c + 5, k + 5, maxOf(c, k) + 5, null))
        // The committed offset skips a visible unprocessed record: refused.
        assertEquals(RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY, refusal(withVisible(c + 5, k, maxOf(c, k) + 5, c + 2)))
        // A visible record exactly at the committed offset is ordinary lag, not a skip.
        FinanceRestoreCut.verify(ledger, withVisible(c + 2, k, maxOf(c, k) + 5, c + 2))
        // Beyond the read-committed end is always refused.
        assertEquals(RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY, refusal(withVisible(c + 9, k, maxOf(c, k) + 5, null)))
    }

    @Test fun anAbsentCommittedOffsetIsZeroOnlyWhenTheTopicHasNoHistory() {
        val h = Harness().also { it.control(bytes(fund("open-seller", seller, aapl, 6))) }   // CONTROL history only; CAPTURE untouched
        val cut = FinanceRestoreCut.verify(ledger, evidence(h, capture = null))
        assertEquals(1L, cut.state.sequence)
        assertEquals(RestoreRefusal.FINANCE_GROUP_OFFSETS_EXPIRED, refusal(evidence(h, control = null)))
    }

    /**
     * S2-A2 measurement: the per-entity store cost on the real processor path (in-memory store, mock context). Harness construction is
     * excluded; only the feed loop is timed. Reported; the only assertion is a sanity floor, not a capacity claim.
     */
    @Test fun measuresDecisionsPerSecondAndBytesPerKeyOnTheStoreBackedPath() {
        val funds = listOf(bytes(fund("open-seller", seller, aapl, 6)), bytes(fund("open-buyer", buyer, usd, 6 * price)))
        val captures = envelopes.map { it.toByteArray() }
        val settles = executionIds.map { bytes(settle(it, 1)) }
        fun feed(h: Harness) { funds.forEach(h::control); captures.forEach(h::capture); settles.forEach(h::control) }
        repeat(3000) { feed(Harness()) }   // warm-up: JIT needs tens of thousands of records to reach steady state
        var decisions = 0; var records = 0; var nanos = 0L
        repeat(3000) {
            val h = Harness()
            val started = System.nanoTime(); feed(h); nanos += System.nanoTime() - started
            decisions += h.context.forwarded().size; records += funds.size + captures.size + settles.size
        }
        val seconds = nanos / 1e9
        val snapshot = Harness().also { feed(it) }.snapshot()
        val byPrefix = snapshot.entries.groupBy { it.key.substringBefore('/') }.mapValues { (_, v) -> v.size to v.sumOf { it.value.size + it.key.length } }
        println("FINANCE-MEASURE records=$records decisions=$decisions seconds=%.3f recordsPerSecond=%.0f decisionsPerSecond=%.0f".format(seconds, records / seconds, decisions / seconds))
        println("FINANCE-MEASURE keys=${snapshot.size} totalBytes=${snapshot.entries.sumOf { it.value.size + it.key.length }} byPrefix=$byPrefix")
        assertTrue(decisions / seconds > 1_000, "store-backed path far below any plausible floor")
    }

    @Test fun topologyEndToEndOnTheTestDriverProducesTheVerifiedDecisionLog() {
        val props = Properties().apply { put(StreamsConfig.APPLICATION_ID_CONFIG, "finance-e2e"); put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234") }
        TopologyTestDriver(FinanceTopology.build(cfg, persistent = false), props).use { driver ->
            val capture = driver.createInputTopic("CAPTURE", ByteArraySerializer(), ByteArraySerializer())
            val control = driver.createInputTopic("CONTROL", ByteArraySerializer(), ByteArraySerializer())
            control.pipeInput(ByteArray(0), bytes(fund("open-seller", seller, aapl, 6)))
            control.pipeInput(ByteArray(0), bytes(fund("open-buyer", buyer, usd, 6 * price)))
            envelopes.forEach { capture.pipeInput(ByteArray(0), it.toByteArray()) }
            executionIds.forEach { control.pipeInput(ByteArray(0), bytes(settle(it, 1))) }
            val out = driver.createOutputTopic("DECISIONS", StringDeserializer(), ByteArrayDeserializer()).readKeyValuesToList()
            val decisions = out.map { FinanceWire.fromProto(FinanceDecisionV1.parseFrom(it.value)) }
            assertEquals(8, decisions.size)
            assertTrue(ledger.verifyChain(decisions))
            assertEquals(decisions.map { it.sequence.toString().padStart(20, '0') }, out.map { it.key })
            assertEquals(Disposition.SETTLED, decisions.last().disposition)
            val store = driver.getKeyValueStore<String, ByteArray>("finite-finance")
            val kv = StreamsLifecycleKv(store)
            val (state, meta) = FinanceStore.verifyAndRestore(ledger, kv, bindingDigest)!!
            assertEquals(8L, state.sequence); assertEquals(decisions.last().checksum, state.chain)
            assertEquals(meta.historyPosition, FinanceStore.entries(kv).size.toLong())
        }
    }
}

internal object FinanceStoreTestSupport {
    fun positionBytes(v: Long) = Canon.bytes { str(FinanceStore.ENTITY_VERSION); long(v) }
}
