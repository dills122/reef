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
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1

/**
 * The capacity path end to end on a real broker and a real Postgres (design stage S4c): three lanes over six instruments and twelve participants, operator funding,
 * trades through the real decoders, the Streams app, the per-lane decision logs and the per-lane projections; then a clean restart and a restart with the local state
 * wiped (restored from the changelog). Every lane's decision log and projection must equal an independent replay of the same inputs through [CapacityLedger].
 * Correctness only: no rate claim. Needs Docker (Redpanda, Postgres 16).
 */
@Tag("broker")
class CapacityEndToEndTest {
    private val lanes = 3
    private val instruments = (0 until 6).map { "INS$it" }
    private val plan = CapacityPlan("e2e-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), instruments.chunked(2).map { it.toSet() }, 10_000, 10_000, 20_000, 1_000, sequenceBound = 100_000)
    private val participants = (0 until 12).map { AccountId("u$it") }
    private val captureBinding = FiniteLifecycleFixtures.binding().digest
    private val appId = "capacity-e2e"
    private val topics = CapacityTopics("E2E_CAPTURE", "E2E_CONTROL", "E2E_DECISIONS")

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    private fun envelope(i: Int, instrument: String, buyer: AccountId, seller: AccountId, units: Long, nanos: Long): ByteArray {
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

    private fun producer(bootstrap: String) = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
    })

    private fun decisions(bootstrap: String, lane: Int): List<Decision> = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    }).use { c ->
        val tp = TopicPartition(topics.decisions, lane); c.assign(listOf(tp)); c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp)).getValue(tp); val out = ArrayList<Decision>(); val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (c.position(tp) < end) { c.poll(Duration.ofMillis(200)).forEach { out += FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.value())).first }; check(System.nanoTime() < deadline) { "timed out reading lane $lane" } }
        out
    }

    private fun waitFor(what: String, seconds: Long = 120, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(250) }
    }

    @Test fun threeLanesTradeFundedParticipantsAndSurviveRestartsWithEveryLogAndProjectionEqualToTheOracle() {
        val pgName = "cap-e2e-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "cap" }
            DisposableRedpanda().start().use { rp -> run(rp, ds) }
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }

    private fun run(rp: DisposableRedpanda, ds: PGSimpleDataSource) {
        rp.admin().use { admin ->
            val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
            admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, lanes, 1.toShort()).configs(history) }).all().get(30, TimeUnit.SECONDS)
        }
        val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
        val stateDir = Files.createTempDirectory("capacity-e2e-state").toFile()
        fun launcher() = CapacityLauncher(plan, captureBinding, topics, appId, client, client, stateDir.path, ds)
        // The oracle: one independent ledger per lane, fed the inputs the lane must have seen, in order.
        val oracle = (0 until lanes).map { CapacityLedger(plan.capacity(it)) }
        val expected = (0 until lanes).map { ArrayList<Decision>() }
        fun feed(lane: Int, input: FinanceInput) { oracle[lane].decide(input).decision?.let { expected[lane] += it } }
        val rnd = Random(7); var n = 0
        val producer = producer(rp.bootstrap)
        fun trades(count: Int) {
            repeat(count) {
                val ins = instruments[rnd.nextInt(instruments.size)]; val lane = plan.laneOf(ins)!!
                val b = participants[rnd.nextInt(participants.size)]; var s = participants[rnd.nextInt(participants.size)]; if (s == b) s = participants[(participants.indexOf(b) + 1) % participants.size]
                val units = 1L + rnd.nextInt(4); val nanos = 1_000L + rnd.nextInt(50)
                val i = n++
                producer.send(ProducerRecord(topics.capture, lane, ByteArray(0), envelope(i, ins, b, s, units, nanos))).get(15, TimeUnit.SECONDS)
                val key = ExecutionKey("p3-run", "p3-session", ins, "exec-$i")
                feed(lane, FinanceInput.Capture(ActionKey("calcify-finance", "capture", "exec-$i"), key, b, s, units, nanos)); feed(lane, CapacityCaptureMapping.firstSettle(key))
            }
        }
        fun total() = expected.sumOf { it.size }
        fun converged(): Boolean = (0 until lanes).all { lane ->
            val log = decisions(rp.bootstrap, lane)
            log.size == expected[lane].size && ds.connection.use { c -> c.prepareStatement("SELECT last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> r.next() && r.getLong(1) == log.size.toLong() } } }
        }
        fun verify() {
            for (lane in 0 until lanes) {
                assertEquals(expected[lane].map { it.checksum }, decisions(rp.bootstrap, lane).map { it.checksum }, "lane $lane decision log equals the oracle")
                assertEquals(oracle[lane].chain to expected[lane].size.toLong(), ds.connection.use { c -> c.prepareStatement("SELECT last_chain, last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> r.next(); r.getString(1) to r.getLong(2) } } }, "lane $lane projection")
                val positions = ds.connection.use { c -> c.prepareStatement("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> val m = HashMap<Pair<String, String>, Long>(); while (r.next()) m[r.getString(1) to r.getString(2)] = r.getLong(3); m } } }
                assertEquals(oracle[lane].snapshot().positions.filterValues { it != 0L }.mapKeys { it.key.first.value to it.key.second.value }, positions.filterValues { it != 0L }, "lane $lane positions")
                val obligations = ds.connection.use { c -> c.prepareStatement("SELECT execution_id, status, cash_residual, share_residual FROM boundary.calcify_finance_obligation WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> val m = HashMap<String, Triple<String, Long, Long>>(); while (r.next()) m[r.getString(1)] = Triple(r.getString(2), r.getLong(3), r.getLong(4)); m } } }
                val wanted = oracle[lane].snapshot().obligations.entries.associate { (k, o) -> k.executionId to Triple(o.status.name, o.cashResidual, o.shareResidual) }
                assertEquals(wanted, obligations, "lane $lane obligations (inserted settled when settled in the same batch, updated otherwise)")
                assertEquals(oracle[lane].commitment(), StateCommitment.reference(oracle[lane].snapshot()), "lane $lane oracle commitment")
            }
        }

        var running = launcher().start()
        try {
            // Fund every participant with cash in every lane and shares of every instrument (control inputs, per lane).
            CapacityControlWriter(client, topics.control, plan).use { writer ->
                for (p in participants) {
                    writer.fundCash("seed-${p.value}", p, List(lanes) { 1_000_000_000L })
                    for (lane in 0 until lanes) feed(lane, FinanceInput.Fund(ActionKey("operator", "fund", "seed-${p.value}/cash/$lane"), Authority.OPERATOR, p, plan.cashAsset, 1_000_000_000L))
                    for (ins in instruments) { writer.fundSecurity("seed-${p.value}", p, ins, 100_000L); feed(plan.laneOf(ins)!!, FinanceInput.Fund(ActionKey("operator", "fund", "seed-${p.value}/$ins"), Authority.OPERATOR, p, AssetId(ins), 100_000L)) }
                }
                // A repeated control is absorbed: same key and bytes is PRIOR_RESULT, not recorded.
                writer.fundCash("seed-${participants[0].value}", participants[0], List(lanes) { 1_000_000_000L })
            }
            waitFor("funding decided") { (0 until lanes).sumOf { decisions(rp.bootstrap, it).size } == total() }

            // 1. Trades flow; every lane equals its oracle.
            trades(240); waitFor("first batch converged") { running.healthy() && converged() }; assertTrue(running.healthy()); verify()
            assertTrue(expected.all { it.size > 80 }, "every lane did real work: ${expected.map { it.size }}")
            assertTrue(expected.flatten().count { it.disposition == Disposition.SETTLED } > 200, "most trades settled")

            // 2. Clean restart: the local state and the projection are intact; the next trades continue each lane.
            running.close(); running = launcher().start()
            trades(120); waitFor("second batch converged") { running.healthy() && converged() }; verify()

            // 3. Restart with the local state wiped: each lane restores from its changelog partition and must be exactly at its published head.
            running.close(); stateDir.deleteRecursively(); stateDir.mkdirs(); running = launcher().start()
            trades(120); waitFor("third batch converged") { running.healthy() && converged() }; verify()
            assertTrue(running.healthy())
        } finally { running.close(); producer.close() }
    }
}
