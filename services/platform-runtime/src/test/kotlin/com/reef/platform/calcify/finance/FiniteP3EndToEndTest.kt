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
import java.time.Duration
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
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
    private val binding = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)
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

    private fun get(port: Int, path: String, client: String? = null): Pair<Int, com.fasterxml.jackson.databind.JsonNode> {
        val c = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        client?.let { c.setRequestProperty("X-Client-Id", it) }
        val text = (if (c.responseCode >= 400) c.errorStream else c.inputStream).bufferedReader().readText()
        return c.responseCode to mapper.readTree(text)
    }

    @Test fun threeTradesPendingThenFundedThenRepairedOnceThroughProjectionAndRoutes() {
        val projectorDs = pg("PROJECTOR"); val readerDs = pg("READER")
        val envelopes = FiniteLifecycleFixtures.sequence().completedRecordsList
        val inputsInOrder = ArrayList<FinanceInput>()
        DisposableRedpanda().start().use { rp ->
            rp.admin().use { admin ->
                F.createTopics(admin)
                val reader = FinanceEvidenceReader(Properties().apply { put("bootstrap.servers", rp.bootstrap) }, F.APP_ID, F.bindingDigest, F.CAPTURE, F.CONTROL, F.DECISIONS, F.changelog)
                val runner = FinanceProjectionRunner(reader, FinanceProjector(F.ledger, projectorDs), binding, 1, F.DECISIONS).also { it.open() }
                val server = PlatformHttpServer(port = 0, api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence())), boundary = ExternalApiBoundary(authHook = authHook),
                    idempotencyStore = InMemoryIdempotencyStore(), idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(), financeReadStore = JdbcFinanceReadStore(readerDs)).start()
                val port = server.address.port
                val stateDir = Files.createTempDirectory("finance-o5-state").toFile()
                var streams = F.streams(rp.bootstrap, stateDir.path).also { it.start() }
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
                    assertEquals(3, runner.runOnce())
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
                    assertEquals(5, runner.runOnce(), "two funds and three repairs")
                    assertEquals(0, runner.runOnce(), "a second pass applies nothing")

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

                    // 4. Restart from the changelog with local state wiped: the restore cut verifies and nothing downstream changes.
                    streams.close(Duration.ofSeconds(30))
                    val before = FinanceRestoreCut.verify(F.ledger, reader.read())
                    assertEquals(oracle, before.state)
                    stateDir.deleteRecursively(); stateDir.mkdirs()
                    streams = F.streams(rp.bootstrap, stateDir.path).also { it.start() }
                    waitProcessed(rp.bootstrap, admin)
                    assertEquals(0, runner.runOnce(), "projection is unchanged by the restart")
                    assertEquals(oracle, FinanceRestoreCut.verify(F.ledger, reader.read()).state)
                    assertContains(get(port, "/internal/boundary/calcify/finance/$binding/checkpoint").second.toString(), oracle.chain)
                } finally {
                    runCatching { streams.close(Duration.ofSeconds(10)) }; producer.close(); server.stop(0)
                }
            }
        }
    }
}
