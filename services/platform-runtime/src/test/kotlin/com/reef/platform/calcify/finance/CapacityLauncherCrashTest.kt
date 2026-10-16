package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.random.Random
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

/** The capacity plan, topics and capture records of [CapacityLauncherCrashTest], shared with the child JVM it kills. */
internal object CapacityCrashFixture {
    const val LANES = 2
    const val APP_ID = "capacity-crash"
    val topics = CapacityTopics("CRASH_CAPTURE", "CRASH_CONTROL", "CRASH_DECISIONS")
    val captureBinding: String = FiniteLifecycleFixtures.binding().digest
    val plan = CapacityPlan("crash-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), (0 until 4).map { "INS$it" }.chunked(2).map { it.toSet() },
        100_000, 100_000, 100_000, 1_000, sequenceBound = 1_000_000, decisionMode = DecisionMode.CAPTURE_SETTLE)

    /** A lifecycle capture record carrying one ready execution, as the Phase 2 capture would publish it. */
    fun envelope(i: Int, instrument: String, buyer: AccountId, seller: AccountId, units: Long, nanos: Long): ByteArray {
        val base = FiniteLifecycleFixtures.sequence().completedRecordsList.single { it.source.sourceOffset == 2L }
        val ready = base.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val keep = base.membersList.withIndex().filter { (idx, m) -> m.disposition.name != "LIFECYCLE_READY_EXECUTION" || idx == ready }.map { it.value }
        val one = base.toBuilder().clearMembers().addAllMembers(keep).setMemberCount(keep.size).build()
        val r = one.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val edited = one.toBuilder().also { b ->
            val m = b.getMembersBuilder(r)
            m.tradeBuilder.factBuilder.setExecutionId("exec-$i").setInstrumentId(instrument).quantityBuilder.setUnits(units.toString())
            m.tradeBuilder.factBuilder.priceBuilder.setNanos(nanos.toString())
            m.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId(buyer.value)
            m.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setAccountId(seller.value)
        }.build()
        return edited.toBuilder().setContentDigest(FiniteLifecycleContract.digest(edited)).build().toByteArray()
    }
}

/**
 * Child-JVM entry point: the real [CapacityLauncher] (evicting lanes, the default) with its per-lane projections, until it is SIGKILLed, or until SIGTERM, which closes it
 * cleanly through a shutdown hook (Streams closes every task, so each lane writes its snapshot).
 */
internal object CapacityLauncherChild {
    @JvmStatic fun main(args: Array<String>) {
        val (bootstrap, stateDir, url, password) = args.toList()
        val ds = PGSimpleDataSource().apply { setUrl(url); user = "postgres"; this.password = password }
        val client = Properties().apply { put("bootstrap.servers", bootstrap) }
        val streams = Properties().apply { put("bootstrap.servers", bootstrap); put("consumer.session.timeout.ms", 6000); put("consumer.heartbeat.interval.ms", 1000) }
        val plan = CapacityCrashFixture.plan
        val running = CapacityLauncher(plan, CapacityInputDecoders(plan, CapacityCrashFixture.captureBinding), CapacityCrashFixture.topics, CapacityCrashFixture.APP_ID, client, streams, stateDir, ds).start()
        Runtime.getRuntime().addShutdownHook(Thread { running.close(); System.err.println("CHILD-CLOSED") })
        println("CHILD-STARTED")
        while (true) {
            if (!running.isClosing() && !running.healthy()) { System.err.println("CHILD-UNHEALTHY streams=${running.streamsState()} projection=${running.projectionFailure()}"); Runtime.getRuntime().halt(3) }
            Thread.sleep(100)
        }
    }
}

/**
 * The capacity launcher with eviction on (combined decision mode, two lanes) under SIGKILL while trades flow, on a real Redpanda and a real Postgres. Four stops land while
 * decisions are being made: a SIGKILL; a SIGKILL followed by wiping the local state (the next start restores every lane's store from its changelog); a SIGTERM (a clean close:
 * every lane writes its snapshot, and the next start must restart every lane from it); and a SIGKILL of that snapshot-restored child. One trade in seven leaves a pending
 * obligation (a buyer without cash), so the evicted lanes hold real open work across every restart; at the end the buyer is funded and every pending trade repaired.
 * Each lane's decision log (contiguous sequences, the chain) and its projection (checkpoint, positions, obligations) must equal an independent replay of the same inputs:
 * no lost, duplicated or reordered decision. Needs Docker (Redpanda, Postgres 16).
 */
@Tag("broker")
class CapacityLauncherCrashTest {
    private val f = CapacityCrashFixture
    private val plan = f.plan
    private val participants = (0 until 8).map { AccountId("u$it") }
    private val poor = AccountId("poor")
    private val snapshotName = "calcify-capacity-finance.lane-snapshot"

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    private fun consumer(bootstrap: String, isolation: String) = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    })

    private fun decisions(bootstrap: String, lane: Int): List<Decision> = consumer(bootstrap, "read_committed").use { c ->
        val tp = TopicPartition(f.topics.decisions, lane); c.assign(listOf(tp)); c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp)).getValue(tp); val out = ArrayList<Decision>(); val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (c.position(tp) < end) { c.poll(Duration.ofMillis(200)).forEach { out += FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.value())).first }; check(System.nanoTime() < deadline) { "timed out reading lane $lane" } }
        out
    }
    private fun decided(bootstrap: String) = (0 until f.LANES).sumOf { decisions(bootstrap, it).size }

    /** True when some decision partition has records past its last stable offset: a transaction was open when the process died. */
    private fun openTransaction(bootstrap: String): Boolean {
        val tps = (0 until f.LANES).map { TopicPartition(f.topics.decisions, it) }
        val all = consumer(bootstrap, "read_uncommitted").use { it.endOffsets(tps) }; val stable = consumer(bootstrap, "read_committed").use { it.endOffsets(tps) }
        return tps.any { all.getValue(it) > stable.getValue(it) }
    }

    private fun waitFor(what: String, seconds: Long = 150, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(250) }
    }

    @Test fun killedEvictingLanesRestartFromTheirChangelogOrTheirSnapshotAndEveryLogAndProjectionEqualsAnIndependentReplay() {
        val pgName = "cap-crash-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql", "0017_calcify_finance_decision_digest_checks.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            val url = "jdbc:postgresql://127.0.0.1:$pgPort/reef"
            val ds = PGSimpleDataSource().apply { setUrl(url); user = "postgres"; password = "cap" }
            DisposableRedpanda().start().use { rp -> crashAndVerify(rp, url, ds) }
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }

    private fun crashAndVerify(rp: DisposableRedpanda, url: String, ds: PGSimpleDataSource) {
        rp.admin().use { admin ->
            val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
            admin.createTopics(listOf(f.topics.capture, f.topics.control, f.topics.decisions).map { NewTopic(it, f.LANES, 1.toShort()).configs(history) }).all().get(30, TimeUnit.SECONDS)
        }
        val stateDir = Files.createTempDirectory("capacity-crash-state").toFile(); val logs = Files.createTempDirectory("capacity-crash-logs").toFile()
        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        var spawned = 0
        fun child(): Pair<Process, File> {
            val log = File(logs, "child-${spawned++}.log")
            return ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"), "com.reef.platform.calcify.finance.CapacityLauncherChild", rp.bootstrap, stateDir.path, url, "cap")
                .redirectErrorStream(true).redirectOutput(log).start() to log
        }
        fun awaitStarted(p: Pair<Process, File>) = waitFor("child started") {
            check(p.first.isAlive || p.second.readText().contains("CHILD-STARTED")) { "child exited before starting: ${p.second.readText().takeLast(3000)}" }
            p.second.readText().contains("CHILD-STARTED")
        }
        fun snapshots() = stateDir.walkTopDown().filter { it.name == snapshotName }.toList()
        fun lanesRestoredFromSnapshot(log: File) = Regex("calcify capacity lane (\\d+): restored from the lane snapshot").findAll(log.readText()).map { it.groupValues[1].toInt() }.toSet()

        // The oracle: one plain ledger per lane, fed exactly the inputs the lane sees, in its partition order.
        val oracle = (0 until f.LANES).map { CapacityLedger(plan.capacity(it)) }
        val expected = (0 until f.LANES).map { ArrayList<Decision>() }
        fun feed(lane: Int, input: FinanceInput) { synchronized(oracle) { oracle[lane].decide(input).decision?.let { expected[lane] += it } } }
        fun total() = synchronized(oracle) { expected.sumOf { it.size } }
        val instruments = plan.laneInstruments.flatten().sorted()
        val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }

        var c = child(); awaitStarted(c)
        // Funding: every participant has cash in every lane and shares of every instrument; the poor buyer has shares only.
        CapacityControlWriter(client, f.topics.control, plan).use { w ->
            fun fund(lane: Int, input: FinanceInput.Fund) { w.submit(lane, input); feed(lane, input) }
            for (p in participants) {
                for (lane in 0 until f.LANES) fund(lane, FinanceInput.Fund(ActionKey("operator", "fund", "seed-${p.value}/cash/$lane"), Authority.OPERATOR, p, plan.cashAsset, 1_000_000_000L))
                for (ins in instruments) fund(plan.laneOf(ins)!!, FinanceInput.Fund(ActionKey("operator", "fund", "seed-${p.value}/$ins"), Authority.OPERATOR, p, AssetId(ins), 1_000_000L))
            }
            for (ins in instruments) fund(plan.laneOf(ins)!!, FinanceInput.Fund(ActionKey("operator", "fund", "poor/$ins"), Authority.OPERATOR, poor, AssetId(ins), 1_000L))
        }
        waitFor("funding decided") { decided(rp.bootstrap) == total() }

        // Trades flow on their own thread for the whole kill schedule.
        val trades = 2_400; val pending: MutableList<Pair<Int, ExecutionKey>> = java.util.Collections.synchronizedList(ArrayList())
        val feeder = Thread {
            val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            })
            val rnd = Random(20261016)
            producer.use {
                for (i in 0 until trades) {
                    val ins = instruments[rnd.nextInt(instruments.size)]; val lane = plan.laneOf(ins)!!
                    val b = if (i % 7 == 3) poor else participants[rnd.nextInt(participants.size)]
                    var s = participants[rnd.nextInt(participants.size)]; if (s == b) s = participants[(participants.indexOf(s) + 1) % participants.size]
                    val units = 1L + rnd.nextInt(4); val nanos = 1_000L + rnd.nextInt(50)
                    producer.send(ProducerRecord(f.topics.capture, lane, ByteArray(0), f.envelope(i, ins, b, s, units, nanos))).get(15, TimeUnit.SECONDS)
                    val key = ExecutionKey("p3-run", "p3-session", ins, "exec-$i")
                    feed(lane, CapacityCaptureMapping.captureSettle(FinanceInput.Capture(ActionKey("calcify-finance", "capture", key.executionId), key, b, s, units, nanos)))
                    if (b == poor) pending.add(lane to key)
                    Thread.sleep(15)
                }
            }
        }
        var feederFailure: Throwable? = null
        feeder.setUncaughtExceptionHandler { _, e -> feederFailure = e }
        feeder.start()

        val random = Random(7); var killsWhileFlowing = 0; var killsInsideATransaction = 0
        for (round in 0 until 4) {
            if (round > 0) { c = child(); awaitStarted(c) }
            val base = decided(rp.bootstrap); val target = base + 20 + random.nextInt(60)
            waitFor("progress before stop $round") { check(c.first.isAlive) { "child died: ${c.second.readText().takeLast(3000)}" }; decided(rp.bootstrap) >= target || !feeder.isAlive }
            Thread.sleep(random.nextInt(150).toLong())
            val flowing = feeder.isAlive && decided(rp.bootstrap) > base
            if (round == 3) assertEquals((0 until f.LANES).toSet(), lanesRestoredFromSnapshot(c.second), "after the clean stop every lane restarted from its snapshot: ${c.second.readText().takeLast(3000)}")
            if (round == 2) {
                c.first.destroy(); assertTrue(c.first.waitFor(90, TimeUnit.SECONDS), "the clean stop finished"); assertTrue(c.second.readText().contains("CHILD-CLOSED"), c.second.readText().takeLast(3000))
                assertEquals(f.LANES, snapshots().size, "a clean close leaves one snapshot per lane: ${snapshots()}")
            } else {
                c.first.destroyForcibly().waitFor()          // SIGKILL: no shutdown hook, no close(), no snapshot, no final commit
                if (flowing) killsWhileFlowing++
                if (openTransaction(rp.bootstrap)) killsInsideATransaction++
            }
            println("CRASH-STOP round=$round kind=${if (round == 2) "SIGTERM" else "SIGKILL"} decisionsBefore=$base flowing=$flowing snapshotsLeft=${snapshots().size} wipeNext=${round == 1}")
            if (round == 1) stateDir.listFiles()?.forEach { it.deleteRecursively() }     // the next start restores every store from its changelog
        }
        assertTrue(killsWhileFlowing >= 2, "at least two SIGKILLs landed while trades were flowing (got $killsWhileFlowing)")

        c = child(); awaitStarted(c)
        try {
            feeder.join(300_000); assertTrue(!feeder.isAlive, "the feeder finished"); assertEquals(null, feederFailure, "feeder: $feederFailure")
            waitFor("every trade decided", seconds = 240) { check(c.first.isAlive) { "child died: ${c.second.readText().takeLast(3000)}" }; decided(rp.bootstrap) == total() }
            // Repairs: fund the poor buyer, then settle every pending trade with attempt 2; a repeated repair of a settled trade is refused (ALREADY_SETTLED, not recorded).
            assertTrue(pending.size > 100, "pending trades: ${pending.size}")
            CapacityControlWriter(client, f.topics.control, plan).use { w ->
                for (lane in 0 until f.LANES) FinanceInput.Fund(ActionKey("operator", "fund", "poor-cash/cash/$lane"), Authority.OPERATOR, poor, plan.cashAsset, 1_000_000_000L).let { w.submit(lane, it); feed(lane, it) }
                pending.forEachIndexed { i, (lane, key) -> FinanceInput.Settle(ActionKey("operator", "repair", "rp-$i"), Authority.OPERATOR, key, 2).let { w.submit(lane, it); feed(lane, it) } }
                pending.take(10).forEachIndexed { i, (lane, key) -> FinanceInput.Settle(ActionKey("operator", "repair", "again-$i"), Authority.OPERATOR, key, 2).let { w.submit(lane, it); feed(lane, it) } }
            }
            fun projected(lane: Int): Pair<String, Long>? = ds.connection.use { cn -> cn.prepareStatement("SELECT last_chain, last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getLong(2) else null } } }
            waitFor("everything decided and projected", seconds = 240) { check(c.first.isAlive) { "child died: ${c.second.readText().takeLast(3000)}" }; decided(rp.bootstrap) == total() && (0 until f.LANES).all { projected(it)?.second == expected[it].size.toLong() } }

            for (lane in 0 until f.LANES) {
                val log = decisions(rp.bootstrap, lane)
                assertEquals((1L..expected[lane].size).toList(), log.map { it.sequence }, "lane $lane: contiguous sequences, no duplicate or lost decision")
                assertEquals(expected[lane].map { it.checksum }, log.map { it.checksum }, "lane $lane: the decision chain equals an independent replay")
                assertEquals(expected[lane], log, "lane $lane: every decision equals the replay's")
                assertEquals(oracle[lane].chain to expected[lane].size.toLong(), projected(lane), "lane $lane projection checkpoint")
                val state = oracle[lane].snapshot()
                ds.connection.use { cn ->
                    val positions = cn.prepareStatement("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1) to r.getString(2), r.getLong(3)) } } }
                    assertEquals(state.positions.filterValues { it != 0L }.mapKeys { it.key.first.value to it.key.second.value }, positions.filterValues { it != 0L }, "lane $lane projected positions equal the replay")
                    val obligations = cn.prepareStatement("SELECT status, count(*) FROM boundary.calcify_finance_obligation WHERE binding_digest = ? GROUP BY status").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1), r.getInt(2)) } } }
                    assertEquals(mapOf("SETTLED" to state.obligations.size), obligations, "lane $lane: every obligation projected once and settled")
                }
                assertEquals(oracle[lane].commitment(), StateCommitment.reference(state), "lane $lane oracle commitment")
            }
            assertTrue(expected.flatten().count { it.disposition == Disposition.INSUFFICIENT_CASH } == pending.size, "each poor trade was one shortfall decision")
            println("CRASH OK children=$spawned killsWhileFlowing=$killsWhileFlowing killsInsideATransaction=$killsInsideATransaction decisions=${expected.map { it.size }} pending=${pending.size} logs=$logs")
        } finally { c.first.destroyForcibly().waitFor(); stateDir.deleteRecursively() }
    }
}
