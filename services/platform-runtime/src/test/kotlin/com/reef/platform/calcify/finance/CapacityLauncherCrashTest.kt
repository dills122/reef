package com.reef.platform.calcify.finance

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

internal object CapacityCrashFixture {
    const val LANES = 2
    val plan = CapacityPlan("crash-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), CapacityRouting.engineLanes("p3-run", "p3-session", (0 until 8).map { "INS$it" }, LANES), 100_000, 100_000, 200_000, 1_000, sequenceBound = 1_000_000)
    val topics = CapacityTopics("CRASH_CAPTURE", "CRASH_CONTROL", "CRASH_DECISIONS")
    const val APP_ID = "capacity-crash"
    val participants = (0 until 10).map { AccountId("u$it") }

    fun resolvedContext(i: Int, instrument: String, buyer: AccountId, seller: AccountId, units: Long, nanos: Long): ByteArray {
        val b = reef.contracts.calcify.v1.MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("p3-run")
        b.tradeBuilder.setRunId("p3-run").factBuilder.setExecutionId("exec-$i").setInstrumentId(instrument).setBuyOrderId("b-$i").setSellOrderId("s-$i").quantityBuilder.setUnits(units.toString())
        b.tradeBuilder.factBuilder.priceBuilder.setNanos(nanos.toString()).setCurrency("USD")
        b.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$i").setVenueSessionId("p3-session").setInstrumentId(instrument).setAccountId(buyer.value).setParticipantId("pb-${buyer.value}")
        b.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$i").setVenueSessionId("p3-session").setInstrumentId(instrument).setAccountId(seller.value).setParticipantId("ps-${seller.value}")
        return b.build().toByteArray()
    }
}

/** Child-JVM entry point: the real [CapacityLauncher] (log heads, restore, projection, Streams app) until it is SIGKILLed. Args: bootstrap, state directory, JDBC url, checkpointEvery. */
internal object CapacityLauncherChild {
    @JvmStatic fun main(args: Array<String>) {
        val (bootstrap, stateDir, url, checkpointEvery) = args.toList()
        val ds = PGSimpleDataSource().apply { setUrl(url); user = "postgres"; password = "cap" }
        val client = Properties().apply { put("bootstrap.servers", bootstrap); put("consumer.session.timeout.ms", 6000); put("consumer.heartbeat.interval.ms", 1000) }
        val running = CapacityLauncher(CapacityCrashFixture.plan, CapacityResolvedContextDecoder(CapacityCrashFixture.plan), CapacityCrashFixture.topics, CapacityCrashFixture.APP_ID, client, client, stateDir, ds, checkpointEvery = checkpointEvery.toInt()).start()
        println("CHILD-STARTED")
        while (running.healthy()) Thread.sleep(100)
        System.err.println("CHILD-UNHEALTHY streams=${running.streamsState()} projection=${running.projectionFailure()}")
        Runtime.getRuntime().halt(3)
    }
}
private operator fun <T> List<T>.component4() = this[3]

/**
 * The capacity launcher under SIGKILL with data flowing (real Redpanda, real Postgres, a child JVM), with head and meta written only every 25 decisions: three SIGKILLs land
 * while trades are being decided, the second restart wipes the local RocksDB (a changelog restore after a crash). Every restart must pass the lane's own checks (the store
 * must be exactly the head of the published decision log, [CapacityStore.restore] with the log head), and at the end every lane's broker decision log, the Postgres
 * projection and an independent replay of the same inputs must agree exactly: no lost, duplicated or reordered decision. Needs Docker.
 */
@Tag("broker")
class CapacityLauncherCrashTest {
    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    private fun decisions(bootstrap: String, lane: Int): List<Decision> = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    }).use { c ->
        val tp = TopicPartition(CapacityCrashFixture.topics.decisions, lane); c.assign(listOf(tp)); c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp)).getValue(tp); val out = ArrayList<Decision>(); val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (c.position(tp) < end) { c.poll(Duration.ofMillis(200)).forEach { out += FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.value())).first }; check(System.nanoTime() < deadline) { "timed out reading lane $lane" } }
        out
    }

    private fun waitUntil(what: String, seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(200) }
    }

    @Test fun killedCapacityLauncherWithCheckpointsEvery25RestoresFromTheLogHeadAndNeverDiverges() {
        val plan = CapacityCrashFixture.plan; val topics = CapacityCrashFixture.topics; val lanes = CapacityCrashFixture.LANES; val participants = CapacityCrashFixture.participants
        val instruments = plan.laneInstruments.flatten().sorted()
        val pgName = "cap-crash-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", "postgres:16-alpine")
        try {
            waitUntil("postgres", 60) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); true } catch (e: Exception) { false } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql", "0017_calcify_finance_decision_digest_checks.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            val url = "jdbc:postgresql://127.0.0.1:$pgPort/reef"
            DisposableRedpanda().start().use { rp ->
                rp.admin().use { admin -> admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, lanes, 1.toShort()).configs(mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")) }).all().get(30, TimeUnit.SECONDS) }
                val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
                val stateDir = Files.createTempDirectory("capacity-crash-state").toFile()
                val logs = Files.createTempDirectory("capacity-crash-logs").toFile()
                val java = File(System.getProperty("java.home"), "bin/java").path
                var spawned = 0
                fun child(): Pair<Process, File> {
                    val log = File(logs, "child-${spawned++}.log")
                    return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.reef.platform.calcify.finance.CapacityLauncherChild", rp.bootstrap, stateDir.path, url, "25")
                        .redirectErrorStream(true).redirectOutput(log).start() to log
                }
                fun awaitStarted(p: Pair<Process, File>) = waitUntil("child reached CHILD-STARTED", 150) {
                    check(p.first.isAlive || p.second.readText().contains("CHILD-STARTED")) { "child exited before starting: ${p.second.readText().takeLast(2000)}" }
                    p.second.readText().contains("CHILD-STARTED")
                }
                val oracle = (0 until lanes).map { CapacityLedger(plan.capacity(it)) }
                fun feed(lane: Int, input: FinanceInput) { oracle[lane].decide(input) }
                fun total() = (0 until lanes).sumOf { decisions(rp.bootstrap, it).size }

                // Funding first, through the real control writer; the lanes must have decided all of it before any trade is sent so the per-lane order is deterministic.
                var funds = 0
                CapacityControlWriter(client, topics.control, plan).use { writer ->
                    for (p in participants) {
                        writer.fundCash("seed-${p.value}", p, List(lanes) { 1_000_000_000L })
                        for (lane in 0 until lanes) { feed(lane, FinanceInput.Fund(ActionKey("operator", "fund", "seed-${p.value}/cash/$lane"), Authority.OPERATOR, p, plan.cashAsset, 1_000_000_000L)); funds++ }
                        for (ins in instruments) { writer.fundSecurity("seed-${p.value}", p, ins, 100_000L); feed(plan.laneOf(ins)!!, FinanceInput.Fund(ActionKey("operator", "fund", "seed-${p.value}/$ins"), Authority.OPERATOR, p, AssetId(ins), 100_000L)); funds++ }
                    }
                }
                val trades = 12_000
                val random = Random(20261015)
                val schedule = (0 until trades).map {
                    val ins = instruments[random.nextInt(instruments.size)]; val b = participants[random.nextInt(participants.size)]; var s = participants[random.nextInt(participants.size)]; if (s == b) s = participants[(participants.indexOf(b) + 1) % participants.size]
                    val units = 1L + random.nextInt(4); val nanos = 1_000L + random.nextInt(50)
                    Triple(it, ins, Triple(b, s, units to nanos))
                }
                for ((i, ins, rest) in schedule) {
                    val (b, s, un) = rest; val lane = plan.laneOf(ins)!!; val key = ExecutionKey("p3-run", "p3-session", ins, "exec-$i")
                    feed(lane, FinanceInput.Capture(ActionKey("calcify-finance", "capture", "exec-$i"), key, b, s, un.first, un.second)); feed(lane, CapacityCaptureMapping.firstSettle(key))
                }

                val first = child(); awaitStarted(first)
                waitUntil("funding decided", 150) { total() >= funds }
                val feeder = Thread {
                    val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                    })
                    try { for ((i, ins, rest) in schedule) { val (b, s, un) = rest; producer.send(ProducerRecord(topics.capture, plan.laneOf(ins)!!, ByteArray(0), CapacityCrashFixture.resolvedContext(i, ins, b, s, un.first, un.second))); if (i % 25 == 24) Thread.sleep(100) }; producer.flush() } finally { producer.close() }
                }
                var feederFailure: Throwable? = null
                feeder.setUncaughtExceptionHandler { _, e -> feederFailure = e }
                feeder.start()

                var killsAfterProgress = 0
                var current = first
                for (round in 0 until 3) {
                    if (round > 0) { current = child(); awaitStarted(current) }
                    val base = total(); val target = base + 20 + random.nextInt(60)
                    waitUntil("progress before kill $round", 150) { total() >= target || !feeder.isAlive }
                    Thread.sleep(random.nextInt(150).toLong())
                    if (total() > base) killsAfterProgress++
                    current.first.destroyForcibly().waitFor()                   // SIGKILL: no shutdown hook, no close(), no final commit
                    println("CAPACITY-CRASH-KILL round=$round decisionsBefore=$base after=${total()} wipeNext=${round == 1}")
                    if (round == 1) stateDir.listFiles()?.forEach { it.deleteRecursively() }     // the next start restores RocksDB from the changelog
                }
                assertTrue(killsAfterProgress >= 2, "at least two kills landed while decisions were flowing (got $killsAfterProgress)")
                val last = child(); awaitStarted(last)
                try {
                    feeder.join(240_000)
                    assertTrue(!feeder.isAlive, "the feeder finished"); assertEquals(null, feederFailure, "feeder: $feederFailure")
                    val expected = funds + 2 * trades
                    waitUntil("all $expected decisions decided", 240) { check(last.first.isAlive) { "final child died: ${last.second.readText().takeLast(2000)}" }; total() >= expected }
                    waitUntil("projection caught up", 240) {
                        PGSimpleDataSource().apply { setUrl(url); user = "postgres"; password = "cap" }.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COALESCE(SUM(last_sequence), 0) FROM boundary.calcify_finance_checkpoint").use { rs -> rs.next(); rs.getLong(1) } } } >= expected
                    }
                    assertTrue(last.first.isAlive, "the final launcher is still healthy: ${last.second.readText().takeLast(1500)}")
                } finally { last.first.destroyForcibly().waitFor() }

                for (lane in 0 until lanes) {
                    val log = decisions(rp.bootstrap, lane)
                    assertEquals((1L..log.size).toList(), log.map { it.sequence }, "lane $lane: contiguous sequences, no duplicate or lost decision")
                    // The oracle decided each lane's inputs in order, independently of the launcher.
                    assertEquals(oracle[lane].sequence, log.size.toLong(), "lane $lane decided exactly the oracle's decisions")
                    assertEquals(oracle[lane].chain, log.last().checksum, "lane $lane: the decision chain head equals the independent replay")
                    assertEquals(oracle[lane].commitment(), log.last().stateDigest, "lane $lane: the committed state equals the replay's")
                }
                println("CAPACITY-CRASH OK kills=3 afterProgress=$killsAfterProgress children=$spawned trades=$trades checkpointEvery=25")
            }
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }
}
