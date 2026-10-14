package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
 * Streams-lane tuning spike (Decision 0 follow-up). The finance lane stand-in of CAL-FIN-S0 sustained 11.1k inputs/s at ten 300-byte store puts per input;
 * the arena contract needs about 19k inputs/s. This asks what moves that number: Streams commit interval, producer batching and compression, consumer fetch
 * size, store-write coalescing (record cache), a bigger broker, and several independent lanes (shards) on one broker. Research only; run with
 * `-PbrokerTests -PresearchTests`. Same stand-in as S0EndToEndCostSpikeTest (no ledger), so the figures bound what tuning can buy, not what the real lane does.
 * Ten puts per input is the order of the real processor's writes per decision (entities, history entry, head, meta); `hot` of them reuse one key (head/meta).
 *
 * S0_TUNE_RECORDS inputs per lane (default 200000); S0_TUNE_ONLY comma list of labels to run.
 */
@Tag("broker") @Tag("research")
class S0StreamsLaneTuningSpikeTest {
    private class Puts(private val perRecord: Int, private val hot: Int, private val storeName: String) : Processor<ByteArray, ByteArray, String, ByteArray> {
        private lateinit var ctx: ProcessorContext<String, ByteArray>
        private lateinit var kv: KeyValueStore<String, ByteArray>
        private var n = 0L
        override fun init(context: ProcessorContext<String, ByteArray>) { ctx = context; kv = context.getStateStore(storeName) }
        override fun process(record: Record<ByteArray, ByteArray>) {
            n++
            val v = ByteArray(300) { (it + n).toByte() }
            for (i in 0 until perRecord - hot) kv.put("e/$n/$i", v)
            for (i in 0 until hot) kv.put("hot/$i", v)
            ctx.forward(Record("k", ByteArray(100), record.timestamp()))
        }
    }

    private fun topology(lane: Int, puts: Int, hot: Int, caching: Boolean): Topology = Topology().apply {
        val store = "s0-store"
        addSource("in", ByteArrayDeserializer(), ByteArrayDeserializer(), "T_IN_$lane")
        addProcessor("p", { Puts(puts, hot, store) }, "in")
        val builder = Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(store), Serdes.String(), Serdes.ByteArray())
        addStateStore(if (caching) builder.withCachingEnabled() else builder.withCachingDisabled(), "p")
        addSink("out", "T_OUT_$lane", org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "p")
    }

    private fun countOutput(bootstrap: String, lane: Int, target: Long, seconds: Long): Long {
        val c = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
            put(ConsumerConfig.GROUP_ID_CONFIG, "t-${System.nanoTime()}"); put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10_000)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        })
        c.use {
            it.subscribe(listOf("T_OUT_$lane")); var seen = 0L; val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
            while (seen < target && System.nanoTime() < deadline) seen += it.poll(Duration.ofMillis(100)).count()
            return seen
        }
    }

    private fun waitRunning(s: KafkaStreams) {
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (s.state() != KafkaStreams.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(50)
        check(s.state() == KafkaStreams.State.RUNNING) { "streams not running: ${s.state()}" }
    }

    private class Variant(val label: String, val overrides: Map<String, Any> = emptyMap(), val lanes: Int = 1, val hot: Int = 0, val caching: Boolean = false, val smp: Int = 1, val memory: String = "1G", val puts: Int = 10)

    private val batching = mapOf("producer.batch.size" to 262_144, "producer.linger.ms" to 20)
    private val variants = listOf(
        Variant("base"),
        Variant("commit500", mapOf("commit.interval.ms" to 500)),
        Variant("commit1000", mapOf("commit.interval.ms" to 1000)),
        Variant("batch256k-linger20", batching),
        Variant("batch256k-linger20-lz4", batching + ("producer.compression.type" to "lz4")),
        Variant("fetch-big", mapOf("consumer.max.poll.records" to 5000, "consumer.fetch.min.bytes" to 65_536, "consumer.fetch.max.wait.ms" to 50)),
        Variant("commit500-batch256k", batching + ("commit.interval.ms" to 500)),
        Variant("hot2-nocache", hot = 2),
        Variant("hot2-cache", hot = 2, caching = true, overrides = mapOf("statestore.cache.max.bytes" to 64L * 1024 * 1024)),
        Variant("broker-smp2", smp = 2, memory = "2G"),
        Variant("lanes2", lanes = 2),
        Variant("lanes4", lanes = 4),
        Variant("puts4", puts = 4),
        Variant("batch64k-linger5", mapOf("producer.batch.size" to 65_536, "producer.linger.ms" to 5)),
        Variant("batch256k-linger5", mapOf("producer.batch.size" to 262_144, "producer.linger.ms" to 5)),
        Variant("batch256k-linger50", mapOf("producer.batch.size" to 262_144, "producer.linger.ms" to 50)),
        Variant("batch1m-linger20", mapOf("producer.batch.size" to 1_048_576, "producer.linger.ms" to 20)),
        Variant("batched-lanes2", batching, lanes = 2),
        Variant("batched-lanes4", batching, lanes = 4),
        Variant("batched-puts10-hot2-cache", batching + ("statestore.cache.max.bytes" to 64L * 1024 * 1024), hot = 2, caching = true),
    )

    @Test
    fun laneTuning() {
        val n = (System.getenv("S0_TUNE_RECORDS") ?: "200000").toInt()
        val only = System.getenv("S0_TUNE_ONLY")?.split(",")?.map { it.trim() }
        val log = StringBuilder("\nS0 Streams lane tuning ($n inputs per lane, 300-byte puts, EOS v2, one thread per lane, production properties plus the override)\n")
        val repeat = (System.getenv("S0_TUNE_REPEAT") ?: "1").toInt()
        for (v0 in variants.filter { only == null || it.label in only }.flatMap { v -> (1..repeat).map { v to it } }) {
            val v = v0.first.let { b -> if (repeat == 1) b else Variant("${b.label}-r${v0.second}", b.overrides, b.lanes, b.hot, b.caching, b.smp, b.memory, b.puts) }
            DisposableRedpanda(v.smp, v.memory).start().use { rp ->
                rp.admin().use { a -> a.createTopics((0 until v.lanes).flatMap { listOf(NewTopic("T_IN_$it", 1, 1.toShort()), NewTopic("T_OUT_$it", 1, 1.toShort())) }).all().get(30, TimeUnit.SECONDS) }
                val streams = (0 until v.lanes).map { lane ->
                    val app = "t-${v.label}-$lane"; val dir = Files.createTempDirectory("t-state-$lane").toString()
                    val props = FinanceTopology.streamsProperties(app, rp.bootstrap, dir).apply { v.overrides.forEach { (k, value) -> put(k, value) } }
                    KafkaStreams(topology(lane, v.puts, v.hot, v.caching), props).also { k ->
                        k.setUncaughtExceptionHandler { e -> System.err.println("TUNE-STREAMS-ERROR ${v.label}: $e"); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
                    }
                }
                streams.forEach { it.start() }; streams.forEach { waitRunning(it) }
                val t0 = System.nanoTime()
                val seen = LongArray(v.lanes)
                val workers = (0 until v.lanes).map { lane -> thread {
                    val producer = FinanceBrokerFixture.producer(rp.bootstrap)
                    repeat(n) { producer.send(ProducerRecord("T_IN_$lane", 0, ByteArray(0), ByteArray(120))) }
                    producer.flush(); producer.close()
                    seen[lane] = countOutput(rp.bootstrap, lane, n.toLong(), 600)
                } }
                workers.forEach { it.join() }
                val secs = (System.nanoTime() - t0) / 1e9; val total = seen.sum()
                streams.forEach { it.close(Duration.ofSeconds(30)) }
                val rate = total / secs
                S0Metrics.record("streamsTuning.${v.label}.inputsPerSec", Math.round(rate))
                log.append("%-24s lanes=%d processed=%d in %.1f s = %.0f inputs/s total, %.0f per lane\n".format(v.label, v.lanes, total, secs, rate, rate / v.lanes))
                check(total == n.toLong() * v.lanes) { "${v.label}: processed $total of ${n.toLong() * v.lanes}" }
            }
        }
        println(log); Files.writeString(S0Metrics.file("s0-streams-tuning.txt"), log)
    }
}
