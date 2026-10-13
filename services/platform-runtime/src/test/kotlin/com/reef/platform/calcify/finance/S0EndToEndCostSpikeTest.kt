package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import java.util.Random
import kotlin.test.Test

/** Child process for the crash-restart measurement: the real finance topology on the production Streams properties, optionally with a lower session timeout. */
internal object S0CrashApp {
    @JvmStatic fun main(args: Array<String>) {
        val props = FinanceTopology.streamsProperties(FinanceBrokerFixture.APP_ID, args[0], args[1])
        if (args[2] != "default") {
            props[org.apache.kafka.streams.StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)] = args[2].toInt()
            props[org.apache.kafka.streams.StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)] = 1000
        }
        val streams = KafkaStreams(FinanceTopology.build(FinanceBrokerFixture.config, persistent = true), props)
        streams.start()
        println("S0-CRASH-APP-STARTED")
        Thread.currentThread().join()
    }
}

/** Appends `key=value` lines to $S0_OUT_DIR/s0-metrics.properties (default: java.io.tmpdir) so a scheduled run can compare against the budget. */
internal object S0Metrics {
    private val dir: Path get() = Path.of(System.getenv("S0_OUT_DIR") ?: System.getProperty("java.io.tmpdir"))
    @Synchronized fun record(key: String, value: Number) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("s0-metrics.properties"), "$key=$value\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
    fun file(name: String): Path = dir.also { Files.createDirectories(it) }.resolve(name)
}

/**
 * S0 end-to-end cost experiment for docs/work/CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md (review round 2: lens C Q7, lens B 9.x).
 * Not product code; run with `-PbrokerTests -PresearchTests`. It answers whether a faster ledger would matter at all, by measuring the
 * costs that are NOT the ledger, using the production Streams settings (FinanceTopology.streamsProperties), a persistent store with
 * caching disabled and the default changelog, on a real Redpanda:
 *   A. Streams/RocksDB/changelog/EOS ceiling: a processor that does P store puts of ~300 bytes per input and forwards one output.
 *   B. Restore: reading the whole changelog into the heap (what FinanceEvidenceReader does) and rebuilding RocksDB from the changelog.
 *   C. Projector (separate test class, needs Docker Postgres): statements and time per decision, batch size 1 versus many.
 */
@Tag("broker") @Tag("research")
class S0EndToEndCostSpikeTest {
    private class Puts(private val perRecord: Int, private val storeName: String, private val valueBytes: Int) : Processor<ByteArray, ByteArray, String, ByteArray> {
        private lateinit var ctx: ProcessorContext<String, ByteArray>
        private lateinit var kv: KeyValueStore<String, ByteArray>
        private var n = 0L
        override fun init(context: ProcessorContext<String, ByteArray>) { ctx = context; kv = context.getStateStore(storeName) }
        override fun process(record: Record<ByteArray, ByteArray>) {
            n++
            val v = ByteArray(valueBytes) { (it + n).toByte() }
            for (i in 0 until perRecord) kv.put("e/${n}/$i", v)
            ctx.forward(Record("k", ByteArray(100), record.timestamp()))
        }
    }

    private fun topology(app: String, puts: Int): Topology = Topology().apply {
        val store = "s0-store"
        addSource("in", ByteArrayDeserializer(), ByteArrayDeserializer(), "S0_IN")
        addProcessor("p", { Puts(puts, store, 300) }, "in")
        addStateStore(org.apache.kafka.streams.state.Stores.keyValueStoreBuilder(
            org.apache.kafka.streams.state.Stores.persistentKeyValueStore(store),
            org.apache.kafka.common.serialization.Serdes.String(), org.apache.kafka.common.serialization.Serdes.ByteArray()).withCachingDisabled(), "p")
        addSink("out", "S0_OUT", org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "p")
    }

    private fun countOutput(bootstrap: String, target: Long, seconds: Long): Long {
        val c = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
            put(ConsumerConfig.GROUP_ID_CONFIG, "s0-${System.nanoTime()}"); put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        })
        c.use {
            it.subscribe(listOf("S0_OUT")); var seen = 0L; val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
            while (seen < target && System.nanoTime() < deadline) seen += it.poll(Duration.ofMillis(100)).count()
            return seen
        }
    }

    private fun waitRunning(s: KafkaStreams) {
        val deadline = System.nanoTime() + Duration.ofSeconds(300).toNanos()
        while (s.state() != KafkaStreams.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(50)
        check(s.state() == KafkaStreams.State.RUNNING) { "streams not running: ${s.state()}" }
    }

    private fun heapMb() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)

    /** Q10: why does restart to RUNNING take about 42 s? Default `KafkaStreams.close()` does not leave the consumer group; test leaveGroup(true). */
    @Test
    fun restartFloor() {
        val log = StringBuilder("\nS0 restart floor (small state, same state dir, same application id)\n")
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { a -> a.createTopics(listOf(NewTopic("S0_IN", 1, 1.toShort()), NewTopic("S0_OUT", 1, 1.toShort()))).all().get(30, TimeUnit.SECONDS) }
            val app = "s0-floor"; val dir = Files.createTempDirectory("s0-floor").toString()
            val producer = FinanceBrokerFixture.producer(rp.bootstrap)
            repeat(1000) { producer.send(ProducerRecord("S0_IN", 0, ByteArray(0), ByteArray(120))) }; producer.flush(); producer.close()
            fun start(): KafkaStreams = KafkaStreams(topology(app, 1), FinanceTopology.streamsProperties(app, rp.bootstrap, dir)).also { it.start(); waitRunning(it) }
            var current = start()
            for ((label, leave) in listOf("default close (no LeaveGroup)" to false, "close with leaveGroup=true" to true, "close with leaveGroup=true again" to true)) {
                // Each label closes the instance that is running now, with that label's close, and times the next start.
                if (leave) current.close(KafkaStreams.CloseOptions().timeout(Duration.ofSeconds(30)).leaveGroup(true)) else current.close(Duration.ofSeconds(30))
                val t0 = System.nanoTime(); current = start(); val secs = (System.nanoTime() - t0) / 1e9
                log.append("D  restart after $label: RUNNING after ${"%.1f".format(secs)} s\n")
                S0Metrics.record("restartFloor.sec." + (if (leave) "leaveGroup" else "default") + (if (label.endsWith("again")) ".repeat" else ""), Math.round(secs))
            }
            current.close(KafkaStreams.CloseOptions().timeout(Duration.ofSeconds(30)).leaveGroup(true))
        }
        println(log); Files.writeString(S0Metrics.file("s0-restart-floor.txt"), log)
    }

    /** Crash (SIGKILL) restart time on the production properties versus a 6 s session timeout; the clean-stop fix does not help a crash. */
    @Test
    fun crashRestart() {
        val log = StringBuilder("\nS0 crash restart (SIGKILL of a child finance app, then the same app id restarts)\n")
        for (session in listOf("default", "6000")) {
            DisposableRedpanda().start().use { rp ->
                rp.admin().use { FinanceBrokerFixture.createTopics(it) }
                val stateDir = Files.createTempDirectory("s0-crash").toFile()
                val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
                val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.reef.platform.calcify.finance.S0CrashApp", rp.bootstrap, stateDir.path, session)
                    .redirectErrorStream(true).start()
                val out = child.inputStream.bufferedReader()
                val seen = ArrayList<String>()
                while (true) { val line = out.readLine() ?: error("child exited before starting: ${seen.takeLast(15)}"); seen += line; if (line.contains("S0-CRASH-APP-STARTED")) break }
                Thread.sleep(8000)                                   // let it join the group and take the partition
                child.destroyForcibly().waitFor()                    // SIGKILL: no shutdown hooks, no LeaveGroup
                val props = FinanceTopology.streamsProperties(FinanceBrokerFixture.APP_ID, rp.bootstrap, stateDir.path)
                if (session != "default") { props[org.apache.kafka.streams.StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)] = session.toInt(); props[org.apache.kafka.streams.StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)] = 1000 }
                val t0 = System.nanoTime()
                val streams = KafkaStreams(FinanceTopology.build(FinanceBrokerFixture.config, persistent = true), props).also { it.start(); waitRunning(it) }
                val secs = (System.nanoTime() - t0) / 1e9
                log.append("E  session.timeout.ms=$session: restart after SIGKILL reached RUNNING in ${"%.1f".format(secs)} s\n")
                S0Metrics.record("crashRestart.sec.session$session", Math.round(secs))
                streams.close(KafkaStreams.CloseOptions().timeout(Duration.ofSeconds(30)).leaveGroup(true))
            }
        }
        println(log); Files.writeString(S0Metrics.file("s0-crash-restart.txt"), log)
    }

    @Test
    fun streamsCeilingAndRestore() {
        val n = (System.getenv("S0_RECORDS") ?: "20000").toInt()
        val log = StringBuilder("\nS0 results (records per run = $n, 300-byte values, EOS v2, 1 thread, commit 100 ms, caching off)\n")
        DisposableRedpanda().start().use { rp ->
            for (puts in (System.getenv("S0_PUTS") ?: "0,1,10").split(",").map { it.trim().toInt() }) {
                val app = "s0-app-p$puts"; val dir = Files.createTempDirectory("s0-state-$puts").toString()
                rp.admin().use { a -> a.createTopics(listOf(NewTopic("S0_IN", 1, 1.toShort()), NewTopic("S0_OUT", 1, 1.toShort()))).all().get(30, TimeUnit.SECONDS) }
                val props = FinanceTopology.streamsProperties(app, rp.bootstrap, dir)
                val streams = KafkaStreams(topology(app, puts), props)
                val producer = FinanceBrokerFixture.producer(rp.bootstrap)
                streams.start()
                waitRunning(streams)
                val t0 = System.nanoTime()
                repeat(n) { producer.send(ProducerRecord("S0_IN", 0, ByteArray(0), ByteArray(120))) }
                producer.flush()
                val seen = countOutput(rp.bootstrap, n.toLong(), 900)
                val secs = (System.nanoTime() - t0) / 1e9
                S0Metrics.record("streams.inputsPerSec.puts$puts", Math.round(seen / secs))
                log.append("A  puts/record=$puts  processed=$seen in ${"%.1f".format(secs)} s = ${"%.0f".format(seen / secs)} records/s  (${"%.0f".format(seen * puts / secs)} puts/s)\n")
                if (puts == 10 && System.getenv("S0_RESTORE") != "false") {
                    streams.close(Duration.ofSeconds(30))
                    val cl = "$app-s0-store-changelog"
                    val before = heapMb(); val r0 = System.nanoTime()
                    val all = FinanceBrokerFixture.readAll(rp.bootstrap, cl)
                    val readSecs = (System.nanoTime() - r0) / 1e9; System.gc(); val held = heapMb() - before
                    S0Metrics.record("restore.changelogReadMs", Math.round(readSecs * 1000)); S0Metrics.record("restore.changelogRecords", all.size)
                    log.append("B1 changelog read into heap: ${all.size} records in ${"%.1f".format(readSecs)} s, about $held MB held\n")
                    val dir2 = Files.createTempDirectory("s0-state-fresh").toString()
                    for ((label, d) in listOf("cold (state dir deleted: rebuild RocksDB from changelog)" to dir2, "warm (intact state dir)" to dir)) {
                        val s = KafkaStreams(topology(app, puts), FinanceTopology.streamsProperties(app, rp.bootstrap, d))
                        val restoreMs = java.util.concurrent.atomic.AtomicLong(-1); val restored = java.util.concurrent.atomic.AtomicLong(0)
                        s.setGlobalStateRestoreListener(object : org.apache.kafka.streams.processor.StateRestoreListener {
                            private var start = 0L
                            override fun onRestoreStart(tp: TopicPartition, store: String, startingOffset: Long, endingOffset: Long) { start = System.nanoTime() }
                            override fun onBatchRestored(tp: TopicPartition, store: String, batchEndOffset: Long, numRestored: Long) { restored.addAndGet(numRestored) }
                            override fun onRestoreEnd(tp: TopicPartition, store: String, totalRestored: Long) { restoreMs.set((System.nanoTime() - start) / 1_000_000) }
                        })
                        val t1 = System.nanoTime(); s.start(); waitRunning(s)
                        S0Metrics.record("restore.storeRestoreMs." + (if (d == dir2) "cold" else "warm"), restoreMs.get()); S0Metrics.record("restore.toRunningSec." + (if (d == dir2) "cold" else "warm"), Math.round((System.nanoTime() - t1) / 1e9))
                        log.append("B2 restart $label: RUNNING after ${"%.1f".format((System.nanoTime() - t1) / 1e9)} s total; store restore phase ${restoreMs.get()} ms for ${restored.get()} changelog records\n")
                        s.close(Duration.ofSeconds(30))
                    }
                } else { streams.close(Duration.ofSeconds(30)) }
                producer.close()
                rp.admin().use { a -> a.deleteTopics(listOf("S0_IN", "S0_OUT")).all().get(30, TimeUnit.SECONDS) }
            }
        }
        println(log)
        Files.writeString(S0Metrics.file("s0-streams.txt"), log)
    }
}

/** C: projector cost against a throwaway Postgres 16 (Docker). */
@Tag("broker") @Tag("research")
class S0ProjectorCostSpikeTest {
    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }; return out
    }

    @Test
    fun projectorStatementsAndBatching() {
        val name = "s0-pg-${System.nanoTime().toString(36)}"; val port = java.net.ServerSocket(0).use { it.localPort }
        val migration = Files.readString(Path.of(System.getenv("S0_MIGRATION") ?: "../../scripts/dev/db/migrations/boundary/0014_calcify_finance_projection.sql"))
        sh("docker", "run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=s0", "-e", "POSTGRES_DB=reef", "-p", "$port:5432", "postgres:16-alpine")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", name, "pg_isready", "-U", "postgres", "-d", "reef"); Thread.sleep(1500); sh("docker", "exec", name, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "pg not ready" }; Thread.sleep(500) } }
            sh("docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migration")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$port/reef"); user = "postgres"; password = "s0" }
            val buyer = AccountId("buyer-account"); val seller = AccountId("seller-account"); val usd = AssetId("USD"); val aapl = AssetId("fixture-base-AAPL")
            val ledger = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("opening")))
            // One binding carries at most 320 sequences by the migration's CHECK, so volume comes from many independent bindings.
            fun script(): List<Pair<Decision, FinanceInput>> {
                var state = ledger.genesis(); val out = ArrayList<Pair<Decision, FinanceInput>>()
                fun step(i: FinanceInput) { val o = ledger.decide(state, i); state = o.state; o.decision?.let { out += it to i } }
                step(FinanceInput.Fund(ActionKey("n", "ctl", "f1"), Authority.OPERATOR, buyer, usd, 10_000_000_000_000L))
                step(FinanceInput.Fund(ActionKey("n", "ctl", "f2"), Authority.OPERATOR, seller, aapl, 100_000L))
                var i = 0
                while (out.size < 120) {
                    val key = ExecutionKey("p3-run", "p3-session", "AAPL", "e$i")
                    step(FinanceInput.Capture(ActionKey("n", "cap", "c$i"), key, buyer, seller, 1L, 1_000_000_000L))
                    step(FinanceInput.Settle(ActionKey("n", "rep", "s$i"), Authority.OPERATOR, key, 1)); i++
                }
                return out
            }
            val recs = script(); val projector = FinanceProjector(ledger, ds)
            val log = StringBuilder("\nS0 projector (${recs.size} decisions per binding; capture+settle pairs, Postgres 16 in Docker, local socket)\n")
            for (batch in listOf(1, 10, 120)) {
                val bindings = (System.getenv("S0_BINDINGS") ?: "10").toInt(); var total = 0; val digests = (0 until bindings).map { (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64) }
                digests.forEach { projector.open(it, 1) }
                val t0 = System.nanoTime()
                for (d in digests) for (chunk in recs.chunked(batch)) total += projector.apply(d, 1, chunk)
                val secs = (System.nanoTime() - t0) / 1e9
                S0Metrics.record("projector.decisionsPerSec.batch$batch", Math.round(total / secs))
                log.append("C  batch=$batch  $total decisions in ${"%.2f".format(secs)} s = ${"%.0f".format(total / secs)} decisions/s (${"%.2f".format(secs * 1000 / total)} ms/decision)\n")
            }
            // Batched variant (research only; the product projector is untouched): the same rows written with JDBC batches per statement class in one
            // transaction, positions pre-aggregated per (account, asset) because ON CONFLICT DO UPDATE cannot touch a row twice in one statement.
            // It omits the in-memory link/digest checks of FinanceProjector.apply (hashing, microseconds per decision); it measures the database cost.
            val settleSet = setOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)
            fun batched(source: PGSimpleDataSource, digest: String, chunk: List<Pair<Decision, FinanceInput>>) = source.connection.use { c ->
                c.autoCommit = false
                val (last, _) = c.prepareStatement("SELECT last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ? FOR UPDATE").use { ps ->
                    ps.setString(1, digest); ps.executeQuery().use { r -> check(r.next()); r.getLong(1) to r.getString(2) } }
                val dec = c.prepareStatement("INSERT INTO boundary.calcify_finance_decision(binding_digest, sequence, action_namespace, action_domain, action_id, disposition, input_digest, prior_chain, checksum) VALUES (?,?,?,?,?,?,?,?,?)")
                val pos = c.prepareStatement("INSERT INTO boundary.calcify_finance_position(binding_digest, account_id, asset_id, amount) VALUES (?,?,?,?) ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount")
                val obl = c.prepareStatement("INSERT INTO boundary.calcify_finance_obligation(binding_digest, run_id, venue_session_id, instrument_id, execution_id, buyer_account_id, seller_account_id, quantity, price_nanos, status, cash_residual, share_residual) VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?)")
                val att = c.prepareStatement("INSERT INTO boundary.calcify_finance_attempt(binding_digest, run_id, venue_session_id, instrument_id, execution_id, attempt, disposition) VALUES (?,?,?,?,?,?,?)")
                val upd = c.prepareStatement("UPDATE boundary.calcify_finance_obligation SET status = 'SETTLED', cash_residual = 0, share_residual = 0 WHERE binding_digest = ? AND run_id = ? AND venue_session_id = ? AND instrument_id = ? AND execution_id = ? AND status = 'PENDING'")
                val net = LinkedHashMap<Pair<String, String>, Long>(); var lastSeq = last; var lastChain = ""
                for ((d, input) in chunk) {
                    if (d.sequence <= last) continue
                    dec.setString(1, digest); dec.setLong(2, d.sequence); dec.setString(3, d.action.namespace); dec.setString(4, d.action.domain); dec.setString(5, d.action.actionId)
                    dec.setString(6, d.disposition.name); dec.setString(7, d.inputDigest); dec.setString(8, d.priorChain); dec.setString(9, d.checksum); dec.addBatch()
                    for (leg in d.legs) net.merge(leg.account.value to leg.asset.value, leg.amount, Long::plus)
                    when (input) {
                        is FinanceInput.Capture -> if (d.disposition == Disposition.CAPTURED) { val e = input.execution
                            obl.setString(1, digest); obl.setString(2, e.runId); obl.setString(3, e.venueSessionId); obl.setString(4, e.instrumentId); obl.setString(5, e.executionId)
                            obl.setString(6, input.buyer.value); obl.setString(7, input.seller.value); obl.setLong(8, input.quantity); obl.setLong(9, input.priceNanos)
                            obl.setLong(10, Math.multiplyExact(input.quantity, input.priceNanos)); obl.setLong(11, input.quantity); obl.addBatch() }
                        is FinanceInput.Settle -> if (d.disposition in settleSet) { val e = input.execution
                            att.setString(1, digest); att.setString(2, e.runId); att.setString(3, e.venueSessionId); att.setString(4, e.instrumentId); att.setString(5, e.executionId)
                            att.setInt(6, input.attempt); att.setString(7, d.disposition.name); att.addBatch()
                            if (d.disposition == Disposition.SETTLED) { upd.setString(1, digest); upd.setString(2, e.runId); upd.setString(3, e.venueSessionId); upd.setString(4, e.instrumentId); upd.setString(5, e.executionId); upd.addBatch() } }
                        is FinanceInput.Fund -> Unit
                    }
                    lastSeq = d.sequence; lastChain = d.checksum
                }
                for ((k, v) in net) { pos.setString(1, digest); pos.setString(2, k.first); pos.setString(3, k.second); pos.setLong(4, v); pos.addBatch() }
                dec.executeBatch(); obl.executeBatch(); att.executeBatch(); pos.executeBatch()
                check(upd.executeBatch().all { it == 1 }) { "settled decision for a missing or already settled obligation" }
                c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET last_sequence = ?, last_chain = ? WHERE binding_digest = ?").use { it.setLong(1, lastSeq); it.setString(2, lastChain); it.setString(3, digest); it.executeUpdate() }
                c.commit()
            }
            for ((label, source) in listOf("plain" to ds, "rewriteBatchedInserts" to PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$port/reef?reWriteBatchedInserts=true"); user = "postgres"; password = "s0" })) {
                for (batch in listOf(10, 120)) {
                    val digests = (0 until (System.getenv("S0_BINDINGS") ?: "10").toInt()).map { (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64) }
                    digests.forEach { projector.open(it, 1) }
                    val t0 = System.nanoTime(); var total = 0
                    for (d in digests) for (chunk in recs.chunked(batch)) { batched(source, d, chunk); total += chunk.size }
                    val secs = (System.nanoTime() - t0) / 1e9
                    S0Metrics.record("projector.batchedDecisionsPerSec.$label.batch$batch", Math.round(total / secs))
                    log.append("C2 JDBC-batched ($label) batch=$batch  $total decisions in ${"%.2f".format(secs)} s = ${"%.0f".format(total / secs)} decisions/s (${"%.2f".format(secs * 1000 / total)} ms/decision)\n")
                }
            }
            println(log); Files.writeString(S0Metrics.file("s0-projector.txt"), log)
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", name).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }
}
