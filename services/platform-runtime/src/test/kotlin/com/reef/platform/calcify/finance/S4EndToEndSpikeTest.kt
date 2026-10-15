package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.finance.v1.FinanceInputV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4 end-to-end spike (Decision 0 follow-up): the whole chain on one machine, to find the binding term before more is spent on any one part. Research only
 * (`-PbrokerTests -PresearchTests`); starts and removes its own Redpanda and Postgres 16 containers.
 *
 *   producers -> CAPTURE (real FiniteLifecycleCaptureV1 envelopes) and CONTROL (real FinanceInputV1) -> Streams processor (real envelope validation and capture mapping,
 *   the S3 O(touched) ledger prototype with the commitment on a second thread, real history-entry chain and encoding, real FinanceWire decision protos, persistent
 *   store with changelog, EOS v2) -> DECISIONS -> tailer (real proto decode) -> set-based projector (unnest, one transaction per batch, checkpoint row locked) -> Postgres.
 *
 * Differences from the product, all of which make this faster than the real thing: the ledger is the prototype (no overlay or abort journal, no store-backed view);
 * buyer and seller are re-drawn from a pool after the real mapping (the fixture binding has two accounts); no restore cut; the projector skips `verifyLink`; the
 * decision log is read as a stream instead of whole; Redpanda, Postgres, the JVM and the producers share one machine and its Docker VM. Settles are produced only after
 * their capture decision has been read back, so the interleaving is realistic. S4_TRADES (default 100000), S4_ACCOUNTS (1000), S4_K (100), S4_ASYNC (true),
 * S4_PG ("keys" = product constraints, "nobindingfk" = without the four foreign keys to the checkpoint), S4_PROJECT ("true"), S4_FREE ("false"; a cached pointer store for head and meta, and the input digest and decision proto bytes reused: no semantic cost), S4_VERIFY_ASYNC ("false"; the capture content-digest check runs on a verifier thread, detecting after the decision), S4_LEAN ("false"; skips the capture content-digest recompute, writes head and meta every K records instead of every record: candidate savings, each with a semantic cost, see CAL-FIN-P4).
 */
@Tag("broker") @Tag("research")
class S4EndToEndSpikeTest {
    private val usd = AssetId("USD"); private val aapl = FinanceBrokerFixture.aapl; private val opening = AccountId("p3-opening")
    private val settleSet = setOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)

    private class Shared { @Volatile var processor: CapacityProcessor? = null }

    private inner class CapacityProcessor(val accounts: List<AccountId>, val every: Int, val async: Boolean, val shared: Shared, val lean: Boolean = false, val captureTopic: String = FinanceBrokerFixture.CAPTURE, val controlTopic: String = FinanceBrokerFixture.CONTROL, val free: Boolean = false, val verifier: ArrayBlockingQueue<FiniteLifecycleCaptureV1>? = null, val spec: ((Int) -> Pair<AccountId, AccountId>)? = null) : Processor<ByteArray, ByteArray, String, ByteArray> {
        private lateinit var ctx: ProcessorContext<String, ByteArray>
        private lateinit var kv: KeyValueStore<String, ByteArray>
        private lateinit var pointer: KeyValueStore<String, ByteArray>
        lateinit var ledger: ScaleLedger
        private var position = 0L; private var captures = 0L
        private var head = "0".repeat(64)
        private val digest = FinanceBrokerFixture.bindingDigest
        /** Nanoseconds spent in each section of process(), for the whole run: decode, decide, history entry, store puts, output encode and forward. */
        val tParse = AtomicLong(); val tVerify = AtomicLong(); val tMap = AtomicLong(); val tDecode = AtomicLong(); val tDecide = AtomicLong(); val tHistory = AtomicLong(); val tPuts = AtomicLong(); val tOut = AtomicLong(); val tTotal = AtomicLong(); val records = AtomicLong(); val captureRecords = AtomicLong()
        override fun init(context: ProcessorContext<String, ByteArray>) {
            ctx = context; kv = context.getStateStore("s4-store"); pointer = if (free) context.getStateStore("s4-pointer") else kv
            ledger = ScaleLedger(accounts.toSet(), usd, aapl, opening, rootEvery = every, asyncCommit = async); shared.processor = this
        }
        override fun process(record: Record<ByteArray, ByteArray>) {
            val tStart = System.nanoTime(); var mark = tStart
            fun lap(into: AtomicLong) { val now = System.nanoTime(); into.addAndGet(now - mark); mark = now }
            val md = ctx.recordMetadata().orElseThrow()
            val role = if (md.topic() == captureTopic) "CAPTURE" else "CONTROL"
            val raw = record.value() ?: ByteArray(0)
            val inputs = ArrayList<Pair<Int, FinanceInput>>()
            if (role == "CAPTURE") {
                captureRecords.incrementAndGet(); val envelope = FiniteLifecycleCaptureV1.parseFrom(raw); lap(tParse)
                require(envelope.schema == com.reef.platform.calcify.FiniteLifecycleContract.CAPTURE_SCHEMA && envelope.finiteBindingDigest == FinanceBrokerFixture.captureBinding.digest && (lean || verifier != null || envelope.contentDigest == com.reef.platform.calcify.FiniteLifecycleContract.digest(envelope))); verifier?.put(envelope); lap(tVerify)
                for (m in FinanceCaptureMapping.map(envelope, FinanceBrokerFixture.captureBinding)) if (m is FinanceCaptureMapping.Mapped.Ready) {
                    captures++
                    val picked = spec?.invoke(m.input.execution.executionId.substringAfterLast('-').toInt())
                    val b = picked?.first ?: accounts[((captures * 2654435761L) and 0x7fffffff).toInt() % accounts.size]
                    var s = picked?.second ?: accounts[((captures * 40503L + 7) and 0x7fffffff).toInt() % accounts.size]; if (picked == null && s == b) s = accounts[(accounts.indexOf(b) + 1) % accounts.size]
                    inputs += m.memberOrdinal to m.input.copy(buyer = b, seller = s)
                }
                lap(tMap)
            } else inputs += 0 to FinanceWire.fromProto(FinanceInputV1.parseFrom(raw))
            lap(tDecode)
            for ((ordinal, input) in inputs) {
                val result = ledger.decide(input); val decision = ledger.lastDecision
                lap(tDecide)
                position++
                val inputDigest = if (free) (decision?.inputDigest ?: input.digest()) else input.digest()
                val chain = FinanceStore.entryChain(head, position, role, md.partition(), md.offset(), ordinal, FinanceStore.EntryKind.INPUT, inputDigest, result.disposition.name, decision?.checksum ?: "")
                kv.put(FinanceStore.historyKey(position), FinanceStore.Entry(position, role, md.partition(), md.offset(), ordinal, FinanceStore.EntryKind.INPUT, inputDigest, result.disposition.name, decision?.checksum ?: "", head, chain).bytes())
                head = chain
                lap(tHistory)
                if (decision != null) {
                    for ((k, v) in ledger.touched) kv.put("e/$k", v.toByteArray())
                    val outBytes: ByteArray?
                    if (free) { outBytes = FinanceWire.toProto(decision, input).toByteArray(); pointer.put("head", outBytes) }
                    else { outBytes = null; if (!lean || position % every == 0L) kv.put("head", FinanceWire.toProto(decision).toByteArray()) }
                    lap(tPuts)
                    ctx.forward(record.withKey(decision.sequence.toString().padStart(20, '0')).withValue(outBytes ?: FinanceWire.toProto(decision, input).toByteArray()))
                    lap(tOut)
                }
            }
            if (!lean || position % every == 0L) pointer.put("meta", FinanceStore.Meta(digest, position, head, ledger.sequence, ledger.chain, ledger.controls).bytes())
            lap(tPuts); tTotal.addAndGet(System.nanoTime() - tStart); records.incrementAndGet()
        }
    }

    private fun topology(accounts: List<AccountId>, every: Int, async: Boolean, shared: Shared, lean: Boolean, captureTopic: String, controlTopic: String, decisionsTopic: String, store: String, free: Boolean, verifier: ArrayBlockingQueue<FiniteLifecycleCaptureV1>?, spec: ((Int) -> Pair<AccountId, AccountId>)? = null): Topology = Topology().apply {
        addSource("capture-in", ByteArrayDeserializer(), ByteArrayDeserializer(), captureTopic)
        addSource("control-in", ByteArrayDeserializer(), ByteArrayDeserializer(), controlTopic)
        addProcessor("finance", { CapacityProcessor(accounts, every, async, shared, lean, captureTopic, controlTopic, free, verifier, spec) }, "capture-in", "control-in")
        addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("s4-store"), Serdes.String(), Serdes.ByteArray()).withCachingDisabled(), "finance")
        // The pointer store (head, meta) is cached: repeated writes to the same two keys coalesce into one changelog record per commit, and the EOS commit still makes it atomic with everything else.
        if (free) addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("s4-pointer"), Serdes.String(), Serdes.ByteArray()).withCachingEnabled(), "finance")
        addSink("decisions-out", decisionsTopic, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "finance")
    }

    // ---- the set-based projector under test ----

    private inner class SetProjector(val source: PGSimpleDataSource, val digest: String) {
        private fun arr(c: Connection, type: String, v: List<*>) = c.createArrayOf(type, v.toTypedArray())
        fun open() = source.connection.use { c -> c.prepareStatement("INSERT INTO boundary.calcify_finance_checkpoint(binding_digest, owner_epoch, last_sequence, last_chain) VALUES (?, 1, 0, ?)").use { it.setString(1, digest); it.setString(2, "0".repeat(64)); it.executeUpdate() } }
        fun apply(expectedLast: Long, batch: List<Pair<Decision, FinanceInput>>) {
            source.connection.use { c ->
                c.autoCommit = false
                val last = c.prepareStatement("SELECT last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ? FOR UPDATE").use { ps -> ps.setString(1, digest); ps.executeQuery().use { r -> check(r.next()); r.getLong(1) } }
                check(last == expectedLast) { "projection at $last, expected $expectedLast" }
                val seq = ArrayList<Long>(); val ns = ArrayList<String>(); val dom = ArrayList<String>(); val aid = ArrayList<String>(); val disp = ArrayList<String>(); val dig = ArrayList<String>(); val prior = ArrayList<String>(); val sum = ArrayList<String>()
                val oRun = ArrayList<String>(); val oVen = ArrayList<String>(); val oIns = ArrayList<String>(); val oEx = ArrayList<String>(); val oBuy = ArrayList<String>(); val oSell = ArrayList<String>(); val oQty = ArrayList<Long>(); val oPx = ArrayList<Long>(); val oCash = ArrayList<Long>()
                val aRun = ArrayList<String>(); val aVen = ArrayList<String>(); val aIns = ArrayList<String>(); val aEx = ArrayList<String>(); val aNo = ArrayList<Int>(); val aDisp = ArrayList<String>()
                val uRun = ArrayList<String>(); val uVen = ArrayList<String>(); val uIns = ArrayList<String>(); val uEx = ArrayList<String>()
                val net = LinkedHashMap<Pair<String, String>, Long>(); var next = expectedLast
                for ((d, input) in batch) {
                    check(d.sequence == next + 1) { "gap at ${d.sequence}" }; next = d.sequence
                    check(input.digest() == d.inputDigest) { "input digest differs" }
                    seq += d.sequence; ns += d.action.namespace; dom += d.action.domain; aid += d.action.actionId; disp += d.disposition.name; dig += d.inputDigest; prior += d.priorChain; sum += d.checksum
                    for (leg in d.legs) net.merge(leg.account.value to leg.asset.value, leg.amount, Long::plus)
                    when (input) {
                        is FinanceInput.Capture -> if (d.disposition == Disposition.CAPTURED) { val e = input.execution
                            oRun += e.runId; oVen += e.venueSessionId; oIns += e.instrumentId; oEx += e.executionId; oBuy += input.buyer.value; oSell += input.seller.value; oQty += input.quantity; oPx += input.priceNanos; oCash += Math.multiplyExact(input.quantity, input.priceNanos) }
                        is FinanceInput.Settle -> if (d.disposition in settleSet) { val e = input.execution
                            aRun += e.runId; aVen += e.venueSessionId; aIns += e.instrumentId; aEx += e.executionId; aNo += input.attempt; aDisp += d.disposition.name
                            if (d.disposition == Disposition.SETTLED) { uRun += e.runId; uVen += e.venueSessionId; uIns += e.instrumentId; uEx += e.executionId } }
                        is FinanceInput.Fund -> Unit
                        is FinanceInput.CaptureSettle -> throw IllegalStateException("capture-settle is not part of this test")
                    }
                }
                c.prepareStatement("INSERT INTO boundary.calcify_finance_decision SELECT ?, t.s, t.n, t.d, t.a, t.p, t.g, t.r, t.k FROM unnest(?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[]) AS t(s,n,d,a,p,g,r,k)").use {
                    it.setString(1, digest); it.setArray(2, arr(c, "bigint", seq)); it.setArray(3, arr(c, "text", ns)); it.setArray(4, arr(c, "text", dom)); it.setArray(5, arr(c, "text", aid)); it.setArray(6, arr(c, "text", disp)); it.setArray(7, arr(c, "text", dig)); it.setArray(8, arr(c, "text", prior)); it.setArray(9, arr(c, "text", sum)); it.executeUpdate() }
                if (oEx.isNotEmpty()) c.prepareStatement("INSERT INTO boundary.calcify_finance_obligation SELECT ?, t.r, t.v, t.i, t.e, t.b, t.s, t.q, t.p, 'PENDING', t.c, t.q FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::bigint[], ?::bigint[], ?::bigint[]) AS t(r,v,i,e,b,s,q,p,c)").use {
                    it.setString(1, digest); it.setArray(2, arr(c, "text", oRun)); it.setArray(3, arr(c, "text", oVen)); it.setArray(4, arr(c, "text", oIns)); it.setArray(5, arr(c, "text", oEx)); it.setArray(6, arr(c, "text", oBuy)); it.setArray(7, arr(c, "text", oSell)); it.setArray(8, arr(c, "bigint", oQty)); it.setArray(9, arr(c, "bigint", oPx)); it.setArray(10, arr(c, "bigint", oCash)); it.executeUpdate() }
                if (aEx.isNotEmpty()) c.prepareStatement("INSERT INTO boundary.calcify_finance_attempt SELECT ?, t.r, t.v, t.i, t.e, t.n, t.d FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::int[], ?::text[]) AS t(r,v,i,e,n,d)").use {
                    it.setString(1, digest); it.setArray(2, arr(c, "text", aRun)); it.setArray(3, arr(c, "text", aVen)); it.setArray(4, arr(c, "text", aIns)); it.setArray(5, arr(c, "text", aEx)); it.setArray(6, arr(c, "int", aNo)); it.setArray(7, arr(c, "text", aDisp)); it.executeUpdate() }
                if (uEx.isNotEmpty()) { val n = c.prepareStatement("UPDATE boundary.calcify_finance_obligation o SET status = 'SETTLED', cash_residual = 0, share_residual = 0 FROM unnest(?::text[], ?::text[], ?::text[], ?::text[]) AS t(r,v,i,e) WHERE o.binding_digest = ? AND o.run_id = t.r AND o.venue_session_id = t.v AND o.instrument_id = t.i AND o.execution_id = t.e AND o.status = 'PENDING'").use {
                    it.setArray(1, arr(c, "text", uRun)); it.setArray(2, arr(c, "text", uVen)); it.setArray(3, arr(c, "text", uIns)); it.setArray(4, arr(c, "text", uEx)); it.setString(5, digest); it.executeUpdate() }
                    check(n == uEx.size) { "settled $n of ${uEx.size}" } }
                if (net.isNotEmpty()) c.prepareStatement("INSERT INTO boundary.calcify_finance_position SELECT ?, t.a, t.s, t.m FROM unnest(?::text[], ?::text[], ?::bigint[]) AS t(a,s,m) ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount").use {
                    it.setString(1, digest); it.setArray(2, arr(c, "text", net.keys.map { k -> k.first })); it.setArray(3, arr(c, "text", net.keys.map { k -> k.second })); it.setArray(4, arr(c, "bigint", net.values.toList())); it.executeUpdate() }
                c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET last_sequence = ?, last_chain = ? WHERE binding_digest = ?").use { it.setLong(1, next); it.setString(2, batch.last().first.checksum); it.setString(3, digest); it.executeUpdate() }
                c.commit()
            }
        }
    }

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }; return out
    }

    /** One shard: its own topics, Streams application, ledger, tailer, projector and binding digest. Shards share the broker, Postgres and the JVM. */
    private inner class Lane(val index: Int, val accountCount: Int, val every: Int, val async: Boolean, val lean: Boolean, val project: Boolean, val bootstrap: String, val ds: PGSimpleDataSource, val free: Boolean, val verifyAsync: Boolean, val participants: List<AccountId>? = null, val spec: ((Int) -> Pair<AccountId, AccountId>)? = null, val seenMoves: MutableSet<String>? = null, val fundCash: Long = 1_000_000_000_000_000L) {
        val captureTopic = "S4_CAPTURE_$index"; val controlTopic = "S4_CONTROL_$index"; val decisionsTopic = "S4_DECISIONS_$index"
        val accounts = participants ?: (0 until accountCount).map { AccountId("acct-$index-$it") }
        val digest = FinanceBrokerFixture.bindingDigest.let { java.security.MessageDigest.getInstance("SHA-256").digest("s4-lane-$index".toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        val shared = Shared(); val projector = SetProjector(ds, digest)
        val seen = AtomicLong(); val captured = AtomicLong(); val applied = AtomicLong(); val lastSeenNanos = AtomicLong(); val lastAppliedNanos = AtomicLong(); val maxBacklog = AtomicLong()
        val queue = ArrayBlockingQueue<List<Pair<Decision, FinanceInput>>>(16); val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false); val failureRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        lateinit var streams: KafkaStreams; lateinit var stateDir: Path; val threads = ArrayList<Thread>()
        val funds = 2 * accountCount
        val verifier: ArrayBlockingQueue<FiniteLifecycleCaptureV1>? = if (verifyAsync) ArrayBlockingQueue(100_000) else null
        val verified = AtomicLong()

        fun start() {
            stateDir = Files.createTempDirectory("s4-state-$index")
            val props = FinanceTopology.streamsProperties("s4-app-$index", bootstrap, stateDir.toString())
            // S4_STREAMS_PROPS="key=value,key=value" overrides Streams properties for a tuning experiment (product code takes its properties from FinanceTopology and verifyPinned refuses a weaker set).
            System.getenv("S4_STREAMS_PROPS")?.split(",")?.filter { it.contains("=") }?.forEach { kv -> val (k, v) = kv.split("=", limit = 2); props[k.trim()] = v.trim().toIntOrNull() ?: v.trim() }
            streams = KafkaStreams(topology(accounts, every, async, shared, lean, captureTopic, controlTopic, decisionsTopic, "s4-store", free, verifier, spec), props)
            streams.setUncaughtExceptionHandler { e -> System.err.println("S4-ERROR lane $index: $e"); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
            projector.open()
            threads += thread(name = "s4-tailer-$index") {
                try {
                    val c = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
                        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.GROUP_ID_CONFIG, "s4-$index-${System.nanoTime()}")
                        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000); put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 65536); put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50)
                        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name) })
                    c.use { cons ->
                        cons.subscribe(listOf(decisionsTopic))
                        while (!stopFlag.get()) {
                            val polled = cons.poll(Duration.ofMillis(100)); if (polled.isEmpty) continue
                            val batch = ArrayList<Pair<Decision, FinanceInput>>(polled.count())
                            for (r in polled) { val rec = FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(r.value())); batch += rec; if (rec.first.disposition == Disposition.CAPTURED) captured.incrementAndGet() }
                            if (seenMoves != null) for (rec in batch) { val a = rec.first.action; if (rec.first.disposition != Disposition.FUNDED) continue; if (a.domain == "defund") seenMoves.add(a.actionId) else if (a.domain == "control" && a.actionId.endsWith("-in")) seenMoves.add("in:" + a.actionId.removeSuffix("-in")) }
                            seen.addAndGet(batch.size.toLong()); lastSeenNanos.set(System.nanoTime())
                            if (project) { queue.put(batch); maxBacklog.accumulateAndGet(seen.get() - applied.get()) { a, b -> maxOf(a, b) } }
                        }
                    }
                } catch (e: Throwable) { failureRef.set(e); System.err.println("S4-TAILER-ERROR lane $index: $e") }
            }
            threads += thread(name = "s4-applier-$index") {
                try {
                    var done = 0L
                    while (!stopFlag.get()) {
                        val first = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        val merged = ArrayList(first); while (merged.size < 5000) { val more = queue.poll() ?: break; merged += more }
                        projector.apply(done, merged); done += merged.size; applied.set(done); lastAppliedNanos.set(System.nanoTime())
                    }
                } catch (e: Throwable) { failureRef.set(e); System.err.println("S4-APPLIER-ERROR lane $index: $e") }
            }
            if (verifier != null) threads += thread(name = "s4-verifier-$index") {
                try {
                    while (!stopFlag.get() || verifier.isNotEmpty()) {
                        val e = verifier.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        check(e.contentDigest == com.reef.platform.calcify.FiniteLifecycleContract.digest(e)) { "content digest mismatch (detected after the decision was issued)" }; verified.incrementAndGet()
                    }
                } catch (e: Throwable) { failureRef.set(e); System.err.println("S4-VERIFIER-ERROR lane $index: $e") }
            }
            streams.start()
        }
        fun awaitRunning() { val ready = System.nanoTime() + Duration.ofSeconds(120).toNanos(); while (streams.state() != KafkaStreams.State.RUNNING && System.nanoTime() < ready) Thread.sleep(50); check(streams.state() == KafkaStreams.State.RUNNING) { "lane $index streams not running: ${streams.state()}" } }
        fun fund(producer: org.apache.kafka.clients.producer.KafkaProducer<ByteArray, ByteArray>) {
            for (a in accounts) for ((asset, amount) in listOf(usd to fundCash, aapl to 1_000_000_000L))
                producer.send(ProducerRecord(controlTopic, 0, ByteArray(0), FinanceWire.toProto(FinanceInput.Fund(ActionKey("s4", "control", "${a.value}-${asset.value}"), Authority.OPERATOR, a, asset, amount)).toByteArray()))
            producer.flush()
            val warm = System.nanoTime() + Duration.ofSeconds(180).toNanos(); while (seen.get() < funds && System.nanoTime() < warm && failureRef.get() == null) Thread.sleep(20)
            check(seen.get() >= funds) { "lane $index funding decisions not seen: ${seen.get()}" }
        }
        fun produce(producer: org.apache.kafka.clients.producer.KafkaProducer<ByteArray, ByteArray>, envelopes: List<ByteArray>, settles: List<ByteArray>): List<Thread> = listOf(
            thread(name = "s4-capture-producer-$index") { for (i in envelopes.indices) producer.send(ProducerRecord(captureTopic, 0, ByteArray(0), envelopes[i])); producer.flush() },
            thread(name = "s4-settle-producer-$index") { for (i in settles.indices) { while (captured.get() <= i && failureRef.get() == null) Thread.sleep(1); producer.send(ProducerRecord(controlTopic, 0, ByteArray(0), settles[i])) }; producer.flush() })
        fun stop() { stopFlag.set(true); threads.forEach { it.join(5000) }; try { streams.close(Duration.ofSeconds(30)) } finally { stateDir.toFile().deleteRecursively() } }
    }

    @Test fun wholeChainOnOneMachine() {
        val trades = (System.getenv("S4_TRADES") ?: "100000").toInt(); val accountCount = (System.getenv("S4_ACCOUNTS") ?: "1000").toInt(); val every = (System.getenv("S4_K") ?: "100").toInt()
        val async = (System.getenv("S4_ASYNC") ?: "true").toBoolean(); val pgMode = System.getenv("S4_PG") ?: "keys"; val project = (System.getenv("S4_PROJECT") ?: "true").toBoolean(); val lean = (System.getenv("S4_LEAN") ?: "false").toBoolean()
        val free = (System.getenv("S4_FREE") ?: "false").toBoolean(); val verifyAsync = (System.getenv("S4_VERIFY_ASYNC") ?: "false").toBoolean(); val laneCount = (System.getenv("S4_LANES") ?: "1").toInt(); val smp = (System.getenv("S4_SMP") ?: "1").toInt(); val memory = System.getenv("S4_BROKER_MEM") ?: "1G"
        val pgName = "s4-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        val original = Files.readString(Path.of(System.getenv("S0_MIGRATION") ?: "../../scripts/dev/db/migrations/boundary/0014_calcify_finance_projection.sql"))
        require(original.contains("BETWEEN 0 AND 320") && original.contains("BETWEEN 1 AND 320")) { "migration 0014 no longer carries the 320 bounds this spike lifts" }
        val migration = original.replace("BETWEEN 0 AND 320", ">= 0").replace("BETWEEN 1 AND 320", ">= 1")
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=s0", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        val log = StringBuilder()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "pg_isready", "-U", "postgres", "-d", "reef"); Thread.sleep(1500); sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(500) } }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migration")
            if (pgMode == "nobindingfk") sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = """
                DO ${'$'}${'$'} DECLARE r record; BEGIN
                  FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint WHERE connamespace = 'boundary'::regnamespace AND contype = 'f' AND confrelid = 'boundary.calcify_finance_checkpoint'::regclass LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname);
                  END LOOP;
                END ${'$'}${'$'};""")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "s0"; }
            // Building each envelope from the fixture costs milliseconds, so build one genuine envelope and only change the execution id and the content digest. Every lane replays the same bytes: its ledger is its own.
            val baseEnvelope = FinanceBrokerFixture.envelope(0); val readyIndex = baseEnvelope.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
            val envelopes = ArrayList<ByteArray>(trades); for (i in 0 until trades) {
                val edited = baseEnvelope.toBuilder().also { b -> b.getMembersBuilder(readyIndex).tradeBuilder.factBuilder.setExecutionId(FinanceBrokerFixture.executionId(i)) }.build()
                envelopes += edited.toBuilder().setContentDigest(com.reef.platform.calcify.FiniteLifecycleContract.digest(edited)).build().toByteArray()
            }
            val settleInputs = ArrayList<ByteArray>(trades); for (i in 0 until trades) settleInputs += FinanceWire.toProto(FinanceBrokerFixture.settle(FinanceBrokerFixture.executionId(i), 1)).toByteArray()

            DisposableRedpanda(smp, memory).start().use { rp ->
                val lanes = (0 until laneCount).map { Lane(it, accountCount, every, async, lean, project, rp.bootstrap, ds, free, verifyAsync) }
                rp.admin().use { a ->
                    val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
                    a.createTopics(lanes.flatMap { l -> listOf(l.captureTopic, l.controlTopic, l.decisionsTopic).map { org.apache.kafka.clients.admin.NewTopic(it, 1, 1.toShort()).configs(history) } }).all().get(60, TimeUnit.SECONDS)
                }
                val producer = FinanceBrokerFixture.producer(rp.bootstrap)
                try {
                    lanes.forEach { it.start() }; lanes.forEach { it.awaitRunning() }
                    lanes.forEach { it.fund(producer) }
                    fun containerCpuMicros(name: String): Long = try { sh("docker", "exec", name, "cat", "/sys/fs/cgroup/cpu.stat").lineSequence().first { it.startsWith("usage_usec") }.split(" ")[1].toLong() } catch (e: Exception) { -1L }
                    val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                    val jvmCpu0 = os.processCpuTime; val rpCpu0 = containerCpuMicros(rp.name); val pgCpu0 = containerCpuMicros(pgName)
                    val gc0 = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
                    val t0 = System.nanoTime()
                    val producers = lanes.flatMap { it.produce(producer, envelopes, settleInputs) }
                    val totalPerLane = lanes[0].funds + 2L * trades
                    val limit = System.nanoTime() + Duration.ofSeconds(1500).toNanos()
                    while (lanes.any { it.seen.get() < totalPerLane } && System.nanoTime() < limit && lanes.all { it.failureRef.get() == null }) Thread.sleep(20)
                    lanes.forEach { check(it.failureRef.get() == null) { "lane ${it.index} failed: ${it.failureRef.get()}" }; check(it.seen.get() == totalPerLane) { "lane ${it.index}: decisions seen ${it.seen.get()} of $totalPerLane" } }
                    val laneEnd = lanes.maxOf { it.lastSeenNanos.get() }
                    if (project) { val d2 = System.nanoTime() + Duration.ofSeconds(1500).toNanos(); while (lanes.any { it.applied.get() < totalPerLane } && System.nanoTime() < d2 && lanes.all { it.failureRef.get() == null }) Thread.sleep(20); lanes.forEach { check(it.failureRef.get() == null) { "lane ${it.index} failed: ${it.failureRef.get()}" }; check(it.applied.get() == totalPerLane) { "lane ${it.index}: applied ${it.applied.get()} of $totalPerLane" } } }
                    val projEnd = if (project) lanes.maxOf { it.lastAppliedNanos.get() } else laneEnd
                    producers.forEach { it.join() }
                    val jvmCpu = (os.processCpuTime - jvmCpu0) / 1000.0; val rpCpu = (containerCpuMicros(rp.name) - rpCpu0).toDouble(); val pgCpu = (containerCpuMicros(pgName) - pgCpu0).toDouble()
                    val gcSecs = (java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime } - gc0) / 1000.0
                    if (project) for (l in lanes) ds.connection.use { c ->
                        val sqlPositions = HashMap<Pair<AccountId, AssetId>, Long>()
                        c.createStatement().use { s -> s.executeQuery("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = '${l.digest}'").use { r -> while (r.next()) sqlPositions[AccountId(r.getString(1)) to AssetId(r.getString(2))] = r.getLong(3) } }
                        val ledger = l.shared.processor!!.ledger
                        assertEquals(ledger.positions.filterValues { true }, sqlPositions.filterKeys { true }, "lane ${l.index}: projected positions equal the ledger's")
                        val settledSql = c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM boundary.calcify_finance_obligation WHERE binding_digest = '${l.digest}' AND status = 'SETTLED'").use { r -> r.next(); r.getLong(1) } }
                        assertEquals(ledger.obligations.values.count { it.status == ObligationStatus.SETTLED }.toLong(), settledSql, "lane ${l.index}: settled obligations equal")
                        assertEquals(totalPerLane, c.createStatement().use { s -> s.executeQuery("SELECT last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = '${l.digest}'").use { r -> r.next(); r.getLong(1) } })
                    }
                    val measured = 2.0 * trades * laneCount; val laneSecs = (laneEnd - t0) / 1e9; val projSecs = (projEnd - t0) / 1e9
                    val label = "lanes=$laneCount broker=smp$smp/$memory k=$every async=$async pg=$pgMode project=$project lean=$lean free=$free verifyAsync=$verifyAsync trades=$trades accounts=$accountCount"
                    S0Metrics.record("endToEnd.$label.laneInputsPerSec", Math.round(measured / laneSecs)); if (project) S0Metrics.record("endToEnd.$label.projectedDecisionsPerSec", Math.round(measured / projSecs))
                    log.append("S4 $label\n  lanes together: %.0f inputs/s end to end (%.1f s for %d inputs; %.0f per lane)\n".format(measured / laneSecs, laneSecs, 2 * trades * laneCount, measured / laneSecs / laneCount))
                    if (project) log.append("  projectors together: %.0f decisions/s through the whole chain (%.1f s; finished %.1f s after the lanes), max backlog per lane %s\n".format(measured / projSecs, projSecs, projSecs - laneSecs, lanes.map { it.maxBacklog.get() }))
                    val perDecision = 2.0 * trades * laneCount + lanes.sumOf { it.funds }
                    log.append("  CPU per decision (microseconds, all threads): JVM (processors, commitment, tailers, projectors, producers, verifiers) %.0f, Redpanda container %.0f, Postgres container %.0f, total %.0f; at 20,000 decisions/s that is %.1f cores\n".format(jvmCpu / perDecision, rpCpu / perDecision, pgCpu / perDecision, (jvmCpu + rpCpu + pgCpu) / perDecision, (jvmCpu + rpCpu + pgCpu) / perDecision * 20000 / 1e6))
                    log.append("  gc %.1f s (shared JVM); machine load average at the end: %s\n".format(gcSecs, "%.2f (1 min, system)".format(java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage)))
                    for (l in lanes) { val p = l.shared.processor!!; val n = p.records.get().toDouble(); log.append("  lane %d: %d records, %.1f us per record inside process (commitment waits %d)\n".format(l.index, p.records.get(), p.tTotal.get() / n / 1e3, p.ledger.blockedCount)) }
                    // Where the deciding thread's wall time goes (Kafka Streams thread metrics; a ratio is the share of the thread's time).
                    val wanted = listOf("process-ratio", "poll-ratio", "commit-ratio", "punctuate-ratio", "poll-latency-avg", "commit-latency-avg", "poll-records-avg", "commit-rate", "process-rate")
                    for (l in lanes) {
                        val m = l.streams.metrics().filter { it.key.group() == "stream-thread-metrics" && it.key.name() in wanted }
                        log.append("  lane %d thread: %s\n".format(l.index, wanted.joinToString(", ") { w -> m.entries.firstOrNull { it.key.name() == w }?.let { e -> "%s %s".format(w, (e.value.metricValue() as? Double)?.let { d -> "%.3f".format(d) } ?: e.value.metricValue()) } ?: "$w n/a" }))
                    }
                } finally { producer.close(); lanes.forEach { try { it.stop() } catch (e: Throwable) { System.err.println("lane stop: $e") } } }
            }
            println(log); Files.writeString(S0Metrics.file("s4-end-to-end.txt"), log)
        } finally {
            ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
        }
    }

    private class PlanItem(val kind: Char, val bytes: ByteArray, val captureIndex: Int = -1, val moveId: String = "", val after: List<String> = emptyList())

    /**
     * S1 prototype (sharding option S1): shard by instrument, each shard a full lane with a cash sub-account per participant, and a rebalancer that moves cash between a participant's sub-accounts
     * when a trade would find the buyer short in the trade's shard. A move is two control inputs: a donor-side "defund" (a prototype-only reverse of a Fund) on the donor shard, and, only after the donor's
     * decision has been read back, a Fund on the target shard, so cash is never spendable in two shards at once. The rebalancing plan is computed up front from the trade stream (the same prediction a live
     * controller would make) and played in global order; every settle waits for its capture decision. Verified at the end: each shard's projection equals its ledger, no settle failed for lack of cash,
     * and total cash across shards is conserved. S1_TRADES (200000), S1_PARTICIPANTS (1000), S1_SHARDS (3), S1_ZIPF (false), S1_CASH_TRADES (120: a participant's total opening cash, in trade costs).
     */
    @Test fun shardedByInstrument() {
        val trades = (System.getenv("S1_TRADES") ?: "200000").toInt(); val participantCount = (System.getenv("S1_PARTICIPANTS") ?: "1000").toInt(); val shards = (System.getenv("S1_SHARDS") ?: "3").toInt()
        val zipf = (System.getenv("S1_ZIPF") ?: "false").toBoolean(); val cashTrades = (System.getenv("S1_CASH_TRADES") ?: "120").toLong(); val lowWater = (System.getenv("S1_LOW_WATER") ?: "4").toLong()
        val every = (System.getenv("S4_K") ?: "100").toInt(); val async = (System.getenv("S4_ASYNC") ?: "true").toBoolean(); val pgMode = System.getenv("S4_PG") ?: "nobindingfk"
        val smp = (System.getenv("S4_SMP") ?: "1").toInt(); val memory = System.getenv("S4_BROKER_MEM") ?: "1G"
        val cost = FinanceBrokerFixture.PRICE; val perShard = cashTrades * cost / shards
        val participants = (0 until participantCount).map { AccountId("p-$it") }
        // Trade stream: instrument (uniform or Zipf), buyer, seller; instruments assigned to shards by greedy balanced partition of their popularity.
        val instruments = 64; val rnd = java.util.Random(7)
        val weight = DoubleArray(instruments) { if (zipf) 1.0 / (it + 1) else 1.0 }
        val shardOfInstrument = IntArray(instruments); run { val load = DoubleArray(shards); for (i in (0 until instruments).sortedByDescending { weight[it] }) { val k = load.indices.minByOrNull { load[it] }!!; shardOfInstrument[i] = k; load[k] += weight[i] } }
        val cum = DoubleArray(instruments); var acc = 0.0; for (i in 0 until instruments) { acc += weight[i]; cum[i] = acc }
        val shardOf = IntArray(trades); val buyerOf = IntArray(trades); val sellerOf = IntArray(trades)
        for (t in 0 until trades) { val x = rnd.nextDouble() * acc; var lo = 0; var hi = instruments - 1; while (lo < hi) { val mid = (lo + hi) ushr 1; if (cum[mid] < x) lo = mid + 1 else hi = mid }
            shardOf[t] = shardOfInstrument[lo]; buyerOf[t] = rnd.nextInt(participantCount); var sl = rnd.nextInt(participantCount); if (sl == buyerOf[t]) sl = (sl + 1) % participantCount; sellerOf[t] = sl }
        // Plan: per shard, the ordered control inputs (settles and the two halves of each cash move).
        fun fundBytes(key: ActionKey, account: AccountId, amount: Long) = FinanceWire.toProto(FinanceInput.Fund(key, Authority.OPERATOR, account, usd, amount)).toByteArray()
        val cash = Array(participantCount) { LongArray(shards) { perShard } }; val queues = List(shards) { ArrayList<PlanItem>() }; val inbound = ArrayList<Pair<Int, PlanItem>>(); val indexInShard = IntArray(shards); var moves = 0; var shortfalls = 0; var tradesWithMoves = 0; val landed = HashMap<Pair<Int, Int>, ArrayList<String>>()
        for (t in 0 until trades) {
            val sh = shardOf[t]; val b = buyerOf[t]; val sl = sellerOf[t]
            // Proactive rebalancing: refill when the balance AFTER this trade would fall below the low-water mark, so cash is already on its way when it is needed.
            if (cash[b][sh] - cost < lowWater * cost) {
                var need = maxOf(perShard, cost) - cash[b][sh]; var any = false
                while (need > 0) {
                    var donor = -1; var best = 0L; for (k in 0 until shards) if (k != sh && cash[b][k] > best) { best = cash[b][k]; donor = k }
                    if (donor < 0) break
                    val take = minOf(best, need); val id = "mv-${moves++}"
                    val prior = landed.remove(b to donor)?.toList() ?: emptyList()   // the donor must have seen the cash it was promised before it gives any of it away
                    queues[donor] += PlanItem('D', fundBytes(ActionKey("s1", "defund", id), participants[b], take), after = prior); landed.getOrPut(b to sh) { ArrayList() } += "in:$id"; inbound += sh to PlanItem('F', fundBytes(ActionKey("s1", "control", "$id-in"), participants[b], take), moveId = id)
                    cash[b][donor] -= take; cash[b][sh] += take; need -= take; any = true
                }
                if (any) tradesWithMoves++
                if (cash[b][sh] < cost) shortfalls++
            }
            queues[sh] += PlanItem('S', FinanceWire.toProto(FinanceBrokerFixture.settle(FinanceBrokerFixture.executionId(t), 1)).toByteArray(), captureIndex = indexInShard[sh]++)
            cash[b][sh] -= cost; cash[sl][sh] += cost
        }
        val expected = LongArray(shards) { k -> 2L * participantCount + 2L * indexInShard[k] + queues[k].count { it.kind != 'S' } + inbound.count { it.first == k } }
        val pgName = "s1-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        val original = Files.readString(Path.of(System.getenv("S0_MIGRATION") ?: "../../scripts/dev/db/migrations/boundary/0014_calcify_finance_projection.sql"))
        require(original.contains("BETWEEN 0 AND 320") && original.contains("BETWEEN 1 AND 320")) { "migration 0014 no longer carries the 320 bounds this spike lifts" }
        val migration = original.replace("BETWEEN 0 AND 320", ">= 0").replace("BETWEEN 1 AND 320", ">= 1")
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=s0", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        val log = StringBuilder()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "pg_isready", "-U", "postgres", "-d", "reef"); Thread.sleep(1500); sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(500) } }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migration")
            if (pgMode == "nobindingfk") sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = """
                DO ${'$'}${'$'} DECLARE r record; BEGIN
                  FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint WHERE connamespace = 'boundary'::regnamespace AND contype = 'f' AND confrelid = 'boundary.calcify_finance_checkpoint'::regclass LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname);
                  END LOOP;
                END ${'$'}${'$'};""")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "s0" }
            val baseEnvelope = FinanceBrokerFixture.envelope(0); val readyIndex = baseEnvelope.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
            val envelopes = Array(trades) { i ->
                val edited = baseEnvelope.toBuilder().also { b -> b.getMembersBuilder(readyIndex).tradeBuilder.factBuilder.setExecutionId(FinanceBrokerFixture.executionId(i)) }.build()
                edited.toBuilder().setContentDigest(com.reef.platform.calcify.FiniteLifecycleContract.digest(edited)).build().toByteArray()
            }
            val seenMoves = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val spec: (Int) -> Pair<AccountId, AccountId> = { t -> participants[buyerOf[t]] to participants[sellerOf[t]] }
            DisposableRedpanda(smp, memory).start().use { rp ->
                val lanes = (0 until shards).map { Lane(it, participantCount, every, async, false, true, rp.bootstrap, ds, false, false, participants, spec, seenMoves, perShard) }
                rp.admin().use { a ->
                    val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
                    a.createTopics(lanes.flatMap { l -> listOf(l.captureTopic, l.controlTopic, l.decisionsTopic).map { org.apache.kafka.clients.admin.NewTopic(it, 1, 1.toShort()).configs(history) } }).all().get(60, TimeUnit.SECONDS)
                }
                val producer = FinanceBrokerFixture.producer(rp.bootstrap)
                try {
                    lanes.forEach { it.start() }; lanes.forEach { it.awaitRunning() }; lanes.forEach { it.fund(producer) }
                    val t0 = System.nanoTime()
                    val producers = lanes.flatMap { l ->
                        listOf(
                            thread(name = "s1-capture-$l.index") { for (t in 0 until trades) if (shardOf[t] == l.index) producer.send(ProducerRecord(l.captureTopic, 0, ByteArray(0), envelopes[t])); producer.flush() },
                            thread(name = "s1-control-${l.index}") {
                                for (item in queues[l.index]) {
                                    if (item.kind == 'S') while (l.captured.get() <= item.captureIndex && l.failureRef.get() == null) Thread.sleep(1)
                                    if (item.kind == 'D') while (!seenMoves.containsAll(item.after) && l.failureRef.get() == null) Thread.sleep(1)
                                    producer.send(ProducerRecord(l.controlTopic, 0, ByteArray(0), item.bytes))
                                }
                                producer.flush()
                            })
                    }
                    // The inbound half of each move is sent as soon as the donor's decision has been read back, without holding up the shard's settles. A defund that fails (the donor no longer has the cash)
                    // produces no decision, so its inbound half is never sent; those moves are counted as failed rather than awaited.
                    val moverStop = java.util.concurrent.atomic.AtomicBoolean(false)
                    val mover = thread(name = "s1-mover") {
                        val pending = ArrayList(inbound)
                        while (pending.isNotEmpty() && !moverStop.get() && lanes.all { it.failureRef.get() == null }) {
                            val it2 = pending.iterator(); var sent = 0
                            while (it2.hasNext()) { val (lane, item) = it2.next(); if (seenMoves.contains(item.moveId)) { producer.send(ProducerRecord(lanes[lane].controlTopic, 0, ByteArray(0), item.bytes)); it2.remove(); sent++ } }
                            if (sent == 0) Thread.sleep(1)
                        }
                        producer.flush()
                    }
                    val limit = System.nanoTime() + Duration.ofSeconds(1500).toNanos(); var lastTotal = -1L; var lastProgress = System.nanoTime()
                    while (System.nanoTime() < limit && lanes.all { it.failureRef.get() == null }) {
                        Thread.sleep(20); val total = lanes.sumOf { it.seen.get() }
                        if (total != lastTotal) { lastTotal = total; lastProgress = System.nanoTime() }
                        else if (producers.none { it.isAlive } && System.nanoTime() - lastProgress > Duration.ofSeconds(8).toNanos()) break
                    }
                    moverStop.set(true); mover.join()
                    lanes.forEach { check(it.failureRef.get() == null) { "shard ${it.index} failed: ${it.failureRef.get()}" } }
                    val laneEnd = lanes.maxOf { it.lastSeenNanos.get() }
                    val d2 = System.nanoTime() + Duration.ofSeconds(300).toNanos(); while (lanes.any { it.applied.get() < it.seen.get() } && System.nanoTime() < d2 && lanes.all { it.failureRef.get() == null }) Thread.sleep(20)
                    lanes.forEach { check(it.failureRef.get() == null) { "shard ${it.index} failed: ${it.failureRef.get()}" }; check(it.applied.get() == it.seen.get()) { "shard ${it.index}: applied ${it.applied.get()} of ${it.seen.get()}" } }
                    val projEnd = lanes.maxOf { it.lastAppliedNanos.get() }; producers.forEach { it.join() }
                    val defundsDone = seenMoves.count { !it.startsWith("in:") }; val inboundDone = seenMoves.count { it.startsWith("in:") }
                    var nonSettled = 0L; var cashTotal = 0L
                    for (l in lanes) ds.connection.use { c ->
                        val sqlPositions = HashMap<Pair<AccountId, AssetId>, Long>()
                        c.createStatement().use { st -> st.executeQuery("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = '${l.digest}'").use { r -> while (r.next()) sqlPositions[AccountId(r.getString(1)) to AssetId(r.getString(2))] = r.getLong(3) } }
                        val ledger = l.shared.processor!!.ledger
                        assertEquals(ledger.positions.filterValues { true }, sqlPositions.filterKeys { true }, "shard ${l.index}: projected positions equal the ledger's")
                        nonSettled += c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM boundary.calcify_finance_attempt WHERE binding_digest = '${l.digest}' AND disposition <> 'SETTLED'").use { r -> r.next(); r.getLong(1) } }
                        cashTotal += ledger.positions.filterKeys { it.second == usd && it.first != opening }.values.sum()
                    }
                    // A settle can fail for lack of cash for two reasons: a true shortfall (the participant's total cash is too low, planned) or cash that was still in transit when the settle was processed (late); the second is the cost of asynchronous rebalancing and is reported, not asserted.
                    check(nonSettled >= shortfalls) { "fewer failed settles ($nonSettled) than planned true shortfalls ($shortfalls)" }
                    assertEquals(participantCount.toLong() * perShard * shards, cashTotal, "total cash across the shards is conserved")
                    val laneSecs = (laneEnd - t0) / 1e9; val projSecs = (projEnd - t0) / 1e9; val totalDecisions = lanes.sumOf { it.seen.get() }
                    val label = "shards=$shards zipf=$zipf participants=$participantCount cashTrades=$cashTrades trades=$trades k=$every async=$async broker=smp$smp/$memory"
                    S0Metrics.record("shardedS1.$label.bookedTradesPerSec", Math.round(trades / projSecs)); S0Metrics.record("shardedS1.$label.movesPer1000Trades", Math.round(moves * 1000.0 / trades))
                    log.append("S1 $label lowWater=$lowWater\n  planned: %d cash moves (%.1f per 1,000 trades; %.2f%% of trades needed one), %d true shortfalls, busiest shard %d%% of trades\n".format(moves, moves * 1000.0 / trades, tradesWithMoves * 100.0 / trades, shortfalls, (0 until shards).maxOf { indexInShard[it] } * 100 / trades))
                    log.append("  lanes: %.0f decisions/s together (%.1f s); projection finished %.1f s after; %.0f booked trades/s through the whole chain (a booked trade is a capture plus a settle)\n".format(totalDecisions / laneSecs, laneSecs, projSecs - laneSecs, trades / projSecs))
                    log.append("  moves planned %d: donor halves completed %d, inbound halves completed %d (failed moves: %d); settle attempts that failed for lack of cash: %d (planned true shortfalls %d; the rest arrived late or followed a failed move)\n".format(moves, defundsDone, inboundDone, moves - inboundDone, nonSettled, shortfalls))
                    log.append("  machine load average at the end: %s; decisions per shard %s\n".format("%.2f (1 min, system)".format(java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage), lanes.map { it.seen.get() }))
                } finally { producer.close(); lanes.forEach { try { it.stop() } catch (e: Throwable) { System.err.println("shard stop: $e") } } }
            }
            println(log); Files.writeString(S0Metrics.file("s1-sharded-chain.txt"), log)
        } finally {
            ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
        }
    }
}
