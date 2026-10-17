package com.reef.platform.calcify.finance

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ledger that evicts settled objects and reads them back from its store must decide exactly like one that keeps everything: same dispositions, same decisions (so the
 * same chain and state commitments), same entity writes, on random schedules that replay old actions and executions, conflict with them, settle them again, fail
 * and retry settlements, and stop at budgets. The store here is a map fed with the ledger's own writes, applied after a random number of decisions to model a record
 * that carries several, so a decision may need an entity that has been written but not yet applied.
 */
class CapacityEvictionTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val msft = AssetId("MSFT"); private val opening = AccountId("opening")

    private class MapStore : EntityBacking {
        val entries = HashMap<String, ByteArray>(); var reads = 0
        override fun get(storeKey: String): ByteArray? { reads++; return entries[storeKey] }
        private val group = LinkedHashMap<String, ByteArray>()
        /** Takes the writes of the decision the ledger just recorded; they reach [entries] only at [flush], as a consumed record's writes reach the store in one batch. */
        fun collect(ledger: CapacityLedger) { for (e in ledger.lastWrites) group[CapacityStore.entityKey(e)] = CapacityStore.entityValue(e) }
        fun flush() { entries.putAll(group); group.clear() }
    }

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

    private fun differential(mode: DecisionMode, seeds: LongRange, steps: Int, accounts: Int, budgets: Int, executionsPool: Int, actions: Int, expectBudgetRefusals: Boolean = false) {
        var settledReadBack = 0; val seen = HashSet<Disposition>()
        for (seed in seeds) {
            val rnd = Random(seed)
            val cfg = config(mode, accounts, budgets)
            val plain = CapacityLedger(cfg); val store = MapStore(); val evicting = CapacityLedger(cfg, store)
            val executions = (0 until executionsPool).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
            // seed the books so trades can settle: fund every account with cash and both securities
            var f = 0
            fun fund(a: AccountId, asset: AssetId, amount: Long) { val input = FinanceInput.Fund(ActionKey("n", "fund", "f${f++}"), Authority.OPERATOR, a, asset, amount); plain.decide(input); evicting.decide(input); store.collect(evicting); store.flush(); evicting.persisted() }
            for (i in 0 until accounts) { fund(AccountId("acct-$i"), usd, 8); fund(AccountId("acct-$i"), aapl, 3); fund(AccountId("acct-$i"), msft, 3) }
            var inGroup = 0; var groupSize = 1 + rnd.nextInt(3)
            for (n in 0 until steps) {
                val input = randomInput(rnd, mode, accounts, executions, actions)
                val a = plain.decide(input); val b = evicting.decide(input)
                assertEquals(a.disposition, b.disposition, "seed $seed step $n $input")
                assertEquals(a.decision, b.decision, "decision seed $seed step $n")
                assertEquals(plain.lastWrites.map { CapacityStore.entityKey(it) to CapacityStore.entityValue(it).toList() }, evicting.lastWrites.map { CapacityStore.entityKey(it) to CapacityStore.entityValue(it).toList() }, "writes seed $seed step $n")
                seen += a.disposition
                if (a.disposition == Disposition.ALREADY_SETTLED || a.disposition == Disposition.EXECUTION_ALREADY_CAPTURED || a.disposition == Disposition.PRIOR_RESULT) settledReadBack++
                store.collect(evicting)
                if (++inGroup >= groupSize) { store.flush(); evicting.persisted(); inGroup = 0; groupSize = 1 + rnd.nextInt(3) }
            }
            store.flush(); evicting.persisted()
            assertEquals(plain.sequence, evicting.sequence); assertEquals(plain.chain, evicting.chain); assertEquals(plain.controls, evicting.controls)
            assertEquals(plain.commitment(), evicting.commitment()); assertEquals(plain.entityCount, evicting.entityCount)
            assertTrue(evicting.residentObjects <= plain.residentObjects, "an evicting ledger holds no more objects than a plain one")
        }
        assertTrue(settledReadBack > 50, "the schedule read back $settledReadBack evicted entities")
        for (d in listOf(Disposition.CAPTURED, Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.PRIOR_RESULT, Disposition.ACTION_CONFLICT, Disposition.EXECUTION_ALREADY_CAPTURED, Disposition.EXECUTION_CONFLICT, Disposition.ALREADY_SETTLED, Disposition.PRIOR_ATTEMPT, Disposition.UNKNOWN_EXECUTION) + (if (expectBudgetRefusals) listOf(Disposition.BUDGET_EXCEEDED) else emptyList()))
            assertTrue(d in seen, "the differential never produced $d")
    }

    @Test fun evictingMatchesKeepingEverythingTwoStep() = differential(DecisionMode.TWO_STEP, 1L..120L, 400, accounts = 5, budgets = 10_000, executionsPool = 25, actions = 300)

    @Test fun evictingMatchesKeepingEverythingCaptureSettle() = differential(DecisionMode.CAPTURE_SETTLE, 1L..120L, 400, accounts = 5, budgets = 10_000, executionsPool = 25, actions = 300)

    @Test fun evictingMatchesKeepingEverythingAtBudgets() = differential(DecisionMode.CAPTURE_SETTLE, 1L..60L, 300, accounts = 4, budgets = 12, executionsPool = 30, actions = 200, expectBudgetRefusals = true)

    @Test fun settledTradesLeaveTheHeapAndStayInTheStore() {
        val cfg = config(DecisionMode.CAPTURE_SETTLE, 4, 1_000_000)
        val store = MapStore(); val ledger = CapacityLedger(cfg, store); var f = 0
        fun apply() { store.collect(ledger); store.flush(); ledger.persisted() }
        for (i in 0 until 4) for ((asset, amount) in listOf(usd to 1_000_000L, aapl to 1_000_000L)) { ledger.decide(FinanceInput.Fund(ActionKey("n", "fund", "f${f++}"), Authority.OPERATOR, AccountId("acct-$i"), asset, amount)); apply() }
        val rnd = Random(5)
        fun capture(i: Int, buyer: Int, seller: Int, price: Long) = FinanceInput.Capture(ActionKey("n", "capture", "e$i"), ExecutionKey("r", "s", "AAPL", "e$i"), AccountId("acct-$buyer"), AccountId("acct-$seller"), 1, price)
        val first = HashMap<Int, Pair<Int, Int>>()
        for (i in 0 until 5_000) {
            val buyer = rnd.nextInt(4); val seller = (buyer + 1 + rnd.nextInt(3)) % 4; first[i] = buyer to seller
            val out = ledger.decide(CapacityCaptureMapping.captureSettle(capture(i, buyer, seller, 2))); assertEquals(Disposition.SETTLED, out.disposition); apply()
        }
        assertEquals(0, ledger.residentObjects, "settled trades are not held")
        assertEquals(5_000L + f, ledger.sequence)
        val readsBefore = store.reads
        // an old settled trade and its action are still known, from the store
        val (b17, s17) = first.getValue(17)
        assertEquals(Disposition.PRIOR_RESULT, ledger.decide(CapacityCaptureMapping.captureSettle(capture(17, b17, s17, 2))).disposition)
        val e17 = ExecutionKey("r", "s", "AAPL", "e17")
        assertEquals(Disposition.EXECUTION_CONFLICT, ledger.decide(FinanceInput.CaptureSettle(ActionKey("n", "cs", "other17"), e17, AccountId("acct-$b17"), AccountId("acct-$s17"), 1, 3)).disposition)
        assertEquals(Disposition.EXECUTION_ALREADY_CAPTURED, ledger.decide(FinanceInput.CaptureSettle(ActionKey("n", "cs", "again17"), e17, AccountId("acct-$b17"), AccountId("acct-$s17"), 1, 2)).disposition)
        assertEquals(Disposition.ALREADY_SETTLED, ledger.decide(FinanceInput.Settle(ActionKey("n", "settle", "s17"), Authority.OPERATOR, ExecutionKey("r", "s", "AAPL", "e17"), 1)).disposition)
        assertEquals(Disposition.UNKNOWN_EXECUTION, ledger.decide(FinanceInput.Settle(ActionKey("n", "settle", "s-none"), Authority.OPERATOR, ExecutionKey("r", "s", "AAPL", "never"), 1)).disposition)
        assertTrue(store.reads - readsBefore in 4..10, "old entities were read from the store, new ones were not: ${store.reads - readsBefore} reads")
    }

    private val binding = "b".repeat(64)

    @Test fun anEvictingRestoreRebuildsTheSameStateKeepsAlmostNothingAndKeepsDecidingIdentically() {
        for (mode in DecisionMode.entries) for (seed in 1L..25L) {
            val rnd = Random(seed); val cfg = config(mode, 5, 10_000)
            val executions = (0 until 25).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
            val kv = com.reef.platform.calcify.MemoryLifecycleKv(); val store = MapStore(); val live = CapacityLedger(cfg, store)
            fun settle(group: Map<String, ByteArray>) { kv.apply(group); store.entries.putAll(group); live.persisted() }
            var f = 0
            val group = LinkedHashMap<String, ByteArray>()
            fun decide(input: FinanceInput) { val out = live.decide(input); out.decision?.let { d -> group.putAll(CapacityStore.writes(live, d, binding)) }; if (rnd.nextInt(3) == 0) { settle(group); group.clear() } }
            for (i in 0 until 5) for ((asset, amount) in listOf(usd to 8L, aapl to 3L, msft to 3L)) decide(FinanceInput.Fund(ActionKey("n", "fund", "f${f++}"), Authority.OPERATOR, AccountId("acct-$i"), asset, amount))
            repeat(500) { decide(randomInput(rnd, mode, 5, executions, 300)) }
            settle(group); group.clear()

            val plainRestored = CapacityStore.restore(cfg, kv, binding)!!
            val evictRestored = CapacityStore.restore(cfg, kv, binding, evictSettled = true)!!
            assertEquals(live.commitment(), evictRestored.commitment()); assertEquals(plainRestored.commitment(), evictRestored.commitment())
            assertEquals(plainRestored.sequence, evictRestored.sequence); assertEquals(plainRestored.chain, evictRestored.chain); assertEquals(plainRestored.controls, evictRestored.controls); assertEquals(plainRestored.entityCount, evictRestored.entityCount)
            assertTrue(evictRestored.residentObjects < plainRestored.residentObjects, "an evicting restore holds less: ${evictRestored.residentObjects} vs ${plainRestored.residentObjects}")

            // both go on deciding the same way, the evicting one (as CapacityStore restored it) reading and extending the same store
            repeat(300) { n ->
                val input = randomInput(rnd, mode, 5, executions, 400)
                val a = plainRestored.decide(input); val b = evictRestored.decide(input)
                assertEquals(a.disposition, b.disposition, "mode ${mode.wire} seed $seed step $n after restore"); assertEquals(a.decision, b.decision, "decision after restore, seed $seed step $n")
                b.decision?.let { d -> kv.apply(CapacityStore.writes(evictRestored, d, binding)) }; evictRestored.persisted()
            }
            assertEquals(plainRestored.commitment(), evictRestored.commitment())
        }
    }

    /** The Streams processor with and without eviction: the same published decisions and the same stored bytes, including across a restart of the evicting one. */
    @Test fun theProcessorPublishesAndStoresTheSameBytesWithEviction() {
        for (mode in DecisionMode.entries) {
            val cfg = config(mode, 6, 100_000)
            // a record carries one to three inputs (length-delimited), so several decisions are made before the record's writes reach the store
            val decoder = CapacityInputDecoder { _, _, _, raw ->
                val stream = java.io.ByteArrayInputStream(raw); val items = ArrayList<CapacityItem>()
                while (stream.available() > 0) items += CapacityItem.Run(FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceInputV1.parseDelimitedFrom(stream)))
                items
            }
            class Lane(evict: Boolean) {
                val pc = CapacityProcessorConfig(binding, cfg, "CAPTURE", "CONTROL", "DECISIONS", "evict-test", evictSettled = evict)
                val context = org.apache.kafka.streams.processor.api.MockProcessorContext<String, ByteArray>()
                val store = CapacityTopology.storeBuilder(pc, persistent = false).withLoggingDisabled().build().also { it.init(context.getStateStoreContext(), it); context.addStateStore(it) }
                var processor = CapacityProcessor(pc, decoder).also { it.init(context) }
                var offset = 0L; val published = ArrayList<Pair<String, List<Byte>>>()
                fun send(topic: String, input: FinanceInput) = sendAll(topic, listOf(input))
                fun sendAll(topic: String, inputs: List<FinanceInput>) {
                    val raw = java.io.ByteArrayOutputStream().also { out -> inputs.forEach { FinanceWire.toProto(it).writeDelimitedTo(out) } }.toByteArray()
                    context.setRecordMetadata(topic, 0, offset++); processor.process(org.apache.kafka.streams.processor.api.Record(ByteArray(0), raw, 0L))
                    context.forwarded().forEach { published += it.record().key() to it.record().value().toList() }; context.resetForwards()
                }
                fun restart() { processor = CapacityProcessor(pc, decoder).also { it.init(context) } }
                fun entries(): Map<String, List<Byte>> = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value.toList() } }
            }
            val plain = Lane(false); val evicting = Lane(true)
            val rnd = Random(77 + mode.ordinal); val executions = (0 until 600).map { ExecutionKey("r", "s", if (it % 2 == 0) "AAPL" else "MSFT", "e$it") }
            var f = 0
            for (i in 0 until 6) for ((asset, amount) in listOf(usd to 20L, aapl to 6L, msft to 6L)) { val input = FinanceInput.Fund(ActionKey("n", "fund", "f${f++}"), Authority.OPERATOR, AccountId("acct-$i"), asset, amount); plain.send("CONTROL", input); evicting.send("CONTROL", input) }
            repeat(900) { n ->
                val inputs = (0 until 1 + rnd.nextInt(3)).map { randomInput(rnd, mode, 6, executions, 500) }
                plain.sendAll("CAPTURE", inputs); evicting.sendAll("CAPTURE", inputs)
                if (n == 420) { evicting.restart(); plain.restart() }
            }
            assertEquals(plain.published, evicting.published, "published decisions, mode ${mode.wire}")
            assertEquals(plain.entries(), evicting.entries(), "stored entities, mode ${mode.wire}")
            assertTrue(plain.published.size > 300, "the schedule recorded ${plain.published.size} decisions")
            assertTrue(evicting.processor.currentLedger().residentObjects < plain.processor.currentLedger().residentObjects, "eviction held less")
        }
    }
}
