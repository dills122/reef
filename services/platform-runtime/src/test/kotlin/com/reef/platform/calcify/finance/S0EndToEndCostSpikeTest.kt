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

    @Test
    fun streamsCeilingAndRestore() {
        val n = (System.getenv("S0_RECORDS") ?: "20000").toInt()
        val log = StringBuilder("\nS0 results (records per run = $n, 300-byte values, EOS v2, 1 thread, commit 100 ms, caching off)\n")
        DisposableRedpanda().start().use { rp ->
            for (puts in listOf(0, 1, 10)) {
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
                val seen = countOutput(rp.bootstrap, n.toLong(), 300)
                val secs = (System.nanoTime() - t0) / 1e9
                log.append("A  puts/record=$puts  processed=$seen in ${"%.1f".format(secs)} s = ${"%.0f".format(seen / secs)} records/s  (${"%.0f".format(seen * puts / secs)} puts/s)\n")
                if (puts == 10) {
                    streams.close(Duration.ofSeconds(30))
                    val cl = "$app-s0-store-changelog"
                    val before = heapMb(); val r0 = System.nanoTime()
                    val all = FinanceBrokerFixture.readAll(rp.bootstrap, cl)
                    val readSecs = (System.nanoTime() - r0) / 1e9; System.gc(); val held = heapMb() - before
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
                        log.append("B2 restart $label: RUNNING after ${"%.1f".format((System.nanoTime() - t1) / 1e9)} s total; store restore phase ${restoreMs.get()} ms for ${restored.get()} changelog records\n")
                        s.close(Duration.ofSeconds(30))
                    }
                } else { streams.close(Duration.ofSeconds(30)) }
                producer.close()
                rp.admin().use { a -> a.deleteTopics(listOf("S0_IN", "S0_OUT")).all().get(30, TimeUnit.SECONDS) }
            }
        }
        println(log)
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "s0-streams.txt"), log)
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
                val bindings = 10; var total = 0; val digests = (0 until bindings).map { (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64) }
                digests.forEach { projector.open(it, 1) }
                val t0 = System.nanoTime()
                for (d in digests) for (chunk in recs.chunked(batch)) total += projector.apply(d, 1, chunk)
                val secs = (System.nanoTime() - t0) / 1e9
                log.append("C  batch=$batch  $total decisions in ${"%.2f".format(secs)} s = ${"%.0f".format(total / secs)} decisions/s (${"%.2f".format(secs * 1000 / total)} ms/decision)\n")
            }
            println(log); Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "s0-projector.txt"), log)
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", name).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }
}
