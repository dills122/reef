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

/**
 * The capacity path end to end on a real broker and a real Postgres (design stage S4c): three lanes over six instruments and twelve participants, operator funding,
 * trades through the real decoders, the Streams app, the per-lane decision logs and the per-lane projections; then a clean restart and a restart with the local state
 * wiped (restored from the changelog). Every lane's decision log and projection must equal an independent replay of the same inputs through [CapacityLedger].
 * Correctness only: no rate claim. Needs Docker (Redpanda, Postgres 16).
 */
@Tag("broker")
class CapacityEndToEndTest {
    private val lanes = 3
    private fun planOver(laneSets: List<Set<String>>, mode: DecisionMode = DecisionMode.TWO_STEP) =
        CapacityPlan(if (mode == DecisionMode.TWO_STEP) "e2e-plan" else "e2e-plan-cs", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), laneSets, 10_000, 10_000, 20_000, 1_000, sequenceBound = 100_000, decisionMode = mode)
    private val participants = (0 until 12).map { AccountId("u$it") }
    private val captureBinding = FiniteLifecycleFixtures.binding().digest
    private val appId = "capacity-e2e"
    private val topics = CapacityTopics("E2E_CAPTURE", "E2E_CONTROL", "E2E_DECISIONS")

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    /** A Phase 2 resolved match context for one trade, as the resolver would publish it. */
    private fun resolvedContext(i: Int, instrument: String, buyer: AccountId, seller: AccountId, units: Long, nanos: Long): ByteArray {
        val b = reef.contracts.calcify.v1.MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("p3-run")
        b.tradeBuilder.setRunId("p3-run").factBuilder.setExecutionId("exec-$i").setInstrumentId(instrument).setBuyOrderId("b-$i").setSellOrderId("s-$i").quantityBuilder.setUnits(units.toString())
        b.tradeBuilder.factBuilder.priceBuilder.setNanos(nanos.toString()).setCurrency("USD")
        b.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$i").setVenueSessionId("p3-session").setInstrumentId(instrument).setAccountId(buyer.value).setParticipantId("pb-${buyer.value}")
        b.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$i").setVenueSessionId("p3-session").setInstrumentId(instrument).setAccountId(seller.value).setParticipantId("ps-${seller.value}")
        return b.build().toByteArray()
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
        val plan = planOver((0 until 6).map { "INS$it" }.chunked(2).map { it.toSet() })
        withInfrastructure { rp, ds -> run(rp, ds, plan, CapacityInputDecoders(plan, captureBinding), ::envelope) }
    }

    @Test fun resolvedMatchContextsOnEngineAlignedLanesGiveTheSameGuarantees() {
        val plan = planOver(CapacityRouting.engineLanes("p3-run", "p3-session", (0 until 12).map { "INS$it" }, lanes)).also { it.requireEngineAligned() }
        withInfrastructure { rp, ds -> run(rp, ds, plan, CapacityResolvedContextDecoder(plan), ::resolvedContext) }
    }

    @Test fun captureSettleModeOnLifecycleCapturesGivesOneDecisionPerTradeAndTheTwoStepTables() {
        val plan = planOver((0 until 6).map { "INS$it" }.chunked(2).map { it.toSet() }, DecisionMode.CAPTURE_SETTLE)
        withInfrastructure { rp, ds -> run(rp, ds, plan, CapacityInputDecoders(plan, captureBinding), ::envelope) }
    }

    @Test fun captureSettleModeOnResolvedMatchContextsWithShortfallsAndOperatorRepairsSurvivesRestartsAndWipedState() {
        val plan = planOver(CapacityRouting.engineLanes("p3-run", "p3-session", (0 until 12).map { "INS$it" }, lanes), DecisionMode.CAPTURE_SETTLE).also { it.requireEngineAligned() }
        withInfrastructure { rp, ds -> run(rp, ds, plan, CapacityResolvedContextDecoder(plan), ::resolvedContext, shortfalls = true) }
    }

    @Test fun bufferedControlsAreProcessedBeforeBufferedCapturesSoAnOperatorCanUnstickALane() {
        val plan = planOver(listOf(setOf("INS0")))
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin -> admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, 1, 1.toShort()).configs(mapOf("retention.ms" to "-1", "retention.bytes" to "-1")) }).all().get(30, TimeUnit.SECONDS) }
            val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
            val producer = producer(rp.bootstrap)
            // The trade is written first, with the oldest timestamp, and its accounts are funded only afterwards; both are buffered when the service starts.
            producer.send(ProducerRecord(topics.capture, 0, 0L, ByteArray(0), envelope(1, "INS0", AccountId("alice"), AccountId("bob"), 2, 10))).get(15, TimeUnit.SECONDS)
            CapacityControlWriter(client, topics.control, plan).use { w -> w.fundCash("f", AccountId("alice"), listOf(1_000L)); w.fundSecurity("f", AccountId("bob"), "INS0", 10L) }
            val stateDir = Files.createTempDirectory("capacity-e2e-state2").toFile()
            val withBadCapture = CapacityInputDecoders(plan, captureBinding)
            val dummyDs = PGSimpleDataSource()
            // Only the Streams side is under test here: run the processor topology directly (the launcher also needs a database).
            val config = CapacityProcessorConfig(plan, topics.capture, topics.control, topics.decisions, "capacity-priority", failOnBadCapture = true)
            val props = CapacityStreams.properties("capacity-priority", rp.bootstrap, stateDir.path, 1)
            val streams = org.apache.kafka.streams.KafkaStreams(CapacityTopology.build(config, withBadCapture), props)
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            streams.setUncaughtExceptionHandler { failed.set(true); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
            try {
                streams.start()
                waitFor("three decisions") { check(!failed.get()) { "the lane stopped: the trade was presented before its funding" }; decisions(rp.bootstrap, 0).size >= 4 }
                val dispositions = decisions(rp.bootstrap, 0).map { it.disposition }
                assertEquals(listOf(Disposition.FUNDED, Disposition.FUNDED, Disposition.CAPTURED, Disposition.SETTLED), dispositions, "funding first, then the trade, which settles")
            } finally { streams.close(Duration.ofSeconds(20)); producer.close() }
        }
    }

    private fun withInfrastructure(body: (DisposableRedpanda, PGSimpleDataSource) -> Unit) {
        val pgName = "cap-e2e-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", *dockerExtraArgs("S4_PG_DOCKER"), "postgres:16-alpine")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql", "0017_calcify_finance_decision_digest_checks.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "cap" }
            DisposableRedpanda().start().use { rp -> body(rp, ds) }
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }

    private fun run(rp: DisposableRedpanda, ds: PGSimpleDataSource, plan: CapacityPlan, decoder: CapacityInputDecoder, record: (Int, String, AccountId, AccountId, Long, Long) -> ByteArray, shortfalls: Boolean = false) {
        val combined = plan.decisionMode == DecisionMode.CAPTURE_SETTLE
        val instruments = plan.laneInstruments.flatten().sorted()
        rp.admin().use { admin ->
            val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
            admin.createTopics(listOf(topics.capture, topics.control, topics.decisions).map { NewTopic(it, lanes, 1.toShort()).configs(history) }).all().get(30, TimeUnit.SECONDS)
        }
        val client = Properties().apply { put("bootstrap.servers", rp.bootstrap) }
        val stateDir = Files.createTempDirectory("capacity-e2e-state").toFile()
        fun launcher() = CapacityLauncher(plan, decoder, topics, appId, client, client, stateDir.path, ds)
        // The oracle: one independent ledger per lane, fed the inputs the lane must have seen, in order.
        val oracle = (0 until lanes).map { CapacityLedger(plan.capacity(it)) }
        // In the combined mode the projection tables (positions, obligations, attempts) must be exactly the ones the two-step flow produces for the same trades: a second, two-step oracle.
        val shadow = if (combined) (0 until lanes).map { CapacityLedger(plan.capacity(it).copy(decisionMode = DecisionMode.TWO_STEP)) } else oracle
        val expected = (0 until lanes).map { ArrayList<Decision>() }
        fun feed(lane: Int, input: FinanceInput) { oracle[lane].decide(input).decision?.let { expected[lane] += it }; if (combined) shadow[lane].decide(input) }
        fun feedTrade(lane: Int, key: ExecutionKey, b: AccountId, s: AccountId, units: Long, nanos: Long) {
            val capture = FinanceInput.Capture(ActionKey("calcify-finance", "capture", key.executionId), key, b, s, units, nanos)
            if (!combined) { feed(lane, capture); feed(lane, CapacityCaptureMapping.firstSettle(key)); return }
            oracle[lane].decide(CapacityCaptureMapping.captureSettle(capture)).decision?.let { expected[lane] += it }
            shadow[lane].decide(capture); shadow[lane].decide(CapacityCaptureMapping.firstSettle(key))
        }
        val rnd = Random(7); var n = 0
        val producer = producer(rp.bootstrap)
        fun trades(count: Int) {
            repeat(count) {
                val ins = instruments[rnd.nextInt(instruments.size)]; val lane = plan.laneOf(ins)!!
                val b = participants[rnd.nextInt(participants.size)]; var s = participants[rnd.nextInt(participants.size)]; if (s == b) s = participants[(participants.indexOf(b) + 1) % participants.size]
                val units = 1L + rnd.nextInt(4); val nanos = 1_000L + rnd.nextInt(50)
                val i = n++
                producer.send(ProducerRecord(topics.capture, lane, ByteArray(0), record(i, ins, b, s, units, nanos))).get(15, TimeUnit.SECONDS)
                feedTrade(lane, ExecutionKey("p3-run", "p3-session", ins, "exec-$i"), b, s, units, nanos)
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
                assertEquals(shadow[lane].snapshot().positions.filterValues { it != 0L }.mapKeys { it.key.first.value to it.key.second.value }, positions.filterValues { it != 0L }, "lane $lane positions")
                assertEquals(oracle[lane].snapshot().positions.filterValues { it != 0L }, shadow[lane].snapshot().positions.filterValues { it != 0L }, "lane $lane: the ledger holds the same positions in both modes")
                val obligations = ds.connection.use { c -> c.prepareStatement("SELECT execution_id, status, cash_residual, share_residual FROM boundary.calcify_finance_obligation WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> val m = HashMap<String, Triple<String, Long, Long>>(); while (r.next()) m[r.getString(1)] = Triple(r.getString(2), r.getLong(3), r.getLong(4)); m } } }
                val wanted = shadow[lane].snapshot().obligations.entries.associate { (k, o) -> k.executionId to Triple(o.status.name, o.cashResidual, o.shareResidual) }
                assertEquals(wanted, obligations, "lane $lane obligations (inserted settled when settled in the same batch, updated otherwise)")
                val attempts = ds.connection.use { c -> c.prepareStatement("SELECT execution_id, attempt, disposition FROM boundary.calcify_finance_attempt WHERE binding_digest = ?").use { ps -> ps.setString(1, plan.bindingDigest(lane)); ps.executeQuery().use { r -> val m = HashMap<Pair<String, Int>, String>(); while (r.next()) m[r.getString(1) to r.getInt(2)] = r.getString(3); m } } }
                assertEquals(shadow[lane].snapshot().attempts.entries.associate { (k, v) -> (k.first.executionId to k.second) to v.name }, attempts, "lane $lane attempt rows equal the two-step flow's, including the settled first attempts the combined ledger holds no leaf for")
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
            assertTrue(expected.all { it.size > 40 }, "every lane did real work: ${expected.map { it.size }}")
            assertTrue(expected.flatten().count { it.disposition == Disposition.SETTLED } > 200, "most trades settled")
            if (combined) {
                assertEquals(240, expected.flatten().count { it.disposition == Disposition.SETTLED && it.legs.size == 4 }, "one SETTLED four-leg decision per booked trade")
                assertTrue(expected.indices.all { shadow[it].sequence > oracle[it].sequence }, "the two-step flow needs more decisions for the same trades")
            }

            // 1b. Shortfalls and operator repairs: a participant with shares but no cash buys; the first attempt is recorded (the obligation stays pending, attempt 1 on record),
            // then the account is funded and the operator settles attempt 2 for each. The combined mode must keep this working live, in the projection and across the restarts below.
            if (shortfalls) {
                val poor = AccountId("poor"); val shorts = ArrayList<Pair<Int, ExecutionKey>>()
                CapacityControlWriter(client, topics.control, plan).use { w ->
                    for (ins in instruments) { w.fundSecurity("poor", poor, ins, 50L); feed(plan.laneOf(ins)!!, FinanceInput.Fund(ActionKey("operator", "fund", "poor/$ins"), Authority.OPERATOR, poor, AssetId(ins), 50L)) }
                }
                waitFor("poor admitted") { running.healthy() && converged() }
                repeat(30) { k ->
                    val ins = instruments[k % instruments.size]; val lane = plan.laneOf(ins)!!; val i = n++; val seller = participants[k % participants.size]
                    producer.send(ProducerRecord(topics.capture, lane, ByteArray(0), record(i, ins, poor, seller, 2, 100))).get(15, TimeUnit.SECONDS)
                    val key = ExecutionKey("p3-run", "p3-session", ins, "exec-$i"); feedTrade(lane, key, poor, seller, 2, 100); shorts += lane to key
                }
                waitFor("shortfalls converged") { running.healthy() && converged() }; verify()
                assertEquals(30, expected.flatten().count { it.disposition == Disposition.INSUFFICIENT_CASH }, "each shortfall is one recorded decision")
                assertEquals(30, (0 until lanes).sumOf { l -> shadow[l].snapshot().obligations.values.count { it.status == ObligationStatus.PENDING } })
                CapacityControlWriter(client, topics.control, plan).use { w ->
                    w.fundCash("poor-cash", poor, List(lanes) { 10_000_000L })
                    for (lane in 0 until lanes) feed(lane, FinanceInput.Fund(ActionKey("operator", "fund", "poor-cash/cash/$lane"), Authority.OPERATOR, poor, plan.cashAsset, 10_000_000L))
                    shorts.forEachIndexed { idx, (lane, key) -> w.repair("rp-$idx", key, 2); feed(lane, FinanceInput.Settle(ActionKey("operator", "repair", "rp-$idx"), Authority.OPERATOR, key, 2)) }
                    shorts.take(5).forEachIndexed { idx, (lane, key) -> w.repair("again-$idx", key, 1); feed(lane, FinanceInput.Settle(ActionKey("operator", "repair", "again-$idx"), Authority.OPERATOR, key, 1)) }     // refused, not recorded
                }
                waitFor("repairs converged") { running.healthy() && converged() }; verify()
                assertEquals(0, (0 until lanes).sumOf { l -> shadow[l].snapshot().obligations.values.count { it.status == ObligationStatus.PENDING } }, "every repaired trade settled")
            }

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
