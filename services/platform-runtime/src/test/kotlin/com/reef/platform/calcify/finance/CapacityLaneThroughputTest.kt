package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import java.nio.file.Files
import java.nio.file.Path
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
import org.postgresql.ds.PGSimpleDataSource

/**
 * Product-path rate on one machine (research only: `-PbrokerTests -PresearchTests`). The same chain as [CapacityEndToEndTest] (real decoders, the capacity Streams app
 * with one thread per lane, per-lane decision logs and projections, Redpanda and Postgres 16 in containers) fed a pre-generated schedule as fast as the producer can
 * write it. The rate is projected decisions per second: decisions the projections hold, divided by the time from the first trade record sent to the last decision
 * projected (funding is decided before the clock starts). Compare CAL-FIN-P5 (stand-in ledger, one history entry per record): 1 lane 11.1k, 2 lanes 15.3k, 3 lanes 17.5k.
 *
 * CAP_LANES (1), CAP_TRADES (100000), CAP_ACCOUNTS (200), CAP_INSTRUMENTS (16), CAP_VERIFY_DIGEST (true). Shares the machine with the broker, the database and the
 * load generator, as the stand-in measurements did. S4_RP_DOCKER / S4_PG_DOCKER add `docker run` options (for example a tmpfs data directory).
 */
@Tag("broker") @Tag("research")
class CapacityLaneThroughputTest {
    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    @Test fun projectedDecisionsPerSecondOnTheProductPath() {
        val lanes = env("CAP_LANES", "1").toInt(); val trades = env("CAP_TRADES", "100000").toInt(); val accountCount = env("CAP_ACCOUNTS", "200").toInt()
        val instrumentCount = env("CAP_INSTRUMENTS", "16").toInt(); val verifyDigest = env("CAP_VERIFY_DIGEST", "true").toBoolean()
        val instruments = (0 until instrumentCount).map { "INS$it" }
        val plan = CapacityPlan("rate-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), (0 until lanes).map { l -> instruments.filterIndexed { i, _ -> i % lanes == l }.toSet() },
            trades + 10, trades + 10, 2 * trades + 10, accountCount * (lanes + instrumentCount) + 10, sequenceBound = 1_000_000_000L)
        val accounts = (0 until accountCount).map { AccountId("u$it") }
        val topics = CapacityTopics("RATE_CAPTURE", "RATE_CONTROL", "RATE_DECISIONS")
        val captureBinding = FiniteLifecycleFixtures.binding().digest

        // Pre-generate: one genuine envelope, edited per trade (execution id, instrument, accounts, content digest).
        val base = FiniteLifecycleFixtures.sequence().completedRecordsList.single { it.source.sourceOffset == 2L }
        val ready = base.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val keep = base.membersList.withIndex().filter { (i, m) -> m.disposition.name != "LIFECYCLE_READY_EXECUTION" || i == ready }.map { it.value }
        val one = base.toBuilder().clearMembers().addAllMembers(keep).setMemberCount(keep.size).build()
        val r = one.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val rnd = Random(3)
        val schedule = ArrayList<Pair<Int, ByteArray>>(trades)
        for (i in 0 until trades) {
            val ins = instruments[rnd.nextInt(instrumentCount)]; val b = accounts[rnd.nextInt(accountCount)]; var s = accounts[rnd.nextInt(accountCount)]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accountCount]
            val edited = one.toBuilder().also { bld ->
                val m = bld.getMembersBuilder(r)
                m.tradeBuilder.factBuilder.setExecutionId("exec-$i").setInstrumentId(ins).quantityBuilder.setUnits((1 + rnd.nextInt(4)).toString())
                m.tradeBuilder.factBuilder.priceBuilder.setNanos((1_000 + rnd.nextInt(50)).toString())
                m.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId(b.value); m.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setAccountId(s.value)
            }.build()
            schedule += plan.laneOf(ins)!! to edited.toBuilder().setContentDigest(FiniteLifecycleContract.digest(edited)).build().toByteArray()
        }

        val pgName = "cap-rate-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            if (env("CAP_PG_NOFK", "false").toBoolean()) sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = """
                DO ${'$'}${'$'} DECLARE r record; BEGIN
                  FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint WHERE connamespace = 'boundary'::regnamespace AND contype = 'f' AND confrelid = 'boundary.calcify_finance_checkpoint'::regclass LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname);
                  END LOOP;
                END ${'$'}${'$'};""")
            if (env("CAP_PG_SYNC_OFF", "false").toBoolean()) sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "ALTER DATABASE reef SET synchronous_commit = off;")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "cap"; reWriteBatchedInserts = true }
            DisposableRedpanda(smp = env("S4_SMP", "1").toInt(), memory = env("S4_BROKER_MEM", "2G")).start().use { rp ->
                rp.admin().use { admin -> admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, lanes, 1.toShort()).configs(mapOf("retention.ms" to "-1", "retention.bytes" to "-1")) }).all().get(30, TimeUnit.SECONDS) }
                val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
                val stateDir = Files.createTempDirectory("capacity-rate-state").toFile()
                val launcher = CapacityLauncher(plan, CapacityInputDecoders(plan, captureBinding, verifyDigest), topics, "capacity-rate", client, client, stateDir.path, ds, maxApply = env("CAP_MAX_APPLY", "2000").toInt()).start()
                val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                    put(ProducerConfig.LINGER_MS_CONFIG, 5); put(ProducerConfig.BATCH_SIZE_CONFIG, 131_072)
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
                    val expected = funds + 2L * trades
                    val mx = java.lang.management.ManagementFactory.getThreadMXBean()
                    fun cpuByGroup(): Map<String, Long> = Thread.getAllStackTraces().keys.groupBy { th -> when { th.name.startsWith("capacity-projection") -> "projection"; th.name.contains("StreamThread") -> "streams"; th.name.contains("kafka-producer") || th.name.contains("kafka-coordinator") -> "kafka-clients"; else -> "other" } }
                        .mapValues { (_, ths) -> ths.sumOf { th -> mx.getThreadCpuTime(th.id).coerceAtLeast(0) } }
                    val cpu0 = cpuByGroup()
                    val t0 = System.nanoTime()
                    for ((lane, bytes) in schedule) producer.send(ProducerRecord(topics.capture, lane, ByteArray(0), bytes))
                    producer.flush(); val tSent = System.nanoTime()
                    var last = 0L; var lastChange = System.nanoTime(); var tProcessed = 0L
                    val admin = rp.admin()
                    fun consumed(): Long = admin.listConsumerGroupOffsets("capacity-rate").partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS).filterKeys { it.topic() == topics.capture }.values.sumOf { it.offset() }
                    while (true) {
                        if (tProcessed == 0L && consumed() >= trades) tProcessed = System.nanoTime()
                        check(launcher.healthy()) { "service unhealthy: stream=${launcher.streamsState()} projection=${launcher.projectionFailure()}" }
                        val p = projected(); if (p >= expected) break
                        if (p != last) { last = p; lastChange = System.nanoTime() }
                        check(System.nanoTime() - lastChange < Duration.ofMinutes(3).toNanos()) { "projection stalled at $p of $expected" }
                        Thread.sleep(100)
                    }
                    val tDone = System.nanoTime()
                    val decisions = 2L * trades; val seconds = (tDone - t0) / 1e9
                    admin.close()
                    val cpu1 = cpuByGroup()
                    println("CAPACITY-CPU cpu_seconds " + listOf("projection", "streams", "kafka-clients").joinToString(" ") { g -> "$g=${"%.1f".format(((cpu1[g] ?: 0) - (cpu0[g] ?: 0)) / 1e9)}" } + " read_s=${"%.1f".format(launcher.readNanos.get() / 1e9)} apply_s=${"%.1f".format(launcher.applyNanos() / 1e9)} wall=${"%.1f".format((tDone - t0) / 1e9)} cores=${Runtime.getRuntime().availableProcessors()}")
                    println("CAPACITY-RATE lanes=$lanes trades=$trades decisions=$decisions processed_in=${"%.1f".format((tProcessed - t0) / 1e9)}s processed_decisions_per_s=${"%.0f".format(decisions / ((tProcessed - t0) / 1e9))} sent_in=${"%.1f".format((tSent - t0) / 1e9)}s projected_in=${"%.1f".format(seconds)}s projected_decisions_per_s=${"%.0f".format(decisions / seconds)} verify_digest=$verifyDigest")
                    assertEquals(expected, projected())
                } finally { launcher.close(); producer.close() }
            }
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }
}
