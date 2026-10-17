package com.reef.platform.calcify.finance

import com.reef.platform.api.AuthHook
import com.reef.platform.api.BoundaryError
import com.reef.platform.api.DefaultIdempotencyRetentionPolicy
import com.reef.platform.api.ExternalApiBoundary
import com.reef.platform.api.ExternalApiPrincipal
import com.reef.platform.api.InMemoryIdempotencyStore
import com.reef.platform.api.PlatformApi
import com.reef.platform.api.PlatformHttpServer
import com.reef.platform.application.OrderApplicationService
import com.reef.platform.calcify.FiniteLifecycleFixtures
import com.reef.platform.calcify.finance.FinanceBrokerFixture as F
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * S3-A2 / O5: the finite P3 path end to end on real infrastructure, with no capacity claim.
 * three captured trades (pending, zero legs) -> fund -> three distinct repairs settle once -> projection -> read routes, then a
 * crash-style restart (wiped local state) whose restore cut verifies and whose projection and routes do not change.
 * Needs Docker (Redpanda) and the CI Postgres fixture (projector and reader roles); skips cleanly without them.
 */
@Tag("broker")
class FiniteP3EndToEndTest {
    private val binding = F.bindingDigest      // one digest per run: store, projection and envelope check
    private val mapper = ObjectMapper()

    private fun pg(role: String): PGSimpleDataSource {
        val prefix = "CALCIFY_FINITE_POSTGRES_TEST_"
        val url = System.getenv(prefix + "JDBC_URL")
        assumeTrue(!url.isNullOrBlank(), "isolated finite Postgres test fixture not provisioned")
        val file = Path.of(System.getenv(prefix + role + "_PASSWORD_FILE") ?: error("$role password file missing"))
        return PGSimpleDataSource().apply { setUrl(url); user = System.getenv(prefix + role + "_USER") ?: error("$role user missing"); password = Files.readString(file).trimEnd('\n', '\r') }
    }

    private fun waitProcessed(bootstrap: String, admin: AdminClient) = F.waitUntil("finance app processed every input", seconds = 120) {
        (F.committed(admin, F.CAPTURE) ?: 0L) >= F.readCommittedEnd(bootstrap, F.CAPTURE) && (F.committed(admin, F.CONTROL) ?: 0L) >= F.readCommittedEnd(bootstrap, F.CONTROL)
    }

    private val authHook = object : AuthHook {
        override fun authorize(clientId: String, token: String?): BoundaryError? = null
        override fun principal(clientId: String) = if (clientId == "buyer-client") ExternalApiPrincipal(clientId, accountIds = setOf("buyer-account")) else ExternalApiPrincipal(clientId)
    }

    private fun awaitProjected(port: Int, sequence: Long) = F.waitUntil("projection reached sequence $sequence", seconds = 60) {
        val r = get(port, "/internal/boundary/calcify/finance/$binding/checkpoint")
        r.first == 200 && r.second["checkpoint"]["lastSequence"].asLong() >= sequence
    }

    private fun get(port: Int, path: String, client: String? = null): Pair<Int, com.fasterxml.jackson.databind.JsonNode> {
        val c = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        client?.let { c.setRequestProperty("X-Client-Id", it) }
        val text = (if (c.responseCode >= 400) c.errorStream else c.inputStream).bufferedReader().readText()
        return c.responseCode to mapper.readTree(text)
    }

    @Test fun threeTradesPendingThenFundedThenRepairedOnceThroughProjectionAndRoutes() {
        val projectorDs = pg("PROJECTOR"); val readerDs = pg("READER")
        // One binding digest per run means a re-run against the same database would find the previous run's projection (and the roles cannot
        // delete it). CI databases are fresh; locally, recreate the database.
        assumeTrue(FinanceProjector(F.ledger, projectorDs).checkpoint(binding) == null, "a projection for the fixture binding already exists in this database; use a fresh one")
        val envelopes = FiniteLifecycleFixtures.sequence().completedRecordsList
        val inputsInOrder = ArrayList<FinanceInput>()
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin ->
                F.createTopics(admin)
                val reader = FinanceEvidenceReader(Properties().apply { put("bootstrap.servers", rp.bootstrap) }, F.APP_ID, F.bindingDigest, F.CAPTURE, F.CONTROL, F.DECISIONS, F.changelog)
                val stateDir = Files.createTempDirectory("finance-o5-state").toFile()
                fun launcher() = FiniteFinanceLauncher(F.config, Properties().apply { put("bootstrap.servers", rp.bootstrap) }, Properties().apply { put("bootstrap.servers", rp.bootstrap) }, stateDir.path,
                    { FinanceProjector(F.ledger, projectorDs) }, projectionIntervalMs = 200)
                val server = PlatformHttpServer(port = 0, api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence())), boundary = ExternalApiBoundary(authHook = authHook),
                    idempotencyStore = InMemoryIdempotencyStore(), idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(), financeReadStore = JdbcFinanceReadStore(readerDs)).start()
                val port = server.address.port
                var running = launcher().start()          // activation: restore cut verifies (genesis), then the app and the projection loop start
                val producer = F.producer(rp.bootstrap)
                val executions = ArrayList<ExecutionKey>()
                try {
                    // 1. Three trades are captured: booked, pending, no money moves.
                    for (e in envelopes) {
                        F.send(producer, F.CAPTURE, e.toByteArray())
                        FinanceCaptureMapping.map(e, F.captureBinding).filterIsInstance<FinanceCaptureMapping.Mapped.Ready>().forEach { inputsInOrder += it.input; executions += it.input.execution }
                    }
                    assertEquals(3, executions.size)
                    waitProcessed(rp.bootstrap, admin)
                    awaitProjected(port, 3)
                    var obligations = get(port, "/internal/boundary/calcify/finance/$binding/obligations").second["obligations"]
                    assertEquals(3, obligations.size()); assertTrue(obligations.all { it["status"].asText() == "PENDING" && it["cashResidual"].asLong() > 0 })
                    assertEquals(0, get(port, "/internal/boundary/calcify/finance/$binding/positions").second["positions"].size(), "no money has moved")
                    assertEquals(0, get(port, "/internal/boundary/calcify/finance/$binding/attempts").second["attempts"].size())
                    assertTrue(F.readAll(rp.bootstrap, F.DECISIONS).map { FinanceWire.recordFromProto(reef.contracts.calcify.finance.v1.FinanceDecisionV1.parseFrom(it.second!!)).first }.all { it.legs.isEmpty() }, "captures carry zero legs")

                    // 2. The operator funds both sides (6 shares, 600,000,000,000 quote nanos) through the control writer.
                    FiniteControlWriter(Properties().apply { put("bootstrap.servers", rp.bootstrap) }, F.CONTROL, "o5-control-writer").use { writer ->
                        writer.recover()
                        val seller = FinanceInput.Fund(ActionKey("operator", "fund", "seller-shares"), Authority.OPERATOR, F.seller, F.aapl, 6)
                        val buyer = FinanceInput.Fund(ActionKey("operator", "fund", "buyer-cash"), Authority.OPERATOR, F.buyer, F.usd, 600_000_000_000L)
                        assertTrue(writer.fund(seller.action, F.seller, F.aapl, 6) is ControlSubmission.Submitted); inputsInOrder += seller
                        assertTrue(writer.fund(buyer.action, F.buyer, F.usd, 600_000_000_000L) is ControlSubmission.Submitted); inputsInOrder += buyer
                        waitProcessed(rp.bootstrap, admin)

                        // 3. Three distinct repairs settle each execution exactly once; a repeat of the same key changes nothing.
                        executions.forEachIndexed { i, ex ->
                            val action = ActionKey("operator", "repair", "repair-$i")
                            assertTrue(writer.settle(action, ex, 1) is ControlSubmission.Submitted)
                            inputsInOrder += FinanceInput.Settle(action, Authority.OPERATOR, ex, 1)
                            waitProcessed(rp.bootstrap, admin)
                        }
                        assertTrue(writer.settle(ActionKey("operator", "repair", "repair-0"), executions[0], 1) is ControlSubmission.AlreadySubmitted)
                    }
                    awaitProjected(port, 8)

                    // Exact rows through the operator route and, scoped to its own account, the participant route.
                    var oracle = F.ledger.genesis(); val expected = ArrayList<Decision>()
                    for (i in inputsInOrder) F.ledger.decide(oracle, i).also { oracle = it.state; it.decision?.let(expected::add) }
                    assertEquals(8, expected.size); assertEquals(listOf(Disposition.CAPTURED, Disposition.CAPTURED, Disposition.CAPTURED, Disposition.FUNDED, Disposition.FUNDED, Disposition.SETTLED, Disposition.SETTLED, Disposition.SETTLED), expected.map { it.disposition })
                    val decisions = get(port, "/internal/boundary/calcify/finance/$binding/decisions").second["decisions"]
                    assertEquals(expected.map { it.checksum }, decisions.map { it["checksum"].asText() })
                    val checkpoint = get(port, "/internal/boundary/calcify/finance/$binding/checkpoint").second["checkpoint"]
                    assertEquals(8L, checkpoint["lastSequence"].asLong()); assertEquals(oracle.chain, checkpoint["lastChain"].asText())
                    obligations = get(port, "/internal/boundary/calcify/finance/$binding/obligations").second["obligations"]
                    assertTrue(obligations.all { it["status"].asText() == "SETTLED" && it["cashResidual"].asLong() == 0L && it["shareResidual"].asLong() == 0L })
                    val attempts = get(port, "/internal/boundary/calcify/finance/$binding/attempts").second["attempts"]
                    assertEquals(3, attempts.size()); assertTrue(attempts.all { it["disposition"].asText() == "SETTLED" && it["attempt"].asInt() == 1 })
                    val positions = get(port, "/internal/boundary/calcify/finance/$binding/positions").second["positions"].associate { (it["accountId"].asText() to it["assetId"].asText()) to it["amount"].asLong() }
                    assertEquals(6L, positions[F.buyer.value to F.aapl.value]); assertEquals(600_000_000_000L, positions[F.seller.value to F.usd.value])
                    val own = get(port, "/api/v1/calcify/finance/$binding/positions?accountId=buyer-account", "buyer-client")
                    assertEquals(200, own.first); assertTrue(own.second["positions"].all { it["accountId"].asText() == "buyer-account" })
                    assertEquals(403, get(port, "/api/v1/calcify/finance/$binding/positions?accountId=seller-account", "buyer-client").first)
                    assertEquals(403, get(port, "/api/v1/calcify/finance/$binding/decisions", "buyer-client").first)

                    // 4. Restart with local state wiped. The launcher verifies the restore cut BEFORE starting anything; then new input is processed on
                    //    the restored state (proving the restart is live, not merely quiet) and flows through projection to the routes.
                    assertEquals(null, running.projectionFailure())
                    running.close()
                    assertEquals(oracle, FinanceRestoreCut.verify(F.ledger, reader.read()).state)

                    stateDir.deleteRecursively(); stateDir.mkdirs()
                    running = launcher().start()
                    val fourth = F.envelope(100)
                    F.send(producer, F.CAPTURE, fourth.toByteArray())
                    (FinanceCaptureMapping.map(fourth, F.captureBinding).single() as FinanceCaptureMapping.Mapped.Ready).input.let { inputsInOrder += it; oracle = F.ledger.decide(oracle, it).state }
                    waitProcessed(rp.bootstrap, admin)
                    awaitProjected(port, 9)
                    assertEquals(oracle.chain, get(port, "/internal/boundary/calcify/finance/$binding/checkpoint").second["checkpoint"]["lastChain"].asText())
                    val after = get(port, "/internal/boundary/calcify/finance/$binding/obligations").second["obligations"]
                    assertEquals(4, after.size()); assertEquals(1, after.count { it["status"].asText() == "PENDING" }, "the post-restart trade is booked and pending")
                    running.close()
                    assertEquals(oracle, FinanceRestoreCut.verify(F.ledger, reader.read()).state)

                    // 5. A dead app must be visible. The 312-entry history bound halts the processor asynchronously, after start() returned;
                    //    healthy() has to turn false rather than leave a frozen projection looking alive.
                    running = launcher().start()
                    assertTrue(running.healthy())
                    repeat(300) { F.send(producer, F.CONTROL, byteArrayOf(1)) }
                    F.waitUntil("the halted app is visible through healthy()", seconds = 120) { !running.healthy() }
                    assertTrue(running.streamsState() != org.apache.kafka.streams.KafkaStreams.State.RUNNING)
                } finally {
                    runCatching { running.close() }; producer.close(); server.stop(0)
                }
            }
        }
    }
}
