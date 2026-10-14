package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.NewTopic
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
import kotlin.test.Test

/**
 * S3d: the O(touched) ledger prototype (S3c) inside a real Streams processor on a real Redpanda, with the store writes the finance lane makes. Answers what S3c could not: the combined rate when the ledger and the
 * Streams puts share one thread, for a state digest on every decision (K=1) versus every 100 decisions (K=100), with and without rotation of the ledger every R entities.
 * Research only (`-PbrokerTests -PresearchTests`). Per input the processor decides one capture or settle, puts every touched entity plus a history entry, a head and a meta record into a persistent RocksDB store with a
 * changelog (caching off), and forwards one 250-byte output. It is not the finance processor: inputs are generated from the record ordinal (no protobuf decode, no restore cut, no history chain), the abort/overlay
 * journal does not exist, and rotation carries closing balances into the next ledger as funding decisions (the genesis of the next ledger) and starts a new store key prefix; the old prefix is never cleaned up.
 * Producer batching is set explicitly (262,144 and 20 ms, CAL-FIN-P2). S3D_INPUTS (default 800000), S3D_ONLY (comma list of labels), S3D_ACCOUNTS (default 5000).
 */
@Tag("broker") @Tag("research")
class S3dLaneWithLedgerSpikeTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val opening = AccountId("opening")

    private class Variant(val label: String, val ledger: Boolean = true, val commit: Boolean = true, val rootEvery: Int = 1, val rotateAt: Int = Int.MAX_VALUE)

    private inner class LedgerProcessor(private val v: Variant, private val accounts: List<AccountId>, private val storeName: String) : Processor<ByteArray, ByteArray, String, ByteArray> {
        private lateinit var ctx: ProcessorContext<String, ByteArray>
        private lateinit var kv: KeyValueStore<String, ByteArray>
        private lateinit var ledger: ScaleLedger
        private var epoch = 0; private var n = 0L; private var trade = 0L; private var position = 0L; var rotations = 0
        private val accountSet = accounts.toSet()
        private val history = ByteArray(150); private val head = ByteArray(300); private val meta = ByteArray(100); private val output = ByteArray(250)
        private fun newLedger() = ScaleLedger(accountSet, usd, aapl, opening, commit = v.commit, rootEvery = v.rootEvery)
        private fun persist() {
            for ((k, value) in ledger.touched) kv.put("e$epoch/$k", value.toByteArray())
            position++; kv.put("h/$position", history); kv.put("head", head); kv.put("meta", meta)
        }
        override fun init(context: ProcessorContext<String, ByteArray>) {
            ctx = context; kv = context.getStateStore(storeName); ledger = newLedger()
            for (a in accounts) { ledger.decide(FinanceInput.Fund(ActionKey("n", "f", "u-${a.value}"), Authority.OPERATOR, a, usd, 1_000_000_000_000L)); ledger.decide(FinanceInput.Fund(ActionKey("n", "f", "s-${a.value}"), Authority.OPERATOR, a, aapl, 1_000_000_000L)) }
        }
        private fun rotate() {
            val closing = ledger.positions.entries.filter { it.key.first != opening }.map { it.key to it.value }
            epoch++; rotations++; ledger = newLedger()
            for ((key, amount) in closing) {
                ledger.decide(FinanceInput.Fund(ActionKey("n", "carry$epoch", "${key.first.value}/${key.second.value}"), Authority.OPERATOR, key.first, key.second, amount)); persist()
            }
        }
        override fun process(record: Record<ByteArray, ByteArray>) {
            val i = n++
            if (v.ledger) {
                if (ledger.entityCount() >= v.rotateAt) rotate()
                if (i % 2L == 0L) {
                    trade++; val b = accounts[((trade * 2654435761L) and 0x7fffffff).toInt() % accounts.size]; var s = accounts[((trade * 40503L + 7) and 0x7fffffff).toInt() % accounts.size]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accounts.size]
                    ledger.decide(FinanceInput.Capture(ActionKey("n", "c", "c$trade"), ExecutionKey("r", "s", "AAPL", "e$trade"), b, s, 1, 1000))
                } else ledger.decide(FinanceInput.Settle(ActionKey("n", "s", "s$trade"), Authority.OPERATOR, ExecutionKey("r", "s", "AAPL", "e$trade"), 1))
                persist()
            } else { for (k in 0 until 8) kv.put("e/$i/$k", head) }
            ctx.forward(Record("k$i", output, record.timestamp()))
        }
    }

    private fun topology(v: Variant, accounts: List<AccountId>, processors: MutableList<LedgerProcessor>): Topology = Topology().apply {
        val store = "s3d-store"
        addSource("in", ByteArrayDeserializer(), ByteArrayDeserializer(), "S3D_IN")
        addProcessor("p", { LedgerProcessor(v, accounts, store).also { processors += it } }, "in")
        addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(store), Serdes.String(), Serdes.ByteArray()).withCachingDisabled(), "p")
        addSink("out", "S3D_OUT", org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "p")
    }

    private fun countOutput(bootstrap: String, target: Long, seconds: Long): Long {
        val c = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
            put(ConsumerConfig.GROUP_ID_CONFIG, "d-${System.nanoTime()}"); put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10_000)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        })
        c.use {
            it.subscribe(listOf("S3D_OUT")); var seen = 0L; val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
            while (seen < target && System.nanoTime() < deadline) seen += it.poll(Duration.ofMillis(100)).count()
            return seen
        }
    }

    private val variants = listOf(
        Variant("standin-8puts", ledger = false),
        Variant("ledger-nocommit", commit = false),
        Variant("k1", rootEvery = 1),
        Variant("k100", rootEvery = 100),
        Variant("k1-rotate1m", rootEvery = 1, rotateAt = 1_000_000),
        Variant("k100-rotate1m", rootEvery = 100, rotateAt = 1_000_000),
        Variant("k100-rotate250k", rootEvery = 100, rotateAt = 250_000),
    )

    @Test fun combinedRate() {
        val n = (System.getenv("S3D_INPUTS") ?: "800000").toInt(); val accountCount = (System.getenv("S3D_ACCOUNTS") ?: "5000").toInt()
        val only = System.getenv("S3D_ONLY")?.split(",")?.map { it.trim() }
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }
        val log = StringBuilder("\nS3d ledger prototype inside the Streams lane ($n inputs, $accountCount accounts, EOS v2, one thread, producer batch 262144 / linger 20 ms, store puts per input = touched entities + 3)\n")
        for (v in variants.filter { only == null || it.label in only }) {
            DisposableRedpanda().start().use { rp ->
                rp.admin().use { a -> a.createTopics(listOf(NewTopic("S3D_IN", 1, 1.toShort()), NewTopic("S3D_OUT", 1, 1.toShort()))).all().get(30, TimeUnit.SECONDS) }
                val processors = ArrayList<LedgerProcessor>()
                val props = FinanceTopology.streamsProperties("s3d-${v.label}", rp.bootstrap, Files.createTempDirectory("s3d-state").toString()).apply { put("producer.batch.size", 262_144); put("producer.linger.ms", 20) }
                val streams = KafkaStreams(topology(v, accounts, processors), props)
                streams.setUncaughtExceptionHandler { e -> System.err.println("S3D-ERROR ${v.label}: $e"); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
                streams.start()
                val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
                while (streams.state() != KafkaStreams.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(50)
                check(streams.state() == KafkaStreams.State.RUNNING) { "streams not running: ${streams.state()}" }
                val t0 = System.nanoTime()
                val producer = FinanceBrokerFixture.producer(rp.bootstrap)
                try { repeat(n) { producer.send(ProducerRecord("S3D_IN", 0, ByteArray(0), ByteArray(16))) }; producer.flush() } finally { producer.close() }
                val seen = countOutput(rp.bootstrap, n.toLong(), 900)
                val secs = (System.nanoTime() - t0) / 1e9
                val rotations = processors.sumOf { it.rotations }
                streams.close(Duration.ofSeconds(30))
                S0Metrics.record("laneWithLedger.${v.label}.inputsPerSec", Math.round(seen / secs))
                log.append("%-16s processed=%d in %.1f s = %.0f inputs/s (rotations %d)\n".format(v.label, seen, secs, seen / secs, rotations))
                check(seen == n.toLong()) { "${v.label}: processed $seen of $n" }
            }
        }
        println(log); Files.writeString(S0Metrics.file("s3d-lane-with-ledger.txt"), log)
    }
}
