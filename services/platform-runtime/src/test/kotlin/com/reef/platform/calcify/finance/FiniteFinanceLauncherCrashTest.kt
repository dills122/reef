package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1

/** The launcher config for one run: the fixture lane with a run-unique binding digest, so reruns and other suites never meet an old projection. */
internal object LauncherCrashConfig {
    fun of(digest: String) = F.config.copy(bindingDigest = digest, captureBinding = F.captureBinding.copy(digest = digest))
    fun datasource(url: String, user: String, passwordFile: String) = PGSimpleDataSource().apply { setUrl(url); this.user = user; password = Files.readString(Path.of(passwordFile)).trimEnd('\n', '\r') }
}

/** Child-JVM entry point: the real [FiniteFinanceLauncher] (evidence, restore cut, projection, Streams app) until it is SIGKILLed. */
internal object FinanceLauncherChild {
    @JvmStatic fun main(args: Array<String>) {
        val (bootstrap, stateDir, digest, url, user, pwFile) = args.toList()
        val config = LauncherCrashConfig.of(digest)
        val ds = LauncherCrashConfig.datasource(url, user, pwFile)
        val evidence = Properties().apply { put("bootstrap.servers", bootstrap) }
        val streams = Properties().apply { put("bootstrap.servers", bootstrap); put("consumer.session.timeout.ms", 6000); put("consumer.heartbeat.interval.ms", 1000) }
        val running = FiniteFinanceLauncher(config, evidence, streams, stateDir, { FinanceProjector(FinanceLedger(config.finance), ds) }, projectionIntervalMs = 100).start()
        println("CHILD-STARTED")       // printed only after the restore cut verified and the projection opened
        while (running.healthy()) Thread.sleep(100)
        System.err.println("CHILD-UNHEALTHY streams=${running.streamsState()} projection=${running.projectionFailure()}")
        Runtime.getRuntime().halt(3)
    }
}
private operator fun <T> List<T>.component6() = this[5]

/**
 * F8: the finite P3 launcher and projector under SIGKILL, with data flowing, on a real Redpanda and a real Postgres projector role.
 * Three SIGKILLs of the launcher process land during catch-up after each restart; the second restart wipes the local RocksDB (a changelog
 * restore after a crash). Every restart must pass the restore cut (the child only reports started after it verified), and at the end the broker
 * decision log, the Postgres projection and an independent replay of the same inputs must agree exactly: no lost, duplicated or reordered
 * decision. Skips (reported, not passed) without the CI Postgres projector fixture.
 */
@Tag("broker")
class FiniteFinanceLauncherCrashTest {
    private val trades = 24

    private fun envelope(i: Int, digest: String): FiniteLifecycleCaptureV1 {
        val base = F.envelope(i).toBuilder().setFiniteBindingDigest(digest).build()
        return base.toBuilder().setContentDigest(FiniteLifecycleContract.digest(base)).build()
    }

    private fun decisions(bootstrap: String): List<Decision> =
        F.readAll(bootstrap, F.DECISIONS).map { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.second!!)).first }

    @Test fun killedLauncherRestartsThroughTheRestoreCutAndTheProjectionNeverDiverges() {
        val prefix = "CALCIFY_FINITE_POSTGRES_TEST_"
        val url = System.getenv(prefix + "JDBC_URL"); assumeTrue(!url.isNullOrBlank(), "isolated finite Postgres test fixture not provisioned")
        val user = System.getenv(prefix + "PROJECTOR_USER") ?: error("projector user missing"); val pwFile = System.getenv(prefix + "PROJECTOR_PASSWORD_FILE") ?: error("projector password file missing")
        val digest = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)
        val config = LauncherCrashConfig.of(digest); val ds = LauncherCrashConfig.datasource(url, user, pwFile)
        val logs = Files.createTempDirectory("launcher-crash-logs").toFile()
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin -> F.createTopics(admin) }
            val stateDir = Files.createTempDirectory("launcher-crash-state").toFile()
            val java = File(System.getProperty("java.home"), "bin/java").path
            var spawned = 0
            fun child(): Pair<Process, File> {
                val log = File(logs, "child-${spawned++}.log")
                return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.reef.platform.calcify.finance.FinanceLauncherChild", rp.bootstrap, stateDir.path, digest, url, user, pwFile)
                    .redirectErrorStream(true).redirectOutput(log).start() to log
            }
            fun awaitStarted(p: Pair<Process, File>) = F.waitUntil("child reached CHILD-STARTED (restore cut verified)", seconds = 150) {
                check(p.first.isAlive || p.second.readText().contains("CHILD-STARTED")) { "child exited before starting: ${p.second.readText().takeLast(2000)}" }
                p.second.readText().contains("CHILD-STARTED")
            }
            fun wipe() = stateDir.listFiles()?.forEach { it.deleteRecursively() }

            // The inputs, in the only order that is deterministic across two topics: every capture is decided before any control is sent.
            val inputs = ArrayList<FinanceInput>()
            val envelopes = (0 until trades).map { envelope(it, digest) }
            for (e in envelopes) FinanceCaptureMapping.map(e, config.captureBinding).filterIsInstance<FinanceCaptureMapping.Mapped.Ready>().forEach { inputs += it.input }
            assertEquals(trades, inputs.size)
            val executions = inputs.map { (it as FinanceInput.Capture).execution }
            val fundSeller = FinanceInput.Fund(ActionKey("operator", "fund", "seller-shares"), Authority.OPERATOR, F.seller, F.aapl, trades.toLong())
            val fundBuyer = FinanceInput.Fund(ActionKey("operator", "fund", "buyer-cash"), Authority.OPERATOR, F.buyer, F.usd, trades * F.PRICE)
            val settles = executions.mapIndexed { i, ex -> FinanceInput.Settle(ActionKey("operator", "repair", "repair-$i"), Authority.OPERATOR, ex, 1) }
            val total = trades + 2 + trades

            val feeder = Thread {
                val producer = F.producer(rp.bootstrap)
                try {
                    for (e in envelopes) { F.send(producer, F.CAPTURE, e.toByteArray()); Thread.sleep(350) }
                    F.waitUntil("every capture decided", seconds = 240) { decisions(rp.bootstrap).size >= trades }
                    FiniteControlWriter(Properties().apply { put("bootstrap.servers", rp.bootstrap) }, F.CONTROL, "f8-control-writer").use { writer ->
                        writer.recover()
                        assertTrue(writer.fund(fundSeller.action, F.seller, F.aapl, trades.toLong()) is ControlSubmission.Submitted)
                        assertTrue(writer.fund(fundBuyer.action, F.buyer, F.usd, trades * F.PRICE) is ControlSubmission.Submitted)
                        F.waitUntil("funds decided", seconds = 240) { decisions(rp.bootstrap).size >= trades + 2 }
                        for (s in settles) { assertTrue(writer.settle(s.action, s.execution, 1) is ControlSubmission.Submitted); Thread.sleep(350) }
                    }
                } finally { producer.close() }
            }
            var feederFailure: Throwable? = null
            feeder.setUncaughtExceptionHandler { _, e -> feederFailure = e }
            feeder.start()

            val random = Random(20261013)
            var killsAfterProgress = 0; var killsInsideATransaction = 0
            for (round in 0 until 3) {
                val c = child(); awaitStarted(c)
                // Kill only once decisions are actually flowing through this child (or the feeder has nothing left to send), plus a sub-commit-interval jitter,
                // so the SIGKILL lands in the process/commit/projection cycle, not in startup.
                val base = decisions(rp.bootstrap).size; val target = base + 2 + random.nextInt(5)
                F.waitUntil("progress before kill $round", seconds = 150) { decisions(rp.bootstrap).size >= target || !feeder.isAlive }
                Thread.sleep(random.nextInt(120).toLong())
                val progressed = decisions(rp.bootstrap).size > base
                c.first.destroyForcibly().waitFor()               // SIGKILL: no shutdown hook, no close(), no LeaveGroup, no final commit
                val open = F.uncommittedEnd(rp.bootstrap, F.DECISIONS) > F.readCommittedEnd(rp.bootstrap, F.DECISIONS)
                if (progressed) killsAfterProgress++; if (open) killsInsideATransaction++
                println("F8-KILL round=$round decisionsBefore=$base progressed=$progressed openTransactionOnDecisions=$open wipeNext=${round == 1}")
                if (round == 1) wipe()                            // the next start restores RocksDB from the changelog
            }
            assertTrue(killsAfterProgress >= 2, "at least two kills landed while decisions were flowing (got $killsAfterProgress)")
            val last = child(); awaitStarted(last)
            try {
                feeder.join(240_000)
                assertTrue(!feeder.isAlive, "the feeder finished"); assertEquals(null, feederFailure, "feeder: $feederFailure")
                F.waitUntil("all $total inputs decided and projected", seconds = 240) {
                    decisions(rp.bootstrap).size >= total && FinanceProjector(FinanceLedger(config.finance), ds).checkpoint(digest)?.first == total.toLong()
                }
                assertTrue(last.first.isAlive, "the final launcher is still healthy: ${last.second.readText().takeLast(1500)}")
            } finally { last.first.destroyForcibly().waitFor() }

            // 1. The broker decision log equals an independent replay of the same inputs, in order, with contiguous sequences and no duplicates.
            val ledger = FinanceLedger(config.finance)
            var oracle = ledger.genesis(); val expected = ArrayList<Decision>()
            for (i in inputs + fundSeller + fundBuyer + settles) ledger.decide(oracle, i).also { oracle = it.state; it.decision?.let(expected::add) }
            assertEquals(total, expected.size)
            val log = decisions(rp.bootstrap)
            assertEquals((1L..total).toList(), log.map { it.sequence }, "contiguous sequences, no duplicate or lost decision")
            assertEquals(expected.map { it.checksum }, log.map { it.checksum }, "the decision chain equals an independent replay")
            // 2. The projection equals the log and the oracle state.
            assertEquals(total.toLong() to oracle.chain, FinanceProjector(ledger, ds).checkpoint(digest))
            ds.connection.use { c ->
                c.prepareStatement("SELECT sequence, checksum FROM boundary.calcify_finance_decision WHERE binding_digest = ? ORDER BY sequence").use { ps ->
                    ps.setString(1, digest)
                    ps.executeQuery().use { r -> val rows = buildList { while (r.next()) add(r.getLong(1) to r.getString(2)) }; assertEquals(log.map { it.sequence to it.checksum }, rows, "projected decisions equal the broker log") }
                }
                val positions = c.prepareStatement("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ?").use { ps ->
                    ps.setString(1, digest); ps.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1) to r.getString(2), r.getLong(3)) } }
                }
                assertEquals(oracle.positions.filterValues { it != 0L }.mapKeys { it.key.first.value to it.key.second.value }, positions.filterValues { it != 0L }, "projected positions equal the replay")
                val open = c.prepareStatement("SELECT count(*) FROM boundary.calcify_finance_obligation WHERE binding_digest = ? AND status <> 'SETTLED'").use { ps -> ps.setString(1, digest); ps.executeQuery().use { r -> r.next(); r.getInt(1) } }
                assertEquals(0, open, "every obligation settled exactly once")
            }
            // 2b. Every start claimed the projection: the stored epoch counts the children that got past the restore cut (4: three killed, one final).
            ds.connection.use { c -> c.prepareStatement("SELECT owner_epoch FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps ->
                ps.setString(1, digest); ps.executeQuery().use { r -> r.next(); assertEquals(spawned.toLong(), r.getLong(1), "each launcher start claims the owner epoch exactly once") } } }
            // 3. The restore cut still verifies over the final broker state.
            FinanceRestoreCut.verify(ledger, FinanceEvidenceReader(Properties().apply { put("bootstrap.servers", rp.bootstrap) }, F.APP_ID, digest, F.CAPTURE, F.CONTROL, F.DECISIONS, F.changelog).read())
            println("F8 OK kills=3 afterProgress=$killsAfterProgress insideTransaction=$killsInsideATransaction children=$spawned decisions=$total")
        }
    }
}
