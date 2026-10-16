package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * Byte inventory of the capacity lane (research only: `-PbrokerTests -PresearchTests`; see docs/work/CALCIFY_BYTES_FOOTPRINT_SPIKE.md). The chain is the one of
 * [CapacityLaneThroughputTest] (resolved match contexts, the capacity Streams app, per-lane decision logs and Postgres projections, Redpanda and Postgres 16 in
 * containers) but the point is storage, not rate: after the run it reports, per booked trade and net of the funding done before the clock starts,
 *  - per broker topic: records, key+value bytes as consumed (uncompressed), and bytes on disk (every `.log` segment walked batch by batch, so a preallocated
 *    active segment does not count), split into data batches, transaction control batches and other batches, with the batch counts and the codec;
 *    the changelog also by key class, live keys and the bytes of the last value of each live key;
 *  - per Postgres relation: heap, each index, toast, row counts, the average stored size of every column, pgstattuple/pgstatindex;
 *  - the WAL: the LSN delta, `pg_stat_wal` (records, full page images, bytes) and `pg_waldump` attributed to the relation written.
 *
 * Environment: CAP_TRADES (300000), CAP_RATE (trades/s, 0 = as fast as the producer can), CAP_LANES (1), CAP_INSTRUMENTS (64), CAP_ACCOUNTS (200),
 * CAP_DECISION_MODE (`two-step` or `capture-settle-v1`), CAP_IDS (`synthetic`, the soak's `exec-<i>`, or `real`: `exec-v2-<64 hex>` as the matching engine writes them),
 * CAP_STREAMS_COMPRESSION (producer.compression.type of the Streams app: none, lz4, zstd, snappy, gzip), CAP_CAPTURE_COMPRESSION (the load generator's producer; the
 * production resolver uses lz4), CAP_TOPIC_CONFIG (`k=v,k=v` for capture, control and decisions), CAP_CHANGELOG_CONFIG (`k=v,k=v`; when set the changelog is created up
 * front, compacted, with these), CAP_STREAMS_PROPS (`k=v,k=v` more client properties), CAP_PG_ALTER_SYSTEM (`k=v;k=v`), CAP_PG_SETUP_SQL (SQL run after the migrations),
 * CAP_PG_ARGS (extra postgres server arguments), CAP_SETTLE_SECONDS (wait after the last projected decision before the final snapshot, default 3),
 * CAP_WAL_DUMP (true), CAP_LABEL. S4_RP_DOCKER / S4_PG_DOCKER add `docker run` options as in the other harnesses.
 */
@Tag("broker") @Tag("research")
class CapacityBytesInventoryTest {
    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private fun sh(vararg cmd: String, stdin: String? = null, allowFail: Boolean = false): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
        check(code == 0 || allowFail) { "${cmd.toList()} failed: $out" }; return out
    }

    private fun kv(text: String): Map<String, String> = text.split(',', ';').map { it.trim() }.filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

    // ---- broker: segment walk inside the container (perl is in the Redpanda image) ----

    private data class DiskKey(val topic: String, val type: Int, val control: Boolean, val codec: Int)
    private class Agg(var batches: Long = 0, var records: Long = 0, var bytes: Long = 0) { operator fun minus(o: Agg) = Agg(batches - o.batches, records - o.records, bytes - o.bytes) }
    private class BrokerSnap(val log: Map<DiskKey, Agg>, val other: Map<Pair<String, String>, Long>, val segments: Map<String, Long>)

    private val walker = """
        use strict;
        my %log; my %other; my %seg; my ${'$'}root = "/var/lib/redpanda/data/kafka";
        opendir(my ${'$'}d, ${'$'}root) or exit 0;
        for my ${'$'}t (sort readdir ${'$'}d) { next if ${'$'}t =~ /^\./;
          opendir(my ${'$'}pd, "${'$'}root/${'$'}t") or next;
          for my ${'$'}p (readdir ${'$'}pd) { next if ${'$'}p =~ /^\./;
            opendir(my ${'$'}fd, "${'$'}root/${'$'}t/${'$'}p") or next;
            for my ${'$'}f (readdir ${'$'}fd) { next if ${'$'}f =~ /^\./;
              my ${'$'}path = "${'$'}root/${'$'}t/${'$'}p/${'$'}f"; next unless -f ${'$'}path;
              if (${'$'}f =~ /\.log${'$'}/) {
                ${'$'}seg{${'$'}t}++;
                open(my ${'$'}fh, "<", ${'$'}path) or next; binmode ${'$'}fh;
                while (1) {
                  my ${'$'}h; my ${'$'}n = read(${'$'}fh, ${'$'}h, 61); last if ${'$'}n != 61;
                  my @v = unpack("L< l< q< c L< s< l< q< q< q< s< l< l<", ${'$'}h);
                  my ${'$'}size = ${'$'}v[1]; last if ${'$'}size < 61;
                  my ${'$'}attrs = ${'$'}v[5] & 0xffff; my ${'$'}key = join("|", ${'$'}t, ${'$'}v[3], ((${'$'}attrs & 0x20) ? 1 : 0), (${'$'}attrs & 7));
                  ${'$'}log{${'$'}key}[0]++; ${'$'}log{${'$'}key}[1] += ${'$'}v[12]; ${'$'}log{${'$'}key}[2] += ${'$'}size;
                  seek(${'$'}fh, ${'$'}size - 61, 1);
                }
                close ${'$'}fh;
              } else {
                my ${'$'}ext = ${'$'}f; ${'$'}ext =~ s/^[0-9]+-[0-9]+-v[0-9]+\.//; ${'$'}other{"${'$'}t|${'$'}ext"} += -s ${'$'}path;
              }
            }
          }
        }
        print "D|${'$'}_|", join("|", @{${'$'}log{${'$'}_}}), "\n" for sort keys %log;
        print "F|${'$'}_|${'$'}other{${'$'}_}\n" for sort keys %other;
        print "S|${'$'}_|${'$'}seg{${'$'}_}\n" for sort keys %seg;
    """.trimIndent()

    private fun brokerSnap(rp: String): BrokerSnap {
        val out = sh("docker", "exec", "-i", rp, "perl", "-", stdin = walker)
        val log = HashMap<DiskKey, Agg>(); val other = HashMap<Pair<String, String>, Long>(); val seg = HashMap<String, Long>()
        for (line in out.lines()) {
            val p = line.split('|')
            when (p.firstOrNull()) {
                "D" -> log[DiskKey(p[1], p[2].toInt(), p[3] == "1", p[4].toInt())] = Agg(p[5].toLong(), p[6].toLong(), p[7].toLong())
                "F" -> other[p[1] to p[2]] = p[3].toLong()
                "S" -> seg[p[1]] = p[2].toLong()
            }
        }
        return BrokerSnap(log, other, seg)
    }

    // ---- consumed (uncompressed) key+value accounting ----

    private class KeyClassStats(var puts: Long = 0, var keyBytes: Long = 0, var valueBytes: Long = 0)
    private class TopicConsumed(var records: Long = 0, var keyBytes: Long = 0, var valueBytes: Long = 0, val classes: MutableMap<String, KeyClassStats> = HashMap(), var liveKeys: Long = 0, var liveBytes: Long = 0) {
        fun minus(o: TopicConsumed): TopicConsumed {
            val r = TopicConsumed(records - o.records, keyBytes - o.keyBytes, valueBytes - o.valueBytes, HashMap(), liveKeys - o.liveKeys, liveBytes - o.liveBytes)
            for ((k, v) in classes) { val b = o.classes[k]; r.classes[k] = KeyClassStats(v.puts - (b?.puts ?: 0), v.keyBytes - (b?.keyBytes ?: 0), v.valueBytes - (b?.valueBytes ?: 0)) }
            return r
        }
    }

    private fun consume(bootstrap: String, topic: String, baselineEnd: Map<Int, Long>, classify: Boolean): Pair<TopicConsumed, TopicConsumed> {
        val total = TopicConsumed(); val before = TopicConsumed(); val live = HashMap<String, Int>()
        var liveBefore = 0L; var liveKeysBefore = 0L
        KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); put(ConsumerConfig.GROUP_ID_CONFIG, "bytes-reader-${System.nanoTime()}"); put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        }).use { c ->
            val parts = c.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
            c.assign(parts); c.seekToBeginning(parts)
            val ends = c.endOffsets(parts)
            val deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos()
            val done = HashSet<TopicPartition>()
            val crossed = HashSet<Int>()
            while (done.size < parts.size) {
                for (r in c.poll(Duration.ofMillis(200))) {
                    val p = r.partition(); val tp = TopicPartition(topic, p)
                    val isRun = r.offset() >= (baselineEnd[p] ?: 0L)
                    if (isRun && crossed.add(p)) { liveBefore += live.values.sumOf { it.toLong() } - liveBefore.takeIf { false }.let { 0L }; liveKeysBefore = live.size.toLong() }
                    val k = maxOf(0, r.serializedKeySize()); val v = maxOf(0, r.serializedValueSize())
                    total.records++; total.keyBytes += k; total.valueBytes += v
                    if (!isRun) { before.records++; before.keyBytes += k; before.valueBytes += v }
                    if (classify && r.key() != null) {
                        val ks = String(r.key(), Charsets.ISO_8859_1)
                        val cls = if (ks.startsWith("e/")) ks.substring(0, 3) else ks
                        if (isRun) { val s = total.classes.getOrPut(cls) { KeyClassStats() }; s.puts++; s.keyBytes += k; s.valueBytes += v }
                        if (r.value() == null) live.remove(ks) else live[ks] = k + v
                    }
                }
                for (tp in parts) if (c.position(tp) >= (ends[tp] ?: 0L)) done.add(tp)
                check(System.nanoTime() < deadline) { "timed out reading $topic" }
            }
            if (classify) { total.liveKeys = live.size.toLong(); total.liveBytes = live.values.sumOf { it.toLong() }; before.liveKeys = liveKeysBefore; before.liveBytes = liveBefore }
        }
        return total to before
    }

    // ---- postgres ----

    private fun psql(pg: String, sql: String): List<List<String>> =
        sh("docker", "exec", "-i", pg, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|", stdin = sql).lines().filter { it.isNotBlank() }.map { it.split('|') }

    private fun hex(i: Int, tag: String): String = MessageDigest.getInstance("SHA-256").digest("$tag-$i".toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun bytesPerTrade() {
        val label = env("CAP_LABEL", "run")
        val lanes = env("CAP_LANES", "1").toInt(); val trades = env("CAP_TRADES", "300000").toInt(); val accountCount = env("CAP_ACCOUNTS", "200").toInt()
        val instrumentCount = env("CAP_INSTRUMENTS", "64").toInt(); val rate = env("CAP_RATE", "0").toDouble(); val realIds = env("CAP_IDS", "synthetic") == "real"
        val instruments = (0 until instrumentCount).map { "INS$it" }
        val mode = DecisionMode.ofWire(env("CAP_DECISION_MODE", DecisionMode.TWO_STEP.wire)); val decisionsPerTrade = if (mode == DecisionMode.CAPTURE_SETTLE) 1L else 2L
        val laneSets = CapacityRouting.engineLanes("p3-run", "p3-session", instruments, lanes)
        val plan = CapacityPlan("bytes-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), laneSets,
            trades + 10, trades + 10, 2 * trades + 10, accountCount * (lanes + instrumentCount) + 10, sequenceBound = 1_000_000_000L, decisionMode = mode)
        val accounts = (0 until accountCount).map { AccountId("u$it") }
        val topics = CapacityTopics("BYTES_CAPTURE", "BYTES_CONTROL", "BYTES_DECISIONS")
        val appId = "capacity-bytes"; val changelog = "$appId-capacity-finance-changelog"

        fun resolvedContext(i: Int, ins: String, b: AccountId, s: AccountId, units: Long, nanos: Long): ByteArray {
            val exec = if (realIds) "exec-v2-" + hex(i, "e") else "exec-$i"
            val buyOrder = if (realIds) "ord-" + hex(i, "b").substring(0, 32) else "b-$i"; val sellOrder = if (realIds) "ord-" + hex(i, "s").substring(0, 32) else "s-$i"
            val c = reef.contracts.calcify.v1.MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("p3-run")
            c.tradeBuilder.setRunId("p3-run").factBuilder.setExecutionId(exec).setInstrumentId(ins).setBuyOrderId(buyOrder).setSellOrderId(sellOrder).quantityBuilder.setUnits(units.toString())
            c.tradeBuilder.factBuilder.priceBuilder.setNanos(nanos.toString()).setCurrency("USD")
            c.buyAcceptedOrderBuilder.factBuilder.setOrderId(buyOrder).setVenueSessionId("p3-session").setInstrumentId(ins).setAccountId(b.value).setParticipantId("pb")
            c.sellAcceptedOrderBuilder.factBuilder.setOrderId(sellOrder).setVenueSessionId("p3-session").setInstrumentId(ins).setAccountId(s.value).setParticipantId("ps")
            return c.build().toByteArray()
        }

        val pgName = "cap-bytes-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        val pgArgs = env("CAP_PG_ARGS", "").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toTypedArray()
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine", "postgres", "-c", "wal_keep_size=6GB", *pgArgs)
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql", "0017_calcify_finance_decision_digest_checks.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            val alter = kv(env("CAP_PG_ALTER_SYSTEM", ""))
            if (alter.isNotEmpty()) { psql(pgName, alter.entries.joinToString("\n") { (k, v) -> "ALTER SYSTEM SET $k = '$v';" } + "\nSELECT pg_reload_conf();"); Thread.sleep(500) }
            env("CAP_PG_SETUP_SQL", "").takeIf { it.isNotBlank() }?.let { psql(pgName, it) }
            psql(pgName, "CREATE EXTENSION IF NOT EXISTS pgstattuple;")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "cap"; reWriteBatchedInserts = true }

            DisposableRedpanda(smp = env("S4_SMP", "1").toInt(), memory = env("S4_BROKER_MEM", "2G")).start().use { rp ->
                val topicConfig = mapOf("retention.ms" to "-1", "retention.bytes" to "-1") + kv(env("CAP_TOPIC_CONFIG", ""))
                rp.admin().use { admin ->
                    admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, lanes, 1.toShort()).configs(topicConfig) }).all().get(30, TimeUnit.SECONDS)
                    kv(env("CAP_CHANGELOG_CONFIG", "")).takeIf { it.isNotEmpty() }?.let { cfg ->
                        admin.createTopics(listOf(NewTopic(changelog, lanes, 1.toShort()).configs(mapOf("cleanup.policy" to "compact") + cfg))).all().get(30, TimeUnit.SECONDS)
                    }
                }
                val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
                put(client, "producer.compression.type", env("CAP_STREAMS_COMPRESSION", "none"))
                kv(env("CAP_STREAMS_PROPS", "")).forEach { (k, v) -> client.put(k, v) }
                val stateDir = Files.createTempDirectory("capacity-bytes-state").toFile()
                val launcher = CapacityLauncher(plan, CapacityResolvedContextDecoder(plan), topics, appId, client, client, stateDir.path, ds, maxApply = env("CAP_MAX_APPLY", "2000").toInt()).start()
                val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                    put(ProducerConfig.LINGER_MS_CONFIG, 5); put(ProducerConfig.BATCH_SIZE_CONFIG, 131_072); put(ProducerConfig.COMPRESSION_TYPE_CONFIG, env("CAP_CAPTURE_COMPRESSION", "none"))
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                })
                fun projected(): Long = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COALESCE(SUM(last_sequence), 0) FROM boundary.calcify_finance_checkpoint").use { rs -> rs.next(); rs.getLong(1) } } }
                try {
                    var funds = 0L
                    CapacityControlWriter(client, topics.control, plan).use { w ->
                        for (a in accounts) { w.fundCash("seed-${a.value}", a, List(lanes) { 1_000_000_000_000L }); funds += lanes; for (ins in instruments) { w.fundSecurity("seed-${a.value}", a, ins, 1_000_000L); funds++ } }
                    }
                    val fundDeadline = System.nanoTime() + Duration.ofMinutes(5).toNanos()
                    while (projected() < funds) { check(launcher.healthy()) { "service unhealthy during funding: ${launcher.projectionFailure()}" }; check(System.nanoTime() < fundDeadline) { "funding not projected: ${projected()} of $funds" }; Thread.sleep(200) }
                    Thread.sleep(2000)

                    // ---- baseline (after funding, before the clock) ----
                    val baseBroker = brokerSnap(rp.name)
                    val baseEnd: Map<String, Map<Int, Long>> = listOf(topics.capture, topics.control, topics.decisions, changelog).associateWith { t ->
                        KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
                            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                        }).use { c -> val parts = c.partitionsFor(t).map { TopicPartition(t, it.partition()) }; c.endOffsets(parts).mapKeys { it.key.partition() } }
                    }
                    val pgBase = psql(pgName, "SELECT pg_current_wal_lsn(), wal_records, wal_fpi, wal_bytes FROM pg_stat_wal;").single()
                    val ckptBase = psql(pgName, "SELECT checkpoints_timed, checkpoints_req FROM pg_stat_bgwriter;").single().map { it.toLong() }
                    val relBase = relSizes(pgName)
                    val cpuBase = cpuByGroup()

                    // ---- load ----
                    val rnd = Random(3); val t0 = System.nanoTime()
                    for (i in 0 until trades) {
                        if (rate > 0) { val due = t0 + (i / rate * 1e9).toLong(); while (System.nanoTime() < due) LockSupport.parkNanos(100_000) }
                        val ins = instruments[rnd.nextInt(instrumentCount)]; val b = accounts[rnd.nextInt(accountCount)]; var s = accounts[rnd.nextInt(accountCount)]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accountCount]
                        producer.send(ProducerRecord(topics.capture, plan.laneOf(ins)!!, ByteArray(0), resolvedContext(i, ins, b, s, 1L + rnd.nextInt(4), 1_000L + rnd.nextInt(50))))
                    }
                    producer.flush(); val tSent = System.nanoTime()
                    val expected = funds + decisionsPerTrade * trades
                    var last = 0L; var lastChange = System.nanoTime()
                    while (true) {
                        check(launcher.healthy()) { "service unhealthy: stream=${launcher.streamsState()} projection=${launcher.projectionFailure()}" }
                        val p = projected(); if (p >= expected) break
                        if (p != last) { last = p; lastChange = System.nanoTime() }
                        check(System.nanoTime() - lastChange < Duration.ofMinutes(3).toNanos()) { "projection stalled at $p of $expected" }
                        Thread.sleep(100)
                    }
                    val tDone = System.nanoTime()
                    val cpuEnd = cpuByGroup()
                    Thread.sleep(env("CAP_SETTLE_SECONDS", "3").toLong() * 1000)

                    // ---- final snapshot ----
                    val endBroker = brokerSnap(rp.name)
                    val pgEnd = psql(pgName, "SELECT pg_current_wal_lsn(), wal_records, wal_fpi, wal_bytes FROM pg_stat_wal;").single()
                    val ckptEnd = psql(pgName, "SELECT checkpoints_timed, checkpoints_req FROM pg_stat_bgwriter;").single().map { it.toLong() }
                    val relEnd = relSizes(pgName)
                    report(label, mode, trades, rate, realIds, lanes, tSent - t0, tDone - t0, cpuBase, cpuEnd)
                    brokerReport(trades, baseBroker, endBroker, listOf(topics.capture, topics.control, topics.decisions, changelog), rp.bootstrap, baseEnd, changelog)
                    brokerConfigs(rp, listOf(topics.capture, topics.decisions, changelog))
                    val walBytes = psql(pgName, "SELECT pg_wal_lsn_diff('${pgEnd[0]}', '${pgBase[0]}');").single()[0].toDouble()
                    println("BYTES-PG-WAL lsn_delta_per_trade=${"%.1f".format(walBytes / trades)} records_per_trade=${"%.2f".format((pgEnd[1].toLong() - pgBase[1].toLong()).toDouble() / trades)} fpi_per_trade=${"%.3f".format((pgEnd[2].toLong() - pgBase[2].toLong()).toDouble() / trades)} stat_bytes_per_trade=${"%.1f".format((pgEnd[3].toLong() - pgBase[3].toLong()).toDouble() / trades)} checkpoints_timed=${ckptEnd[0] - ckptBase[0]} checkpoints_req=${ckptEnd[1] - ckptBase[1]} total_lsn_delta_mib=${"%.1f".format(walBytes / 1048576)}")
                    pgReport(pgName, trades, relBase, relEnd)
                    if (env("CAP_WAL_DUMP", "true").toBoolean()) walDump(pgName, pgBase[0], pgEnd[0], trades)
                    val stateBytes = stateDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    println("BYTES-STATE-DIR rocksdb_bytes_per_trade=${"%.1f".format(stateBytes.toDouble() / (trades + funds / 2))} total_mib=${"%.1f".format(stateBytes / 1048576.0)}")
                    assertEquals(expected, projected())
                } finally { launcher.close(); producer.close(); stateDir.deleteRecursively() }
            }
        } finally { sh("docker", "rm", "-f", "-v", pgName, allowFail = true) }
    }

    private fun put(p: Properties, k: String, v: String) { p.put(k, v) }

    private fun cpuByGroup(): Map<String, Long> {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
        return Thread.getAllStackTraces().keys.groupBy { th -> when { th.name.startsWith("capacity-projection") -> "projection"; th.name.contains("StreamThread") -> "streams"; th.name.contains("kafka-producer") || th.name.contains("kafka-coordinator") -> "kafka-clients"; else -> "other" } }
            .mapValues { (_, ths) -> ths.sumOf { th -> mx.getThreadCpuTime(th.id).coerceAtLeast(0) } }
    }

    private fun report(label: String, mode: DecisionMode, trades: Int, rate: Double, realIds: Boolean, lanes: Int, sentNanos: Long, doneNanos: Long, cpu0: Map<String, Long>, cpu1: Map<String, Long>) {
        println("BYTES-RUN label=$label mode=${mode.wire} trades=$trades lanes=$lanes rate=${if (rate > 0) rate.toString() else "max"} ids=${if (realIds) "real" else "synthetic"} sent_s=${"%.1f".format(sentNanos / 1e9)} done_s=${"%.1f".format(doneNanos / 1e9)} " +
            "env_streams_compression=${System.getenv("CAP_STREAMS_COMPRESSION") ?: "none"} env_capture_compression=${System.getenv("CAP_CAPTURE_COMPRESSION") ?: "none"}")
        println("BYTES-CPU " + listOf("projection", "streams", "kafka-clients").joinToString(" ") { g -> "${g}_us_per_trade=${"%.1f".format(((cpu1[g] ?: 0) - (cpu0[g] ?: 0)) / 1e3 / trades)}" })
    }

    private fun brokerConfigs(rp: DisposableRedpanda, names: List<String>) {
        rp.admin().use { admin ->
            val cfg = admin.describeConfigs(names.map { ConfigResource(ConfigResource.Type.TOPIC, it) }).all().get(30, TimeUnit.SECONDS)
            for ((res, c) in cfg) println("BYTES-TOPIC-CONFIG ${res.name()} " + c.entries().filter { it.name() in setOf("cleanup.policy", "compression.type", "retention.ms", "retention.bytes", "segment.bytes", "segment.ms", "min.cleanable.dirty.ratio", "min.compaction.lag.ms", "delete.retention.ms", "max.compaction.lag.ms") }.joinToString(" ") { "${it.name()}=${it.value()}" })
        }
    }

    private fun brokerReport(trades: Int, base: BrokerSnap, end: BrokerSnap, tracked: List<String>, bootstrap: String, baseEnd: Map<String, Map<Int, Long>>, changelog: String) {
        val topicsOnDisk = (end.log.keys.map { it.topic } + base.log.keys.map { it.topic }).toSortedSet()
        println("BYTES-BROKER-DISK topic | data_batches/trade data_records/trade data_bytes/trade | txn_control_batches/trade control_bytes/trade | other_bytes/trade | total_log_bytes/trade | index+other_files_bytes/trade | codec")
        var grand = 0.0
        for (t in topicsOnDisk) {
            fun sum(snap: BrokerSnap, f: (DiskKey) -> Boolean): Agg { val a = Agg(); for ((k, v) in snap.log) if (k.topic == t && f(k)) { a.batches += v.batches; a.records += v.records; a.bytes += v.bytes }; return a }
            val data = sum(end) { it.type == 1 && !it.control } - sum(base) { it.type == 1 && !it.control }
            val ctl = sum(end) { it.type == 1 && it.control } - sum(base) { it.type == 1 && it.control }
            val oth = sum(end) { it.type != 1 } - sum(base) { it.type != 1 }
            val total = data.bytes + ctl.bytes + oth.bytes
            val files = end.other.filterKeys { it.first == t }.values.sum() - base.other.filterKeys { it.first == t }.values.sum()
            val codecs = end.log.filterKeys { it.topic == t && it.type == 1 && !it.control }.keys.map { it.codec }.toSortedSet()
            if (total == 0L && files == 0L) continue
            fun pt(x: Long) = "%.2f".format(x.toDouble() / trades)
            println("BYTES-BROKER-DISK $t | ${pt(data.batches)} ${pt(data.records)} ${pt(data.bytes)} | ${pt(ctl.batches)} ${pt(ctl.bytes)} | ${pt(oth.bytes)} | ${pt(total)} | ${pt(files)} | codecs=$codecs")
            grand += total + files
        }
        println("BYTES-BROKER-DISK-TOTAL per_trade=${"%.1f".format(grand / trades)} (all kafka-namespace topics, log batches plus index and snapshot files, net of the post-funding baseline)")
        println("BYTES-BROKER-DISK-NOTE segment counts end=${end.segments} (a preallocated active segment is measured by walking its batches; index files exist only for rolled segments)")
        for (t in tracked) {
            val (total, before) = consume(bootstrap, t, baseEnd.getValue(t), classify = t == changelog)
            val run = total.minus(before.also { it.classes.clear() }).also { }
            val rec = (total.records - before.records); val kb = total.keyBytes - before.keyBytes; val vb = total.valueBytes - before.valueBytes
            println("BYTES-BROKER-KV $t records/trade=${"%.3f".format(rec.toDouble() / trades)} key_bytes/trade=${"%.1f".format(kb.toDouble() / trades)} value_bytes/trade=${"%.1f".format(vb.toDouble() / trades)} kv_bytes/trade=${"%.1f".format((kb + vb).toDouble() / trades)}" +
                (if (t == changelog) " live_keys_total=${total.liveKeys} live_kv_bytes_total=${total.liveBytes} live_keys_added_per_trade=${"%.3f".format((total.liveKeys - before.liveKeys).toDouble() / trades)} live_kv_bytes_added_per_trade=${"%.1f".format((total.liveBytes - before.liveBytes).toDouble() / trades)}" else ""))
            for ((cls, s) in total.classes.toSortedMap()) println("BYTES-CHANGELOG-CLASS $cls puts/trade=${"%.3f".format(s.puts.toDouble() / trades)} key_bytes/put=${"%.1f".format(s.keyBytes.toDouble() / maxOf(1, s.puts))} value_bytes/put=${"%.1f".format(s.valueBytes.toDouble() / maxOf(1, s.puts))} kv_bytes/trade=${"%.1f".format((s.keyBytes + s.valueBytes).toDouble() / trades)}")
        }
        // field breakdown of one decision record from the log
        KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.GROUP_ID_CONFIG, "bytes-sample-${System.nanoTime()}"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        }).use { c ->
            val tp = TopicPartition(decisionsTopicOf(tracked), 0); c.assign(listOf(tp)); val start = (baseEnd.getValue(tracked[2])[0] ?: 0L) + 1000; c.seek(tp, start)
            val sample = ArrayList<FinanceDecisionV1>(); val dl = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (sample.size < 500 && System.nanoTime() < dl) c.poll(Duration.ofMillis(200)).forEach { if (sample.size < 500) sample += FinanceDecisionV1.parseFrom(it.value()) }
            if (sample.isNotEmpty()) {
                fun avg(f: (FinanceDecisionV1) -> Int) = sample.sumOf { f(it).toLong() }.toDouble() / sample.size
                println("BYTES-DECISION-FIELDS sample=${sample.size} serialized=${"%.1f".format(avg { it.serializedSize })} input_field=${"%.1f".format(avg { com.google.protobuf.CodedOutputStream.computeMessageSize(9, it.input) })} " +
                    "without_input=${"%.1f".format(avg { it.serializedSize - com.google.protobuf.CodedOutputStream.computeMessageSize(9, it.input) })} action_field=${"%.1f".format(avg { com.google.protobuf.CodedOutputStream.computeMessageSize(2, it.action) })} " +
                    "digest_fields_4x66=${"%.1f".format(avg { 4 * 66 })} legs_field=${"%.1f".format(avg { d -> d.legsList.sumOf { com.google.protobuf.CodedOutputStream.computeMessageSize(5, it) } })} sequence_field=${"%.1f".format(avg { com.google.protobuf.CodedOutputStream.computeInt64Size(1, it.sequence) })} " +
                    "legs=${"%.1f".format(avg { it.legsCount })} action_id_len=${"%.1f".format(avg { it.action.actionId.length })}")
            }
        }
    }

    private fun decisionsTopicOf(tracked: List<String>) = tracked[2]

    private class Rel(val name: String, val kind: String, val bytes: Long, val filenode: Long)

    private fun relSizes(pg: String): Map<String, Rel> = psql(pg, """
        SELECT c.relname, c.relkind, pg_relation_size(c.oid), c.relfilenode FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'boundary' AND c.relkind IN ('r','i')
        UNION ALL
        SELECT 'toast:' || c.relname, 't', pg_relation_size(c.reltoastrelid), c.reltoastrelid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'boundary' AND c.relkind = 'r' AND c.reltoastrelid <> 0;
    """.trimIndent()).associate { it[0] to Rel(it[0], it[1], it[2].toLong(), it[3].toLong()) }

    private fun pgReport(pg: String, trades: Int, base: Map<String, Rel>, end: Map<String, Rel>) {
        println("BYTES-PG-REL relation kind | bytes/trade | total_bytes")
        var heap = 0L; var idx = 0L
        for ((name, r) in end.toSortedMap()) {
            val d = r.bytes - (base[name]?.bytes ?: 0L)
            if (r.kind == "r") heap += d else if (r.kind == "i") idx += d
            println("BYTES-PG-REL $name ${r.kind} | ${"%.2f".format(d.toDouble() / trades)} | ${r.bytes}")
        }
        println("BYTES-PG-REL-TOTAL heap_per_trade=${"%.1f".format(heap.toDouble() / trades)} index_per_trade=${"%.1f".format(idx.toDouble() / trades)}")
        for (row in psql(pg, "SELECT relname, n_live_tup, n_dead_tup, n_tup_ins, n_tup_upd, n_tup_hot_upd, autovacuum_count FROM pg_stat_user_tables WHERE schemaname = 'boundary' ORDER BY relname;")) println("BYTES-PG-TUPLES ${row.joinToString(" ")} (table live dead ins upd hot_upd autovacuums)")
        for (t in listOf("calcify_finance_decision", "calcify_finance_position", "calcify_finance_obligation", "calcify_finance_attempt", "calcify_finance_checkpoint")) {
            val cnt = psql(pg, "SELECT count(*) FROM boundary.$t;").single()[0].toLong()
            val st = psql(pg, "SELECT tuple_count, tuple_len, dead_tuple_count, dead_tuple_len, free_space, free_percent FROM pgstattuple('boundary.$t');").single()
            println("BYTES-PG-STATTUPLE $t rows=$cnt tuple_count=${st[0]} avg_tuple_len=${"%.1f".format(st[1].toDouble() / maxOf(1, st[0].toLong()))} dead_tuples=${st[2]} dead_len=${st[3]} free_space=${st[4]} free_percent=${st[5]}")
            val cols = psql(pg, "SELECT column_name FROM information_schema.columns WHERE table_schema = 'boundary' AND table_name = '$t' ORDER BY ordinal_position;").map { it[0] }
            if (cnt > 0 && t != "calcify_finance_checkpoint") println("BYTES-PG-COLUMNS $t " + cols.joinToString(" ") { c -> "$c=" + "%.1f".format(psql(pg, "SELECT avg(pg_column_size($c)) FROM boundary.$t;").single()[0].toDouble()) })
        }
        for (row in psql(pg, "SELECT i.indexrelid::regclass::text, (pgstatindex(i.indexrelid::regclass::text)).leaf_pages, (pgstatindex(i.indexrelid::regclass::text)).avg_leaf_density, (pgstatindex(i.indexrelid::regclass::text)).leaf_fragmentation, (pgstatindex(i.indexrelid::regclass::text)).tree_level FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'boundary' ORDER BY 1;"))
            println("BYTES-PG-INDEX ${row.joinToString(" ")} (index leaf_pages avg_leaf_density% leaf_fragmentation% tree_level)")
        println("BYTES-PG-DIRS " + psql(pg, "SELECT pg_database_size('reef'), (SELECT coalesce(sum(size),0) FROM pg_ls_waldir());").single().joinToString(" ") + " (database_size wal_dir_size)")
    }

    private fun walDump(pg: String, start: String, end: String, trades: Int) {
        // busybox awk: rmgr, relfilenode of the first block reference, record length and total length (record plus full page images)
        val awk = """awk '{ rm = ${'$'}2; rec = 0; tot = 0; if (match(${'$'}0, /len \(rec\/tot\): *[0-9]+\/ *[0-9]+/)) { s = substr(${'$'}0, RSTART, RLENGTH); sub(/len \(rec\/tot\): */, "", s); n = split(s, a, /\/ */); rec = a[1]; tot = a[2] } rel = "-"; if (match(${'$'}0, /rel [0-9]+\/[0-9]+\/[0-9]+/)) { r = substr(${'$'}0, RSTART + 4, RLENGTH - 4); split(r, b, "/"); rel = b[3] } k = rm " " rel; R[k] += rec; T[k] += tot; C[k]++ } END { for (k in R) print "W " k " " R[k] " " T[k] " " C[k] }'"""
        val out = sh("docker", "exec", pg, "sh", "-c", "pg_waldump -p /var/lib/postgresql/data/pg_wal -s $start -e $end 2>&1 | $awk", allowFail = true)
        val names = psql(pg, "SELECT c.relfilenode, c.relname FROM pg_class c WHERE c.relfilenode <> 0;").associate { it[0] to it[1] }
        class W(var rec: Long = 0, var tot: Long = 0, var n: Long = 0)
        val by = HashMap<String, W>()
        for (line in out.lines()) {
            val p = line.trim().split(' ')
            if (p.size == 6 && p[0] == "W") { val key = "${p[1]} ${names[p[2]] ?: p[2]}"; val w = by.getOrPut(key) { W() }; w.rec += p[3].toLong(); w.tot += p[4].toLong(); w.n += p[5].toLong() }
            else if (line.isNotBlank() && !line.startsWith("W ")) println("BYTES-WAL-DUMP-NOTE ${line.take(160)}")
        }
        println("BYTES-WAL-DUMP rmgr relation | records/trade | record_bytes/trade | with_full_page_images_bytes/trade | fpi_bytes/trade")
        for ((k, w) in by.entries.sortedByDescending { it.value.tot }.take(40))
            println("BYTES-WAL-DUMP $k | ${"%.2f".format(w.n.toDouble() / trades)} | ${"%.1f".format(w.rec.toDouble() / trades)} | ${"%.1f".format(w.tot.toDouble() / trades)} | ${"%.1f".format((w.tot - w.rec).toDouble() / trades)}")
        println("BYTES-WAL-DUMP-TOTAL records/trade=${"%.2f".format(by.values.sumOf { it.n }.toDouble() / trades)} record_bytes/trade=${"%.1f".format(by.values.sumOf { it.rec }.toDouble() / trades)} total_with_fpi/trade=${"%.1f".format(by.values.sumOf { it.tot }.toDouble() / trades)}")
    }
}
