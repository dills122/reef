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
 * S4_PG ("keys" = product constraints, "nobindingfk" = without the four foreign keys to the checkpoint), S4_PROJECT ("true"), S4_LEAN ("false"; skips the capture content-digest recompute, writes head and meta every K records instead of every record: candidate savings, each with a semantic cost, see CAL-FIN-P4).
 */
@Tag("broker") @Tag("research")
class S4EndToEndSpikeTest {
    private val usd = AssetId("USD"); private val aapl = FinanceBrokerFixture.aapl; private val opening = AccountId("p3-opening")
    private val settleSet = setOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)

    private class Shared { @Volatile var processor: CapacityProcessor? = null }

    private inner class CapacityProcessor(val accounts: List<AccountId>, val every: Int, val async: Boolean, val shared: Shared, val lean: Boolean = false) : Processor<ByteArray, ByteArray, String, ByteArray> {
        private lateinit var ctx: ProcessorContext<String, ByteArray>
        private lateinit var kv: KeyValueStore<String, ByteArray>
        lateinit var ledger: ScaleLedger
        private var position = 0L; private var captures = 0L
        private var head = "0".repeat(64)
        private val digest = FinanceBrokerFixture.bindingDigest
        /** Nanoseconds spent in each section of process(), for the whole run: decode, decide, history entry, store puts, output encode and forward. */
        val tParse = AtomicLong(); val tVerify = AtomicLong(); val tMap = AtomicLong(); val tDecode = AtomicLong(); val tDecide = AtomicLong(); val tHistory = AtomicLong(); val tPuts = AtomicLong(); val tOut = AtomicLong(); val tTotal = AtomicLong(); val records = AtomicLong(); val captureRecords = AtomicLong()
        override fun init(context: ProcessorContext<String, ByteArray>) {
            ctx = context; kv = context.getStateStore("s4-store")
            ledger = ScaleLedger(accounts.toSet(), usd, aapl, opening, rootEvery = every, asyncCommit = async); shared.processor = this
        }
        override fun process(record: Record<ByteArray, ByteArray>) {
            val tStart = System.nanoTime(); var mark = tStart
            fun lap(into: AtomicLong) { val now = System.nanoTime(); into.addAndGet(now - mark); mark = now }
            val md = ctx.recordMetadata().orElseThrow()
            val role = if (md.topic() == FinanceBrokerFixture.CAPTURE) "CAPTURE" else "CONTROL"
            val raw = record.value() ?: ByteArray(0)
            val inputs = ArrayList<Pair<Int, FinanceInput>>()
            if (role == "CAPTURE") {
                captureRecords.incrementAndGet(); val envelope = FiniteLifecycleCaptureV1.parseFrom(raw); lap(tParse)
                require(envelope.schema == com.reef.platform.calcify.FiniteLifecycleContract.CAPTURE_SCHEMA && envelope.finiteBindingDigest == FinanceBrokerFixture.captureBinding.digest && (lean || envelope.contentDigest == com.reef.platform.calcify.FiniteLifecycleContract.digest(envelope))); lap(tVerify)
                for (m in FinanceCaptureMapping.map(envelope, FinanceBrokerFixture.captureBinding)) if (m is FinanceCaptureMapping.Mapped.Ready) {
                    captures++; val b = accounts[((captures * 2654435761L) and 0x7fffffff).toInt() % accounts.size]; var s = accounts[((captures * 40503L + 7) and 0x7fffffff).toInt() % accounts.size]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accounts.size]
                    inputs += m.memberOrdinal to m.input.copy(buyer = b, seller = s)
                }
                lap(tMap)
            } else inputs += 0 to FinanceWire.fromProto(FinanceInputV1.parseFrom(raw))
            lap(tDecode)
            for ((ordinal, input) in inputs) {
                val result = ledger.decide(input); val decision = ledger.lastDecision
                lap(tDecide)
                position++
                val inputDigest = input.digest()
                val chain = FinanceStore.entryChain(head, position, role, md.partition(), md.offset(), ordinal, FinanceStore.EntryKind.INPUT, inputDigest, result.disposition.name, decision?.checksum ?: "")
                kv.put(FinanceStore.historyKey(position), FinanceStore.Entry(position, role, md.partition(), md.offset(), ordinal, FinanceStore.EntryKind.INPUT, inputDigest, result.disposition.name, decision?.checksum ?: "", head, chain).bytes())
                head = chain
                lap(tHistory)
                if (decision != null) {
                    for ((k, v) in ledger.touched) kv.put("e/$k", v.toByteArray())
                    if (!lean || position % every == 0L) kv.put("head", FinanceWire.toProto(decision).toByteArray())
                    lap(tPuts)
                    val proto = FinanceWire.toProto(decision, input)
                    ctx.forward(record.withKey(decision.sequence.toString().padStart(20, '0')).withValue(proto.toByteArray()))
                    lap(tOut)
                }
            }
            if (!lean || position % every == 0L) kv.put("meta", FinanceStore.Meta(digest, position, head, ledger.sequence, ledger.chain, ledger.controls).bytes())
            lap(tPuts); tTotal.addAndGet(System.nanoTime() - tStart); records.incrementAndGet()
        }
    }

    private fun topology(accounts: List<AccountId>, every: Int, async: Boolean, shared: Shared, lean: Boolean): Topology = Topology().apply {
        addSource("capture-in", ByteArrayDeserializer(), ByteArrayDeserializer(), FinanceBrokerFixture.CAPTURE)
        addSource("control-in", ByteArrayDeserializer(), ByteArrayDeserializer(), FinanceBrokerFixture.CONTROL)
        addProcessor("finance", { CapacityProcessor(accounts, every, async, shared, lean) }, "capture-in", "control-in")
        addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("s4-store"), Serdes.String(), Serdes.ByteArray()).withCachingDisabled(), "finance")
        addSink("decisions-out", FinanceBrokerFixture.DECISIONS, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "finance")
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

    @Test fun wholeChainOnOneMachine() {
        val trades = (System.getenv("S4_TRADES") ?: "100000").toInt(); val accountCount = (System.getenv("S4_ACCOUNTS") ?: "1000").toInt(); val every = (System.getenv("S4_K") ?: "100").toInt()
        val async = (System.getenv("S4_ASYNC") ?: "true").toBoolean(); val pgMode = System.getenv("S4_PG") ?: "keys"; val project = (System.getenv("S4_PROJECT") ?: "true").toBoolean(); val lean = (System.getenv("S4_LEAN") ?: "false").toBoolean()
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }
        val funds = 2 * accountCount; val totalDecisions = funds + 2L * trades
        val pgName = "s4-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        val original = Files.readString(Path.of(System.getenv("S0_MIGRATION") ?: "../../scripts/dev/db/migrations/boundary/0014_calcify_finance_projection.sql"))
        require(original.contains("BETWEEN 0 AND 320") && original.contains("BETWEEN 1 AND 320")) { "migration 0014 no longer carries the 320 bounds this spike lifts" }
        val migration = original.replace("BETWEEN 0 AND 320", ">= 0").replace("BETWEEN 1 AND 320", ">= 1")
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=s0", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", "postgres:16-alpine")
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
            val digest = FinanceBrokerFixture.bindingDigest
            val projector = SetProjector(ds, digest); projector.open()

            val genStart = System.nanoTime()
            // Building each envelope from the fixture costs milliseconds, so build one genuine envelope and only change the execution id and the content digest.
            val baseEnvelope = FinanceBrokerFixture.envelope(0); val readyIndex = baseEnvelope.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
            val envelopes = ArrayList<ByteArray>(trades); for (i in 0 until trades) {
                val edited = baseEnvelope.toBuilder().also { b -> b.getMembersBuilder(readyIndex).tradeBuilder.factBuilder.setExecutionId(FinanceBrokerFixture.executionId(i)) }.build()
                envelopes += edited.toBuilder().setContentDigest(com.reef.platform.calcify.FiniteLifecycleContract.digest(edited)).build().toByteArray()
            }
            val settleInputs = ArrayList<ByteArray>(trades); for (i in 0 until trades) settleInputs += FinanceWire.toProto(FinanceBrokerFixture.settle(FinanceBrokerFixture.executionId(i), 1)).toByteArray()
            val genSecs = (System.nanoTime() - genStart) / 1e9

            DisposableRedpanda().start().use { rp ->
                rp.admin().use { FinanceBrokerFixture.createTopics(it) }
                val shared = Shared(); val stateDir = Files.createTempDirectory("s4-state")
                val props = FinanceTopology.streamsProperties("s4-app", rp.bootstrap, stateDir.toString())
                val streams = KafkaStreams(topology(accounts, every, async, shared, lean), props)
                streams.setUncaughtExceptionHandler { e -> System.err.println("S4-ERROR: $e"); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
                val seen = AtomicLong(); val captured = AtomicLong(); val applied = AtomicLong(); val lastSeenNanos = AtomicLong(); val lastAppliedNanos = AtomicLong(); val maxBacklog = AtomicLong()
                val queue = ArrayBlockingQueue<List<Pair<Decision, FinanceInput>>>(16); val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false); val failureRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                val tailer = thread(name = "s4-tailer") {
                    try {
                        val c = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
                            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.GROUP_ID_CONFIG, "s4-${System.nanoTime()}")
                            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000); put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 65536); put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50)
                            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name) })
                        c.use { cons ->
                            cons.subscribe(listOf(FinanceBrokerFixture.DECISIONS))
                            while (!stopFlag.get()) {
                                val polled = cons.poll(Duration.ofMillis(100)); if (polled.isEmpty) continue
                                val batch = ArrayList<Pair<Decision, FinanceInput>>(polled.count())
                                for (r in polled) { val rec = FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(r.value())); batch += rec; if (rec.first.disposition == Disposition.CAPTURED) captured.incrementAndGet() }
                                seen.addAndGet(batch.size.toLong()); lastSeenNanos.set(System.nanoTime())
                                if (project) { queue.put(batch); maxBacklog.accumulateAndGet(seen.get() - applied.get()) { a, b -> maxOf(a, b) } }
                            }
                        }
                    } catch (e: Throwable) { failureRef.set(e); System.err.println("S4-TAILER-ERROR: $e") }
                }
                val applier = thread(name = "s4-applier") {
                    try {
                        var done = 0L
                        while (!stopFlag.get()) {
                            val first = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                            val merged = ArrayList(first); while (merged.size < 5000) { val more = queue.poll() ?: break; merged += more }
                            projector.apply(done, merged); done += merged.size; applied.set(done); lastAppliedNanos.set(System.nanoTime())
                        }
                    } catch (e: Throwable) { failureRef.set(e); System.err.println("S4-APPLIER-ERROR: $e") }
                }
                streams.start()
                val ready = System.nanoTime() + Duration.ofSeconds(120).toNanos()
                while (streams.state() != KafkaStreams.State.RUNNING && System.nanoTime() < ready) Thread.sleep(50)
                check(streams.state() == KafkaStreams.State.RUNNING) { "streams not running: ${streams.state()}" }
                val producer = FinanceBrokerFixture.producer(rp.bootstrap)
                try {
                    for (a in accounts) { for ((asset, amount) in listOf(usd to 1_000_000_000_000_000L, aapl to 1_000_000_000L))
                        producer.send(ProducerRecord(FinanceBrokerFixture.CONTROL, 0, ByteArray(0), FinanceWire.toProto(FinanceInput.Fund(ActionKey("s4", "control", "${a.value}-${asset.value}"), Authority.OPERATOR, a, asset, amount)).toByteArray())) }
                    producer.flush()
                    val warm = System.nanoTime() + Duration.ofSeconds(120).toNanos(); while (seen.get() < funds && System.nanoTime() < warm && failureRef.get() == null) Thread.sleep(20)
                    check(seen.get() >= funds) { "funding decisions not seen: ${seen.get()}" }
                    val gc0 = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
                    val t0 = System.nanoTime()
                    val capturer = thread(name = "s4-capture-producer") { for (i in 0 until trades) producer.send(ProducerRecord(FinanceBrokerFixture.CAPTURE, 0, ByteArray(0), envelopes[i])); producer.flush() }
                    val settler = thread(name = "s4-settle-producer") { for (i in 0 until trades) { while (captured.get() <= i && failureRef.get() == null) Thread.sleep(1); producer.send(ProducerRecord(FinanceBrokerFixture.CONTROL, 0, ByteArray(0), settleInputs[i])) }; producer.flush() }
                    val limit = System.nanoTime() + Duration.ofSeconds(900).toNanos()
                    while (seen.get() < totalDecisions && System.nanoTime() < limit && failureRef.get() == null) Thread.sleep(20)
                    check(failureRef.get() == null) { "pipeline failed: ${failureRef.get()}" }; check(seen.get() == totalDecisions) { "decisions seen ${seen.get()} of $totalDecisions" }
                    val laneSecs = (lastSeenNanos.get() - t0) / 1e9
                    if (project) { val d2 = System.nanoTime() + Duration.ofSeconds(900).toNanos(); while (applied.get() < totalDecisions && System.nanoTime() < d2 && failureRef.get() == null) Thread.sleep(20); check(failureRef.get() == null) { "pipeline failed: ${failureRef.get()}" }; check(applied.get() == totalDecisions) { "applied ${applied.get()} of $totalDecisions" } }
                    val projSecs = if (project) (lastAppliedNanos.get() - t0) / 1e9 else 0.0
                    capturer.join(); settler.join()
                    val gcSecs = (java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime } - gc0) / 1000.0
                    val measured = 2.0 * trades
                    // correctness: the projection equals the ledger the processor ended with
                    if (project) ds.connection.use { c ->
                        val sqlPositions = HashMap<Pair<AccountId, AssetId>, Long>()
                        c.createStatement().use { s -> s.executeQuery("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = '$digest'").use { r -> while (r.next()) sqlPositions[AccountId(r.getString(1)) to AssetId(r.getString(2))] = r.getLong(3) } }
                        val ledger = shared.processor!!.ledger
                        assertEquals(ledger.positions.filterValues { true }, sqlPositions.filterKeys { true }, "projected positions equal the ledger's")
                        val settledSql = c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM boundary.calcify_finance_obligation WHERE binding_digest = '$digest' AND status = 'SETTLED'").use { r -> r.next(); r.getLong(1) } }
                        assertEquals(ledger.obligations.values.count { it.status == ObligationStatus.SETTLED }.toLong(), settledSql, "settled obligations equal")
                        assertEquals(totalDecisions, c.createStatement().use { s -> s.executeQuery("SELECT last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = '$digest'").use { r -> r.next(); r.getLong(1) } })
                    }
                    val p = shared.processor!!
                    val label = "k=$every async=$async pg=$pgMode project=$project lean=$lean trades=$trades accounts=$accountCount"
                    S0Metrics.record("endToEnd.$label.laneInputsPerSec", Math.round(measured / laneSecs)); if (project) S0Metrics.record("endToEnd.$label.projectedDecisionsPerSec", Math.round(measured / projSecs))
                    log.append("S4 $label: generated %d trades of inputs in %.1f s (untimed)\n  lane: %.0f inputs/s end to end (%.1f s for %d inputs), decisions read back as fast as produced\n".format(trades, genSecs, measured / laneSecs, laneSecs, 2 * trades))
                    if (project) log.append("  projector: %.0f decisions/s through the whole chain (%.1f s; finished %.1f s after the lane), max backlog %d decisions\n".format(measured / projSecs, projSecs, projSecs - laneSecs, maxBacklog.get()))
                    log.append("  gc %.1f s; commitment thread waits %d (%.2f s)\n".format(gcSecs, p.ledger.blockedCount, p.ledger.blockedNanos / 1e9))
                    val n = p.records.get().toDouble()
                    log.append("  capture decode detail per capture record (%d): protobuf parse %.1f us, schema+binding+content digest check %.1f us, capture mapping %.1f us; control records are parsed with FinanceInputV1\n".format(p.captureRecords.get(), p.tParse.get() / p.captureRecords.get().toDouble() / 1e3, p.tVerify.get() / p.captureRecords.get().toDouble() / 1e3, p.tMap.get() / p.captureRecords.get().toDouble() / 1e3))
                    log.append("  processor time per record (%d records, %.1f s inside process): decode %.1f us, decide %.1f us, history entry %.1f us, store puts %.1f us, output encode+forward %.1f us, total %.1f us\n".format(
                        p.records.get(), p.tTotal.get() / 1e9, p.tDecode.get() / n / 1e3, p.tDecide.get() / n / 1e3, p.tHistory.get() / n / 1e3, p.tPuts.get() / n / 1e3, p.tOut.get() / n / 1e3, p.tTotal.get() / n / 1e3))
                } finally { stopFlag.set(true); producer.close(); tailer.join(5000); applier.join(5000); try { streams.close(Duration.ofSeconds(30)) } finally { stateDir.toFile().deleteRecursively() } }
            }
            println(log); Files.writeString(S0Metrics.file("s4-end-to-end.txt"), log)
        } finally {
            ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
        }
    }
}
