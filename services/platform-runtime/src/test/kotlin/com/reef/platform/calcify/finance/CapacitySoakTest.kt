package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleFixtures
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.fail
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * Paced soak of the Calcify capacity profile (research only: `-PbrokerTests -PresearchTests`; docs/work/CALCIFY_SOAK_HARNESS.md). [CapacityLaneThroughputTest] pushes a pre-generated
 * batch as fast as the producer can for 30 to 40 seconds; this holds a TARGET RATE for minutes and watches what the burst cannot show: backlog and lag growth, GC, store, broker and
 * Postgres growth, disk throughput, stability. The same chain as that test (real decoders, the capacity Streams app with a thread per lane, per-lane decision logs and Postgres
 * projections, Redpanda and Postgres 16 in containers), fed by a lazy deterministic generator and ONE paced sender thread against System.nanoTime.
 *
 * Environment: CAP_SOAK_SECONDS (120), CAP_SOAK_RATE (10000 trades/s), CAP_LANES (1), CAP_INSTRUMENTS (64), CAP_ACCOUNTS (200), CAP_HOT_SHARE (0), CAP_SOURCE (`resolved`|`lifecycle`),
 * CAP_DECISION_MODE (`two-step`|`capture-settle-v1`), CAP_EVICT (false: true keeps only open work in each lane's memory), CAP_MAX_APPLY (2000), CAP_PG_SYNC_OFF (false), CAP_SOAK_SAMPLE_SECONDS (10), CAP_SOAK_MAX_LAG_SECONDS (30),
 * CAP_SOAK_LAG_SUSTAIN_SECONDS (60), CAP_SOAK_ABORT_ON_LAG (true: stop at the first breach; false: run to the end, report and then fail), CAP_SOAK_DRAIN_SECONDS (600),
 * CAP_SOAK_STALL_SECONDS (120), CAP_SOAK_COUNT_ROWS (true), CAP_SOAK_OUT_DIR (build/capacity-soak), CAP_SOAK_LABEL (soak); S4_RP_DOCKER / S4_PG_DOCKER / S4_SMP / S4_BROKER_MEM as the other
 * capacity tests. The test fails (loudly, with the time series) if the producer or a lane is behind by more than the lag threshold for the sustain period, or if the end-of-run integrity
 * check does not balance.
 */
@Tag("broker") @Tag("research")
class CapacitySoakTest {
    private fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    /** Output of a command that may fail or hang (a sampling probe): null instead of an exception, and never longer than [seconds]. */
    private fun probe(vararg cmd: String, seconds: Long = 20): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else if (p.exitValue() == 0) out else null
    } catch (_: Exception) { null }

    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    private val threads = ManagementFactory.getThreadMXBean()
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

    private fun cpuByGroup(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (info in threads.getThreadInfo(threads.allThreadIds)) {
            if (info == null) continue
            val n = info.threadName
            val g = when {
                n.startsWith("capacity-projection") -> "projection"; n.contains("StreamThread") -> "streams"
                n.startsWith("soak-") -> "harness"; n.contains("kafka-producer") || n.contains("kafka-coordinator") -> "kafka-clients"; else -> "other"
            }
            out.merge(g, threads.getThreadCpuTime(info.threadId).coerceAtLeast(0), Long::plus)
        }
        return out
    }

    /** Sectors written (x 512 bytes) to whole disks since boot, from /proc/diskstats; null where there is no such file (macOS). */
    private fun diskBytesWritten(): Long? = try {
        File("/proc/diskstats").takeIf { it.exists() }?.readLines()?.map { it.trim().split(Regex("\\s+")) }
            ?.filter { it.size > 9 && Regex("(vd[a-z]+|sd[a-z]+|xvd[a-z]+|hd[a-z]+|nvme\\d+n\\d+)").matches(it[2]) }?.sumOf { it[9].toLong() * 512 }
    } catch (_: Exception) { null }

    private fun dirKib(root: File): Long = try { root.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) } / 1024 } catch (_: Exception) { 0L }

    private class Heads(val sequence: Long, val checksum: String)

    /**
     * The sampler's decision-log head reader: [FinanceEvidenceReader.lastValue]'s algorithm (the last read-committed data record, backing off over transaction markers) on a consumer kept
     * open for the whole run, because the production method opens a consumer per call (hundreds of milliseconds), which would skew a sample taken while 10,000 trades/s arrive. The
     * end-of-run integrity check uses the production method itself. Synchronized: the sampler and the drain loop both read.
     */
    private class HeadReader(client: Properties, private val topic: String) : AutoCloseable {
        private val consumer = org.apache.kafka.clients.consumer.KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            putAll(client)
            put(org.apache.kafka.clients.consumer.ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none"); put(org.apache.kafka.clients.consumer.ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
            put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java.name)
            put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java.name)
        })
        private val timeout = Duration.ofSeconds(30)
        private val cache = HashMap<Int, Pair<Long, Heads?>>()      // lane -> (end offset the head was read at, head)

        @Synchronized fun read(lane: Int): Heads? {
            val tp = org.apache.kafka.common.TopicPartition(topic, lane); consumer.assign(listOf(tp))
            val start = consumer.beginningOffsets(listOf(tp), timeout).getValue(tp); val end = consumer.endOffsets(listOf(tp), timeout).getValue(tp)
            cache[lane]?.let { if (it.first == end) return it.second }
            var back = 64L; var head: Heads? = null
            while (end > start && head == null) {
                val from = maxOf(start, end - back); consumer.seek(tp, from)
                var last: ByteArray? = null; val deadline = System.nanoTime() + timeout.toNanos()
                while (consumer.position(tp, timeout) < end) { consumer.poll(Duration.ofMillis(100)).forEach { last = it.value() }; check(System.nanoTime() < deadline) { "timed out reading the tail of $topic-$lane" } }
                last?.let { raw -> head = FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(raw)).first.let { Heads(it.sequence, it.checksum) } }
                if (from == start) break
                back *= 8
            }
            cache[lane] = end to head; return head
        }

        override fun close() = consumer.close(Duration.ofSeconds(5))
    }

    /** Data-directory sizes in KiB from `du` inside the containers; a field is null when its probe failed. */
    private class Sizes(val rpTotal: Long?, val rpCapture: Long?, val rpDecisions: Long?, val rpChangelog: Long?, val pgDir: Long?, val pgWal: Long?)

    @Test fun pacedSoakHoldsTheTargetRate() {
        // ---- configuration ----
        val seconds = env("CAP_SOAK_SECONDS", "120").toLong(); val rate = env("CAP_SOAK_RATE", "10000").toLong(); val lanes = env("CAP_LANES", "1").toInt()
        val instrumentCount = env("CAP_INSTRUMENTS", "64").toInt(); val accountCount = env("CAP_ACCOUNTS", "200").toInt(); val hotShare = env("CAP_HOT_SHARE", "0").toDouble()
        val source = env("CAP_SOURCE", "resolved"); val resolved = when (source) { "resolved" -> true; "lifecycle" -> false; else -> error("CAP_SOURCE must be resolved or lifecycle, not $source") }
        val mode = DecisionMode.ofWire(env("CAP_DECISION_MODE", DecisionMode.TWO_STEP.wire)); val dpt = SoakAccounting.decisionsPerTrade(mode)
        val evict = env("CAP_EVICT", "false").toBoolean()
        val maxApply = env("CAP_MAX_APPLY", "2000").toInt(); val pgSyncOff = env("CAP_PG_SYNC_OFF", "false").toBoolean()
        val sampleSeconds = env("CAP_SOAK_SAMPLE_SECONDS", "10").toLong(); val maxLagSeconds = env("CAP_SOAK_MAX_LAG_SECONDS", "30").toDouble(); val sustainSeconds = env("CAP_SOAK_LAG_SUSTAIN_SECONDS", "60").toDouble()
        val abortOnLag = env("CAP_SOAK_ABORT_ON_LAG", "true").toBoolean(); val drainSeconds = env("CAP_SOAK_DRAIN_SECONDS", "600").toLong(); val stallSeconds = env("CAP_SOAK_STALL_SECONDS", "120").toLong()
        val countRows = env("CAP_SOAK_COUNT_ROWS", "true").toBoolean(); val label = env("CAP_SOAK_LABEL", "soak").replace(Regex("[^A-Za-z0-9_.-]"), "_")
        require(seconds > 0 && rate > 0 && lanes > 0 && instrumentCount >= lanes && accountCount >= 2 && sampleSeconds > 0) { "invalid soak configuration" }
        val limit = Math.multiplyExact(seconds, rate)                       // trades this soak sends in total
        require(limit * dpt + accountCount.toLong() * (lanes + instrumentCount) < FinanceProjector.MAX_SEQUENCE_BOUND) { "planned decisions exceed the sequence bound" }
        val instruments = (0 until instrumentCount).map { "INS$it" }
        val laneSets = if (resolved) CapacityRouting.engineLanes("p3-run", "p3-session", instruments, lanes) else (0 until lanes).map { l -> instruments.filterIndexed { i, _ -> i % lanes == l }.toSet() }
        val plan = CapacityPlan("soak-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), laneSets,
            Math.toIntExact(limit + 10), Math.toIntExact(limit + 10), Math.toIntExact(2 * limit + 10), accountCount * (lanes + instrumentCount) + 10, sequenceBound = FinanceProjector.MAX_SEQUENCE_BOUND, decisionMode = mode)
        val accounts = (0 until accountCount).map { AccountId("u$it") }
        val funding = SoakFunding.sized(limit, accountCount, instrumentCount, hotShare)
        val fundsPerLane = LongArray(lanes) { SoakAccounting.fundingDecisions(plan, it, accountCount) }; val funds = fundsPerLane.sum()
        val topics = CapacityTopics("SOAK_CAPTURE", "SOAK_CONTROL", "SOAK_DECISIONS"); val appId = "capacity-soak"
        val captureBinding = FiniteLifecycleFixtures.binding().digest
        val outDir = File(env("CAP_SOAK_OUT_DIR", "build/capacity-soak")).also { it.mkdirs() }
        val csv = File(outDir, "soak-$label-${java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(java.time.LocalDateTime.now())}.csv")
        csv.writeText(SoakSample.HEADER + "\n")
        println("CAPACITY-SOAK-CONFIG label=$label seconds=$seconds rate=$rate lanes=$lanes instruments=$instrumentCount accounts=$accountCount hot_share=$hotShare source=$source mode=${mode.wire} " +
            "max_apply=$maxApply pg_sync_off=$pgSyncOff planned_trades=$limit funding_cash=${funding.cashPerAccountPerLane} funding_shares=${funding.sharesPerAccountPerInstrument} max_lag_s=$maxLagSeconds sustain_s=$sustainSeconds " +
            "abort_on_lag=$abortOnLag sample_s=$sampleSeconds heap_max_mib=${Runtime.getRuntime().maxMemory() shr 20} cores=${Runtime.getRuntime().availableProcessors()}")
        println("CAPACITY-SOAK-CSV $csv")

        // ---- containers ----
        val pgName = "cap-soak-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        val stateDir = Files.createTempDirectory("capacity-soak-state").toFile()
        try {
            fun psql(sql: String) = sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = sql)
            val pgDeadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < pgDeadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql", "0017_calcify_finance_decision_digest_checks.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            psql("CREATE SCHEMA boundary;\n$migrations")
            if (pgSyncOff) psql("ALTER DATABASE reef SET synchronous_commit = off;")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "cap"; reWriteBatchedInserts = true }
            val pgSync = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SHOW synchronous_commit").use { rs -> rs.next(); rs.getString(1) } } }
            println("CAPACITY-SOAK-POSTGRES synchronous_commit=$pgSync data_dir_mount=${probe("docker", "exec", pgName, "sh", "-c", "df -P /var/lib/postgresql/data | tail -1")?.trim()}")

            DisposableRedpanda(smp = env("S4_SMP", "1").toInt(), memory = env("S4_BROKER_MEM", "2G")).start().use { rp ->
                rp.admin().use { admin -> admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, lanes, 1.toShort()).configs(mapOf("retention.ms" to "-1", "retention.bytes" to "-1")) }).all().get(30, TimeUnit.SECONDS) }
                println("CAPACITY-SOAK-BROKER data_dir_mount=${probe("docker", "exec", rp.name, "sh", "-c", "df -P /var/lib/redpanda/data | tail -1")?.trim()}")
                val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
                val launcher = CapacityLauncher(plan, if (resolved) CapacityResolvedContextDecoder(plan) else CapacityInputDecoders(plan, captureBinding, true), topics, appId, client, client, stateDir.path, ds, maxApply = maxApply, evictSettled = evict).start()
                val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                    put(ProducerConfig.LINGER_MS_CONFIG, 5); put(ProducerConfig.BATCH_SIZE_CONFIG, 131_072)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                })
                val reader = FinanceEvidenceReader(client, appId, plan.planId, topics.capture, topics.control, topics.decisions, "$appId-capacity-finance-changelog")
                val stop = AtomicBoolean(false)
                val senderThread = AtomicReference<Thread?>(null); val generatorThread = AtomicReference<Thread?>(null); val samplerThread = AtomicReference<Thread?>(null)
                val headReader = HeadReader(client, topics.decisions)
                try {
                    // ---- state readers ----
                    fun readHeads(): List<Heads?> = (0 until lanes).map { headReader.read(it) }
                    fun readHeadsProduction(): List<Heads?> = (0 until lanes).map { lane -> reader.lastValue(topics.decisions, lane)?.let { raw -> FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(raw)).first.let { Heads(it.sequence, it.checksum) } } }
                    fun readCheckpoints(): List<Heads?> = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT binding_digest, last_sequence, last_chain FROM boundary.calcify_finance_checkpoint").use { rs ->
                        val byDigest = HashMap<String, Heads>(); while (rs.next()) byDigest[rs.getString(1)] = Heads(rs.getLong(2), rs.getString(3)); (0 until lanes).map { byDigest[plan.bindingDigest(it)] } } } }
                    fun tradesOf(h: List<Heads?>): Long = h.withIndex().sumOf { (l, x) -> SoakAccounting.trades(x?.sequence ?: 0, fundsPerLane[l], mode) }
                    fun projectedSequences(): Long = readCheckpoints().sumOf { it?.sequence ?: 0 }
                    fun sizes(): Sizes {
                        val rpOut = probe("docker", "exec", rp.name, "sh", "-c", "du -sk /var/lib/redpanda/data; du -sk /var/lib/redpanda/data/kafka/* 2>/dev/null")
                        var rpTotal: Long? = null; var rpCapture: Long? = null; var rpDecisions: Long? = null; var rpChangelog: Long? = null
                        if (rpOut != null) {
                            rpTotal = 0L; rpCapture = 0L; rpDecisions = 0L; rpChangelog = 0L
                            for (line in rpOut.lines()) {
                                val parts = line.trim().split(Regex("\\s+"), 2); if (parts.size < 2) continue; val kib = parts[0].toLongOrNull() ?: continue; val name = parts[1].substringAfterLast('/')
                                when { parts[1].endsWith("/data") -> rpTotal = kib; name == topics.capture -> rpCapture = kib; name == topics.decisions -> rpDecisions = kib; name.contains("changelog") -> rpChangelog = (rpChangelog ?: 0L) + kib }
                            }
                        }
                        val pgOut = probe("docker", "exec", pgName, "sh", "-c", "du -sk /var/lib/postgresql/data; du -sk /var/lib/postgresql/data/pg_wal")
                        var pgDir: Long? = null; var pgWal: Long? = null
                        if (pgOut != null) for (line in pgOut.lines()) { val parts = line.trim().split(Regex("\\s+"), 2); if (parts.size < 2) continue; val kib = parts[0].toLongOrNull() ?: continue; if (parts[1].endsWith("pg_wal")) pgWal = kib else pgDir = kib }
                        return Sizes(rpTotal, rpCapture, rpDecisions, rpChangelog, pgDir, pgWal)
                    }

                    // ---- funding (before the clock starts), sized so no account runs out during the whole soak ----
                    CapacityControlWriter(client, topics.control, plan).use { w ->
                        for (a in accounts) { w.fundCash("seed-${a.value}", a, List(lanes) { funding.cashPerAccountPerLane }); for (ins in instruments) w.fundSecurity("seed-${a.value}", a, ins, funding.sharesPerAccountPerInstrument) }
                    }
                    val fundDeadline = System.nanoTime() + Duration.ofMinutes(5).toNanos()
                    while (projectedSequences() < funds) { check(launcher.healthy()) { "service unhealthy during funding: ${launcher.projectionFailure()}" }; check(System.nanoTime() < fundDeadline) { "funding not projected: ${projectedSequences()} of $funds" }; Thread.sleep(200) }

                    val baseline = sizes()          // an idle broker already holds preallocated segments for its internal topics: per-trade sizes below are net of this
                    println("CAPACITY-SOAK-BASELINE after_funding rp_kib=${baseline.rpTotal} pg_dir_kib=${baseline.pgDir} pg_wal_kib=${baseline.pgWal}")

                    // ---- the lazy generator and the paced sender ----
                    val queue = ArrayBlockingQueue<SoakTrade>(131_072)
                    val generator = SoakTradeGenerator(plan, instruments, accounts, hotShare, resolved)
                    val sent = AtomicLong(); val acked = AtomicLong(); val sentPerLane = AtomicLongArray(lanes); val starved = AtomicLong(); val sendError = AtomicReference<Throwable?>(null)
                    val generatorDone = AtomicBoolean(false)
                    generatorThread.set(Thread {
                        try { while (generator.generated < limit && !stop.get()) { val t = generator.next(); while (!queue.offer(t, 100, TimeUnit.MILLISECONDS)) if (stop.get()) return@Thread } }
                        catch (e: Throwable) { sendError.compareAndSet(null, e) } finally { generatorDone.set(true) }
                    }.apply { name = "soak-generator"; isDaemon = true; start() })
                    val prefill = minOf(limit, rate * 2, 100_000L).toInt()                      // up to two seconds of trades are ready before the clock starts, so start-up is not counted as sender lateness
                    val prefillDeadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
                    while (queue.size < prefill && !generatorDone.get() && System.nanoTime() < prefillDeadline) Thread.sleep(10)
                    val t0 = System.nanoTime(); val pacer = SchedulePacer(rate, t0)
                    val senderDone = AtomicBoolean(false); val senderFinishedAt = AtomicLong(0)
                    senderThread.set(Thread {
                        val buf = ArrayList<SoakTrade>(4096)
                        try {
                            var n = 0L
                            while (n < limit && !stop.get()) {
                                val now = System.nanoTime()
                                val want = minOf(pacer.nextBatch(n, now, 4096).toLong(), limit - n).toInt()
                                if (want == 0) { LockSupport.parkNanos(pacer.nanosUntilNext(n, now).coerceIn(10_000L, 1_000_000L)); continue }
                                buf.clear(); val got = queue.drainTo(buf, want)
                                if (got < want) starved.addAndGet((want - got).toLong())      // the generator, not the schedule, is the limit
                                if (got == 0) { LockSupport.parkNanos(50_000L); continue }
                                for (t in buf) {
                                    producer.send(ProducerRecord(topics.capture, t.lane, ByteArray(0), t.bytes)) { _, e -> if (e != null) sendError.compareAndSet(null, e) else acked.incrementAndGet() }
                                    sentPerLane.incrementAndGet(t.lane)
                                }
                                n += got; sent.set(n)
                            }
                        } catch (e: Throwable) { sendError.compareAndSet(null, e) } finally { senderFinishedAt.set(System.nanoTime()); senderDone.set(true) }
                    }.apply { name = "soak-sender"; isDaemon = true; start() })

                    // ---- the sampler ----
                    val samples = java.util.Collections.synchronizedList(ArrayList<SoakSample>())
                    val producerRule = SustainedLagRule(maxLagSeconds, sustainSeconds); val bookingRule = SustainedLagRule(maxLagSeconds, sustainSeconds); val projectionRule = SustainedLagRule(maxLagSeconds, sustainSeconds)
                    val breach = AtomicReference<String?>(null)
                    val phaseRef = AtomicReference("run")
                    var prevT = 0.0; var prevSent = 0L; var prevBooked = 0L; var prevProjected = 0L; var prevRp: Long? = null; var prevPg: Long? = null
                    var prevGcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }; var prevGcMs = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
                    var prevCpu = cpuByGroup(); var prevProcessCpu = os.processCpuTime; var prevDisk = diskBytesWritten()
                    var consecutiveReadErrors = 0; var maxBookingLagS = 0.0; var maxProjectionLagS = 0.0; var maxProducerBehindS = 0.0; var maxHeapMib = 0L; var totalGcMs = 0L; var sampleErrors = 0
                    var lastRp = LongArray(4); var lastPg = LongArray(2)

                    fun collect(): SoakSample {
                        // Read the lane state first and the producer's counters last, so projected <= booked <= sent <= due holds in every row whatever the reads cost; the row's time is that of the counters.
                        var booked = prevBooked; var projected = prevProjected
                        try { projected = tradesOf(readCheckpoints()); booked = tradesOf(readHeads()); consecutiveReadErrors = 0 }
                        catch (e: Exception) { sampleErrors++; println("CAPACITY-SOAK-WARN sample could not read the lane state: $e"); check(++consecutiveReadErrors < 3) { "lane state unreadable three samples in a row: $e" } }
                        val nowNanos = System.nanoTime(); val sentNow = sent.get(); val ackedNow = acked.get()
                        val t = (nowNanos - t0) / 1e9; val dt = (t - prevT).coerceAtLeast(1e-3)
                        val phase = phaseRef.get()
                        val behind = if (phase == "run") pacer.behindTrades(sentNow, nowNanos).coerceAtMost(limit - sentNow) else 0L
                        val mem = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used shr 20; maxHeapMib = maxOf(maxHeapMib, mem)
                        val gcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }; val gcMs = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
                        val cpu = cpuByGroup(); val processCpu = os.processCpuTime
                        fun pct(g: String) = ((cpu[g] ?: 0) - (prevCpu[g] ?: 0)).coerceAtLeast(0) / 1e9 / dt * 100
                        val sz = sizes(); val rpTotal = sz.rpTotal; val pgDir = sz.pgDir
                        val pgBytes = try { ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT pg_database_size('reef')").use { rs -> rs.next(); rs.getLong(1) } } } } catch (_: Exception) { null }
                        val disk = diskBytesWritten()
                        val rpGrowth = if (rpTotal != null && prevRp != null) (rpTotal - prevRp!!) / dt else null; val pgGrowth = if (pgDir != null && prevPg != null) (pgDir - prevPg!!) / dt else null
                        val s = SoakSample(
                            phase, t, sentNow, ackedNow, booked, projected, behind,
                            (sentNow - prevSent) / dt, (booked - prevBooked) / dt, (projected - prevProjected) / dt, if (t > 0) sentNow / t else 0.0, if (t > 0) booked / t else 0.0, if (t > 0) projected / t else 0.0,
                            mem, gcCount - prevGcCount, gcMs - prevGcMs, (processCpu - prevProcessCpu).coerceAtLeast(0) / 1e9 / dt * 100, pct("streams"), pct("projection"), pct("kafka-clients"), pct("harness"),
                            rpTotal, sz.rpCapture, sz.rpDecisions, sz.rpChangelog, pgDir, sz.pgWal, pgBytes, dirKib(stateDir),
                            rpGrowth, pgGrowth, if (disk != null && prevDisk != null) (disk - prevDisk!!) / dt / (1 shl 20) else null, launcher.healthy() && sendError.get() == null,
                        )
                        totalGcMs += s.gcMsDelta
                        prevT = t; prevSent = sentNow; prevBooked = booked; prevProjected = projected; prevRp = rpTotal ?: prevRp; prevPg = pgDir ?: prevPg; prevGcCount = gcCount; prevGcMs = gcMs; prevCpu = cpu; prevProcessCpu = processCpu; prevDisk = disk ?: prevDisk
                        samples += s; csv.appendText(s.csvRow(rate) + "\n")
                        println("CAPACITY-SOAK-SAMPLE ${phase} t=${"%.0f".format(t)}s sent=$sentNow booked=$booked projected=$projected behind_s=${"%.2f".format(s.producerBehind / rate.toDouble())} lag_s(sent-booked/booked-projected)=${"%.2f".format(s.lagSentBooked / rate.toDouble())}/${"%.2f".format(s.lagBookedProjected / rate.toDouble())} " +
                            "per_s(sent/booked/projected)=${"%.0f".format(s.instSent)}/${"%.0f".format(s.instBooked)}/${"%.0f".format(s.instProjected)} cum_per_s=${"%.0f".format(s.cumSent)}/${"%.0f".format(s.cumBooked)}/${"%.0f".format(s.cumProjected)} " +
                            "heap_mib=$mem gc=+${s.gcCountDelta}/${s.gcMsDelta}ms cpu%(proc/streams/proj/clients/harness)=${"%.0f".format(s.processCpuPct)}/${"%.0f".format(s.streamsCpuPct)}/${"%.0f".format(s.projectionCpuPct)}/${"%.0f".format(s.kafkaClientsCpuPct)}/${"%.0f".format(s.harnessCpuPct)} " +
                            "rp_mib=${rpTotal?.div(1024) ?: "?"} pg_mib=${pgDir?.div(1024) ?: "?"} pgdb_mib=${pgBytes?.shr(20) ?: "?"} state_mib=${s.stateDirKib?.div(1024) ?: "?"}")
                        if (phase == "run") {
                            val pLag = s.producerBehind / rate.toDouble(); val bLag = s.lagSentBooked / rate.toDouble(); val jLag = s.lagBookedProjected / rate.toDouble()
                            maxProducerBehindS = maxOf(maxProducerBehindS, pLag); maxBookingLagS = maxOf(maxBookingLagS, bLag); maxProjectionLagS = maxOf(maxProjectionLagS, jLag)
                            val verdict = SoakVerdicts.judge(producerRule.observe(t, pLag), bookingRule.observe(t, bLag), projectionRule.observe(t, jLag))
                            if (verdict != SoakVerdict.OK && breach.get() == null) {
                                val why = when (verdict) {
                                    SoakVerdict.PRODUCER_BEHIND -> "PRODUCER BEHIND (the load generator, not the lane): at t=${"%.0f".format(t)}s the sender is ${"%.1f".format(pLag)} s of rate ($behind trades) behind its own schedule, over the ${maxLagSeconds.toInt()} s limit for at least ${sustainSeconds.toInt()} s. " +
                                        "Sent $sentNow of ${pacer.due(nowNanos)} due; sent-acked=${sentNow - ackedNow} (large means broker acknowledgements are slowing the producer); generator-starved sends=${starved.get()} (large means the generator is slower than the target); " +
                                        "harness CPU ${"%.0f".format(s.harnessCpuPct)}% of one core. The lane was not offered the target load, so this run does NOT measure the lane's capacity: lower CAP_SOAK_RATE or move the load generator. " +
                                        "(Lane lag at the same moment: booking ${"%.1f".format(bLag)} s, projection ${"%.1f".format(jLag)} s of rate; the lane was fed ${"%.0f".format(s.instSent)}/s and booked ${"%.0f".format(s.instBooked)}/s.)"
                                    SoakVerdict.LANE_BEHIND_BOOKING -> "LANE BEHIND, BOOKING: at t=${"%.0f".format(t)}s sent-booked=${s.lagSentBooked} trades = ${"%.1f".format(bLag)} s of rate, over the ${maxLagSeconds.toInt()} s limit for at least ${sustainSeconds.toInt()} s. " +
                                        "The producer is on schedule (behind by ${"%.2f".format(pLag)} s) but the Streams lane books ${"%.0f".format(s.instBooked)}/s against ${"%.0f".format(s.instSent)}/s sent: it cannot hold $rate trades/s on this host."
                                    else -> "LANE BEHIND, PROJECTION: at t=${"%.0f".format(t)}s booked-projected=${s.lagBookedProjected} trades = ${"%.1f".format(jLag)} s of rate, over the ${maxLagSeconds.toInt()} s limit for at least ${sustainSeconds.toInt()} s. " +
                                        "Booking keeps pace but the Postgres projection applies ${"%.0f".format(s.instProjected)}/s against ${"%.0f".format(s.instBooked)}/s booked (pg growth ${s.pgGrowthKibS?.let { "%.0f KiB/s".format(it) } ?: "?"}): the projection store cannot hold the rate."
                                }
                                breach.set(why)
                            }
                        }
                        return s
                    }
                    samplerThread.set(Thread {
                        try {
                            var k = 1
                            while (!stop.get()) {
                                val due = t0 + k * sampleSeconds * 1_000_000_000L
                                while (!stop.get() && System.nanoTime() < due) Thread.sleep(minOf(200L, maxOf(1L, (due - System.nanoTime()) / 1_000_000)))
                                if (stop.get()) break
                                collect(); k++
                                while (due + k * sampleSeconds * 1_000_000_000L < System.nanoTime() && k < Int.MAX_VALUE) k++      // a slow sample skips ticks instead of bursting
                            }
                        } catch (e: Throwable) { sendError.compareAndSet(null, e) }
                    }.apply { name = "soak-sampler"; isDaemon = true; start() })

                    fun evidence(): String = "CSV: $csv\n" + (listOf(SoakSample.HEADER) + samples.takeLast(8).map { it.csvRow(rate) }).joinToString("\n") { "CAPACITY-SOAK-EVIDENCE $it" }
                    fun failLoudly(headline: String): Nothing {
                        stop.set(true)
                        println("CAPACITY-SOAK-FAIL $headline"); println(evidence())
                        fail("CAPACITY SOAK FAILED: $headline\n${evidence()}")
                    }
                    fun checkAlive() {
                        sendError.get()?.let { failLoudly("harness or send error: $it") }
                        if (!launcher.healthy()) failLoudly("a lane stopped: streams=${launcher.streamsState()} projection=${launcher.projectionFailure()}")
                    }

                    // ---- run: until the planned trades are sent, a breach or a fault ----
                    while (!senderDone.get()) {
                        checkAlive()
                        if (abortOnLag) breach.get()?.let { failLoudly(it) }
                        Thread.sleep(200)
                    }
                    checkAlive()
                    producer.flush(); check(sendError.get() == null) { "send error after flush: ${sendError.get()}" }
                    val sentSeconds = (senderFinishedAt.get() - t0) / 1e9
                    phaseRef.set("drain")
                    println("CAPACITY-SOAK-PRODUCER-DONE sent=${sent.get()} in=${"%.1f".format(sentSeconds)}s target_seconds=$seconds generator_starved=${starved.get()} producer_rate=${"%.0f".format(sent.get() / sentSeconds)}/s")

                    // ---- drain: bounded wait for booking and projection of everything sent ----
                    val drainStart = System.nanoTime(); var bookedAt = 0.0; var projectedAt = 0.0
                    var lastProgress = System.nanoTime(); var lastSeen = -1L
                    while (true) {
                        checkAlive()
                        val booked = tradesOf(readHeads()); val projected = tradesOf(readCheckpoints())
                        val elapsed = (System.nanoTime() - drainStart) / 1e9
                        if (booked >= limit && bookedAt == 0.0) bookedAt = elapsed
                        if (projected >= limit) { projectedAt = elapsed; break }
                        if (booked + projected != lastSeen) { lastSeen = booked + projected; lastProgress = System.nanoTime() }
                        if ((System.nanoTime() - lastProgress) / 1e9 > stallSeconds) failLoudly("drain stalled: booked=$booked projected=$projected of $limit with no progress for ${stallSeconds}s")
                        if (elapsed > drainSeconds) failLoudly("drain did not finish within ${drainSeconds}s: booked=$booked projected=$projected of $limit (the lane was behind at the end of the producing phase; see lag columns)")
                        Thread.sleep(1000)
                    }
                    val drainTotal = (System.nanoTime() - drainStart) / 1e9
                    stop.set(true); samplerThread.get()?.join(60_000); stop.set(false)
                    val finalSample = collect()

                    // ---- integrity ----
                    val problems = ArrayList<String>()
                    val heads = readHeadsProduction(); val checkpoints = readCheckpoints()
                    val expectedSent = limit
                    if (sent.get() != expectedSent) problems += "sent ${sent.get()} != planned $expectedSent"
                    if (acked.get() != sent.get()) problems += "acked ${acked.get()} != sent ${sent.get()}"
                    val consumed = AtomicLong(); val consumedPerLane = HashMap<Int, Long>()
                    rp.admin().use { admin -> admin.listConsumerGroupOffsets(appId).partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS).filterKeys { it.topic() == topics.capture }.forEach { (tp, om) -> consumedPerLane[tp.partition()] = om.offset(); consumed.addAndGet(om.offset()) } }
                    if (consumed.get() != sent.get()) problems += "capture records consumed (committed offsets) ${consumed.get()} != sent ${sent.get()}"
                    var decisionsTotal = 0L
                    for (l in 0 until lanes) {
                        val h = heads[l]; val c = checkpoints[l]; val expectedSeq = fundsPerLane[l] + dpt * sentPerLane.get(l)
                        if (h == null) { problems += "lane $l has no decision-log head"; continue }
                        decisionsTotal += h.sequence
                        if (h.sequence != expectedSeq) problems += "lane $l decision-log head ${h.sequence} != funding ${fundsPerLane[l]} + $dpt x sent ${sentPerLane.get(l)} = $expectedSeq"
                        if (consumedPerLane[l] != sentPerLane.get(l)) problems += "lane $l consumed ${consumedPerLane[l]} capture records != sent ${sentPerLane.get(l)}"
                        if (c == null) problems += "lane $l has no projection checkpoint" else {
                            if (c.sequence != h.sequence) problems += "lane $l projection at sequence ${c.sequence} != decision-log head ${h.sequence}"
                            if (c.checksum != h.checksum) problems += "lane $l projection chain ${c.checksum.take(16)}.. != decision-log head chain ${h.checksum.take(16)}.."
                        }
                    }
                    val bookedTrades = tradesOf(heads); val projectedTrades = tradesOf(checkpoints)
                    if (bookedTrades != sent.get()) problems += "trades booked $bookedTrades != sent ${sent.get()}"
                    if (projectedTrades != sent.get()) problems += "trades projected $projectedTrades != sent ${sent.get()}"
                    if (countRows) {
                        val rows = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM boundary.calcify_finance_decision").use { rs -> rs.next(); rs.getLong(1) } } }
                        if (rows != decisionsTotal) problems += "projection holds $rows decision rows != $decisionsTotal decisions in the logs"
                    }
                    if (!launcher.healthy() || launcher.projectionFailure() != null || launcher.streamsState() != KafkaStreams.State.RUNNING) problems += "a lane stopped: streams=${launcher.streamsState()} projection=${launcher.projectionFailure()}"

                    // ---- report ----
                    fun perTrade(final: Long?, base: Long?) = if (final != null && base != null) "%.0f".format((final - base) * 1024.0 / sent.get()) else "?"      // net of the after-funding baseline; du counts allocated blocks
                    val perTradeRp = perTrade(finalSample.rpTotalKib, baseline.rpTotal); val perTradePg = perTrade(finalSample.pgDirKib, baseline.pgDir)
                    // the heap that is still reachable once everything is drained and collected: what a long session leaves resident in the lane (and the harness)
                    repeat(3) { System.gc() }
                    val liveMib = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used shr 20
                    println("CAPACITY-SOAK-LIVE evict=$evict live_heap_mib=$liveMib trades=${sent.get()} live_bytes_per_trade=${"%.0f".format(liveMib * 1048576.0 / sent.get())}")
                    println("CAPACITY-SOAK-RESULT label=$label mode=${mode.wire} source=$source lanes=$lanes target_rate=$rate seconds=$seconds sent=${sent.get()} booked=$bookedTrades projected=$projectedTrades " +
                        "producer_rate=${"%.0f".format(sent.get() / sentSeconds)}/s drain_s=${"%.1f".format(drainTotal)} booked_after_producer_s=${"%.1f".format(bookedAt)} projected_after_producer_s=${"%.1f".format(projectedAt)} " +
                        "end_to_end_projected_per_s=${"%.0f".format(sent.get() / (sentSeconds + drainTotal))} max_producer_behind_s=${"%.2f".format(maxProducerBehindS)} max_booking_lag_s=${"%.2f".format(maxBookingLagS)} max_projection_lag_s=${"%.2f".format(maxProjectionLagS)} " +
                        "max_heap_mib=$maxHeapMib gc_total_ms=$totalGcMs rp_mib=${finalSample.rpTotalKib?.div(1024) ?: "?"} pg_dir_mib=${finalSample.pgDirKib?.div(1024) ?: "?"} pgdb_mib=${finalSample.pgDatabaseBytes?.shr(20) ?: "?"} state_dir_mib=${finalSample.stateDirKib?.div(1024) ?: "?"} " +
                        "bytes_per_trade_rp=$perTradeRp bytes_per_trade_pg_dir=$perTradePg pg_synchronous_commit=$pgSync integrity=${if (problems.isEmpty()) "ok" else "FAILED"} csv=$csv")
                    if (problems.isNotEmpty()) failLoudly("integrity check failed after the drain: " + problems.joinToString("; "))
                    breach.get()?.let { failLoudly(it + " (CAP_SOAK_ABORT_ON_LAG=false: the run was completed, integrity is ok)") }
                } finally {
                    stop.set(true)
                    for (t in listOf(generatorThread, senderThread, samplerThread)) t.get()?.let { it.interrupt(); it.join(10_000) }
                    runCatching { launcher.close() }; runCatching { producer.close(Duration.ofSeconds(10)) }; runCatching { headReader.close() }
                }
            }
        } finally {
            ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
            stateDir.deleteRecursively()
        }
    }
}
