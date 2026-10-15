package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Tag

/**
 * What the lane's persistence costs on the real Streams stack, without the projector and Postgres (so the only thing running next to the lane is the broker): one lane,
 * the production decoder and processor, EOS v2, a real RocksDB store and changelog on a disposable Redpanda. Pre-generated resolved match contexts are sent in two phases
 * (a warm-up that is not measured, then the measured trades) and the run reports, per decision, the Streams thread's CPU, the JVM process CPU and the broker container's CPU,
 * plus the bytes the topics and the local store grew by. Research only (`-PbrokerTests -PresearchTests`).
 *
 * Env: CAP_TRADES (200000), CAP_WARM (40000), CAP_ACCOUNTS (200), CAP_INSTRUMENTS (64), CAP_CHECKPOINT_EVERY (1), CAP_STREAMS_PROPS (comma separated key=value added to the Streams
 * properties, for example `producer.compression.type=lz4,commit.interval.ms=1000`), CAP_LABEL, CAP_PROFILE (true prints the Streams thread's inclusive frames). S4_RP_DOCKER adds docker options.
 */
@Tag("broker") @Tag("research")
class CapacityStoreWritesBenchTest {
    private fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    private fun dirBytes(dir: java.io.File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Test fun lanePersistenceCostOnTheStreamsStack() {
        val trades = env("CAP_TRADES", "200000").toInt(); val warm = env("CAP_WARM", "40000").toInt(); val accountCount = env("CAP_ACCOUNTS", "200").toInt()
        val instrumentCount = env("CAP_INSTRUMENTS", "64").toInt(); val label = env("CAP_LABEL", "baseline")
        val checkpointEvery = env("CAP_CHECKPOINT_EVERY", "1").toInt()
        val instruments = (0 until instrumentCount).map { "INS$it" }
        val total = warm + trades
        val plan = CapacityPlan("rate-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), listOf(instruments.toSet()), total + 10, total + 10, 2 * total + 10, accountCount * (1 + instrumentCount) + 10, sequenceBound = 1_000_000_000L)
        val accounts = (0 until accountCount).map { AccountId("u$it") }
        val topics = CapacityTopics("WB_CAPTURE", "WB_CONTROL", "WB_DECISIONS")
        val appId = "capacity-wb"
        val rnd = Random(3)
        fun context(i: Int): ByteArray {
            val ins = instruments[rnd.nextInt(instrumentCount)]; val b = accounts[rnd.nextInt(accountCount)]; var s = accounts[rnd.nextInt(accountCount)]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accountCount]
            val c = reef.contracts.calcify.v1.MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("p3-run")
            c.tradeBuilder.setRunId("p3-run").factBuilder.setExecutionId("exec-$i").setInstrumentId(ins).setBuyOrderId("b-$i").setSellOrderId("s-$i").quantityBuilder.setUnits((1 + rnd.nextInt(4)).toString())
            c.tradeBuilder.factBuilder.priceBuilder.setNanos((1000 + rnd.nextInt(50)).toString()).setCurrency("USD")
            c.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$i").setVenueSessionId("p3-session").setInstrumentId(ins).setAccountId(b.value).setParticipantId("pb")
            c.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$i").setVenueSessionId("p3-session").setInstrumentId(ins).setAccountId(s.value).setParticipantId("ps")
            return c.build().toByteArray()
        }
        val schedule = (0 until total).map { context(it) }
        val extra = env("CAP_STREAMS_PROPS", "").split(",").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }

        DisposableRedpanda(smp = env("S4_SMP", "1").toInt(), memory = env("S4_BROKER_MEM", "2G")).start().use { rp ->
            rp.admin().use { admin -> admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, 1, 1.toShort()).configs(mapOf("retention.ms" to "-1", "retention.bytes" to "-1")) }).all().get(30, TimeUnit.SECONDS) }
            val stateDir = Files.createTempDirectory("capacity-wb-state").toFile()
            val config = CapacityProcessorConfig(plan, topics.capture, topics.control, topics.decisions, appId, failOnBadCapture = true, checkpointEvery = checkpointEvery, cachingEnabled = env("CAP_CACHE", "false").toBoolean())
            val props = CapacityStreams.properties(appId, rp.bootstrap, stateDir.path, 1).apply { extra.forEach { (k, v) -> put(k, v) } }
            // CAP_NOLOG=true removes the store's changelog (measurement of the upper bound only: such a lane cannot restore after a crash).
            val topology = if (env("CAP_NOLOG", "false").toBoolean()) org.apache.kafka.streams.Topology().apply {
                val decoder = CapacityResolvedContextDecoder(plan)
                addSource(org.apache.kafka.streams.AutoOffsetReset.earliest(), "capture-in", org.apache.kafka.streams.processor.TimestampExtractor { record, _ -> maxOf(1L, record.timestamp()) }, org.apache.kafka.common.serialization.ByteArrayDeserializer(), org.apache.kafka.common.serialization.ByteArrayDeserializer(), config.captureTopic)
                addSource(org.apache.kafka.streams.AutoOffsetReset.earliest(), "control-in", org.apache.kafka.streams.processor.TimestampExtractor { _, _ -> 0L }, org.apache.kafka.common.serialization.ByteArrayDeserializer(), org.apache.kafka.common.serialization.ByteArrayDeserializer(), config.controlTopic)
                addProcessor("capacity", { CapacityProcessor(config, decoder) }, "capture-in", "control-in")
                addStateStore(CapacityTopology.storeBuilder(config, true).withLoggingDisabled(), "capacity")
                addSink("decisions-out", config.decisionsTopic, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), LaneByKey, "capacity")
            } else CapacityTopology.build(config, CapacityResolvedContextDecoder(plan), true)
            val streams = org.apache.kafka.streams.KafkaStreams(topology, props)
            val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            streams.setUncaughtExceptionHandler { failed.set(it); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
            val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                put(ProducerConfig.LINGER_MS_CONFIG, 5); put(ProducerConfig.BATCH_SIZE_CONFIG, 131_072)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            })
            val admin = rp.admin()
            fun consumed(topic: String): Long = admin.listConsumerGroupOffsets(appId).partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS).filterKeys { it.topic() == topic }.values.sumOf { it.offset() }
            fun waitConsumed(topic: String, n: Long) {
                val deadline = System.nanoTime() + Duration.ofMinutes(6).toNanos()
                while (consumed(topic) < n) { check(failed.get() == null) { "lane failed: ${failed.get()}" }; check(System.nanoTime() < deadline) { "timed out at ${consumed(topic)} of $n" }; Thread.sleep(50) }
            }
            fun topicBytes(topic: String): Long = sh("docker", "exec", rp.name, "sh", "-c", "du -sb /var/lib/redpanda/data/kafka/$topic 2>/dev/null | cut -f1 || echo 0").trim().ifEmpty { "0" }.toLong()
            fun brokerCpuUs(): Long = sh("docker", "exec", rp.name, "sh", "-c", "grep usage_usec /sys/fs/cgroup/cpu.stat | cut -d' ' -f2").trim().toLong()
            val changelog = config.changelogTopic
            val mx = java.lang.management.ManagementFactory.getThreadMXBean()
            val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            fun streamCpu(): Long = Thread.getAllStackTraces().keys.filter { it.name.endsWith("StreamThread-1") }.sumOf { mx.getThreadCpuTime(it.id).coerceAtLeast(0) }
            fun producerCpu(): Long = Thread.getAllStackTraces().keys.filter { it.name.contains("kafka-producer-network-thread") }.sumOf { mx.getThreadCpuTime(it.id).coerceAtLeast(0) }
            fun producerMetric(name: String): Double = streams.metrics().values.filter { it.metricName().group() == "producer-metrics" && it.metricName().name() == name }.sumOf { (it.metricValue() as? Double) ?: 0.0 }
            try {
                streams.start()
                // Funding (control topic): cash and every security for every account.
                var funds = 0L
                for (a in accounts) {
                    producer.send(ProducerRecord(topics.control, 0, ByteArray(0), FinanceWire.toProto(FinanceInput.Fund(ActionKey("operator", "fund", "seed-${a.value}/cash"), Authority.OPERATOR, a, plan.cashAsset, 1_000_000_000_000L)).toByteArray())); funds++
                    for (ins in instruments) { producer.send(ProducerRecord(topics.control, 0, ByteArray(0), FinanceWire.toProto(FinanceInput.Fund(ActionKey("operator", "fund", "seed-${a.value}/$ins"), Authority.OPERATOR, a, AssetId(ins), 1_000_000L)).toByteArray())); funds++ }
                }
                producer.flush(); waitConsumed(topics.control, funds)
                for (i in 0 until warm) producer.send(ProducerRecord(topics.capture, 0, ByteArray(0), schedule[i]))
                producer.flush(); waitConsumed(topics.capture, warm.toLong())
                Thread.sleep(1500)                                                      // let a commit land and the JIT settle
                var profile: Thread? = null; val frames = HashMap<String, Int>(); var samples = 0; var sampling = true
                val streamThread = Thread.getAllStackTraces().keys.first { it.name.endsWith("StreamThread-1") }
                if (env("CAP_PROFILE", "false").toBoolean()) profile = Thread {
                    while (sampling) { val st = streamThread.stackTrace; samples++; val seen = HashSet<String>(); for (f in st) { val n = f.className.substringAfterLast('.') + "." + f.methodName; if (seen.add(n)) frames.merge(n, 1, Int::plus) }; Thread.sleep(1) }
                }.also { it.isDaemon = true; it.start() }
                val topicsBefore = listOf(topics.capture, topics.control, topics.decisions, changelog).associateWith { topicBytes(it) }
                val stateBefore = dirBytes(stateDir)
                val sendBefore = producerMetric("record-send-total"); val outBefore = producerMetric("outgoing-byte-total"); val sc0 = streamCpu(); val np0 = producerCpu(); val pc0 = os.processCpuTime; val bc0 = brokerCpuUs(); val t0 = System.nanoTime()
                for (i in warm until total) producer.send(ProducerRecord(topics.capture, 0, ByteArray(0), schedule[i]))
                producer.flush(); waitConsumed(topics.capture, total.toLong())
                val wall = (System.nanoTime() - t0) / 1e9; val sc1 = streamCpu(); val np1 = producerCpu(); val pc1 = os.processCpuTime; val bc1 = brokerCpuUs()
                sampling = false; profile?.join()
                Thread.sleep(500)
                val topicsAfter = listOf(topics.capture, topics.control, topics.decisions, changelog).associateWith { topicBytes(it) }
                val decisions = 2.0 * trades
                val metrics = streams.metrics().values.filter { it.metricName().group() == "producer-metrics" }.associate { it.metricName().name() to it.metricValue() }
                println("WB-BENCH [$label] trades=$trades warm=$warm accounts=$accountCount instruments=$instrumentCount checkpointEvery=$checkpointEvery props=$extra")
                println("WB-BENCH [$label] rate ${"%.0f".format(decisions / wall)} decisions/s wall ${"%.1f".format(wall)} s; streams-thread cpu ${"%.2f".format((sc1 - sc0) / decisions / 1000)} + producer-network ${"%.2f".format((np1 - np0) / decisions / 1000)} us/decision; process cpu ${"%.2f".format((pc1 - pc0) / decisions / 1000)} us/decision; broker cpu ${"%.2f".format((bc1 - bc0) * 1000.0 / decisions / 1000)} us/decision")
                println("WB-BENCH [$label] bytes per decision on the broker: " + topicsAfter.entries.joinToString(", ") { (t, b) -> "${t.removePrefix("WB_").lowercase().replace(Regex("capacity-wb-capacity-finance-"), "")}=${"%.0f".format((b - topicsBefore.getValue(t)) / decisions)}" } +
                    "; sum ${"%.0f".format(topicsAfter.entries.sumOf { it.value - topicsBefore.getValue(it.key) } / decisions)}; local store ${"%.0f".format((dirBytes(stateDir) - stateBefore) / decisions)} (dir total ${dirBytes(stateDir) / 1_000_000} MB)")
                println("WB-BENCH [$label] Streams producer per decision: records ${"%.2f".format((producerMetric("record-send-total") - sendBefore) / decisions)}, request bytes ${"%.0f".format((producerMetric("outgoing-byte-total") - outBefore) / decisions)}")
                println("WB-BENCH [$label] producer: record-send-total=${metrics["record-send-total"]} outgoing-byte-total=${metrics["outgoing-byte-total"]} batch-size-avg=${metrics["batch-size-avg"]} compression-rate-avg=${metrics["compression-rate-avg"]} records-per-request-avg=${metrics["records-per-request-avg"]} request-latency-avg=${metrics["request-latency-avg"]}")
                if (samples > 0) for ((n, c) in frames.entries.sortedByDescending { it.value }.take(60)) println("WB-PROFILE %5.1f%%  %s".format(100.0 * c / samples, n))
                // The decision log must be complete and the changelog restorable: all trades decided (2 per trade) plus funding.
                assertEquals(funds + 2L * total, consumedDecisions(rp, topics.decisions))
            } finally { admin.close(); streams.close(Duration.ofSeconds(30)); producer.close() }
        }
    }

    private fun consumedDecisions(rp: DisposableRedpanda, topic: String): Long =
        org.apache.kafka.clients.consumer.KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put("bootstrap.servers", rp.bootstrap); put("isolation.level", "read_committed")
            put("key.deserializer", org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java.name); put("value.deserializer", org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java.name)
        }).use { c -> val tp = org.apache.kafka.common.TopicPartition(topic, 0); c.assign(listOf(tp)); c.seekToBeginning(listOf(tp))
            // Count of data records (transaction markers occupy offsets), via the position after the end.
            var n = 0L; val end = c.endOffsets(listOf(tp)).getValue(tp)
            while (c.position(tp) < end) n += c.poll(Duration.ofMillis(300)).count()
            n }
}
