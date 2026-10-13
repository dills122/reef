package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteBindingContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.acl.AccessControlEntry
import org.apache.kafka.common.acl.AclBinding
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.acl.AclPermissionType
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourceType
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The finance process role end to end on a SASL/SCRAM Redpanda with authorization on and a real Postgres projector role: the environment
 * contract builds a launcher, the restore cut passes at genesis under the read-only `observer` principal, the Streams app starts under the
 * `finance` principal with only its minimal grants, and the owner-fenced projection is opened. Skipped (reported, not passed) without the
 * CALCIFY_FINITE_POSTGRES_TEST_* projector fixture.
 */
@Tag("broker")
class FiniteFinanceRuntimeBrokerTest {
    private val fixture = Path.of("../../contracts/calcify/finite-binding-v2.json")
    private val opening = FiniteBindingContract.parseOpeningV2(Files.readAllBytes(fixture))
    private val config = FinanceProcessorConfig.fromOpening(opening)

    private fun grants() = FinanceGrants.financeAndObserver("finance", "observer", config.applicationId, config.captureTopic, config.controlTopic, config.decisionsTopic, config.changelogTopic)

    private fun private(dir: Path, name: String, content: String): Path =
        Files.writeString(dir.resolve(name), content).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------")) }

    private fun postgres(): Triple<String, String, String> {
        val prefix = "CALCIFY_FINITE_POSTGRES_TEST_"
        val url = System.getenv(prefix + "JDBC_URL"); assumeTrue(!url.isNullOrBlank(), "isolated finite Postgres test fixture not provisioned")
        return Triple(url, System.getenv(prefix + "PROJECTOR_USER") ?: error("projector test user missing"), System.getenv(prefix + "PROJECTOR_PASSWORD_FILE") ?: error("projector password file missing"))
    }

    private fun env(rp: SecuredRedpanda, pg: Triple<String, String, String>, observerPassword: String): Map<String, String> {
        val dir = Files.createTempDirectory("finance-runtime-broker"); val state = Files.createDirectory(dir.resolve("state")).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        return mapOf(
            "CALCIFY_FINANCE_BINDING_DIGEST" to opening.digest, "CALCIFY_FINANCE_OPENING_FILE" to private(dir, "opening.json", Files.readString(fixture)).toString(),
            "CALCIFY_FINANCE_KAFKA_BOOTSTRAP_SERVERS" to rp.bootstrap, "CALCIFY_FINANCE_KAFKA_SASL_MECHANISM" to "SCRAM-SHA-256",
            "CALCIFY_FINANCE_KAFKA_USERNAME" to "finance", "CALCIFY_FINANCE_KAFKA_PASSWORD_FILE" to private(dir, "finance.pw", rp.password("finance")).toString(),
            "CALCIFY_FINANCE_OBSERVER_KAFKA_USERNAME" to "observer", "CALCIFY_FINANCE_OBSERVER_KAFKA_PASSWORD_FILE" to private(dir, "observer.pw", observerPassword).toString(),
            "CALCIFY_FINANCE_POSTGRES_JDBC_URL" to pg.first, "CALCIFY_FINANCE_POSTGRES_USER" to pg.second, "CALCIFY_FINANCE_POSTGRES_PASSWORD_FILE" to pg.third,
            "CALCIFY_FINANCE_STATE_DIRECTORY" to state.toString(), "CALCIFY_FINANCE_PROJECTION_INTERVAL_MS" to "200",
        )
    }

    private fun provision(rp: SecuredRedpanda) = rp.admin("admin").use { admin ->
        val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
        admin.createTopics(listOf(config.captureTopic, config.controlTopic, config.decisionsTopic).map { NewTopic(it, 1, 1.toShort()).configs(history) } +
            NewTopic(config.changelogTopic, 1, 1.toShort()).configs(mapOf("cleanup.policy" to "compact"))).all().get(30, TimeUnit.SECONDS)
        admin.createAcls(grants()).all().get(30, TimeUnit.SECONDS)
    }

    @Test fun theRuntimeStartsTheFinanceAppAndOpensTheProjectionUnderItsMinimalGrants() {
        val pg = postgres()
        SecuredRedpanda(listOf("finance", "observer")).start().use { rp ->
            provision(rp)
            val running = FiniteFinanceRuntime.create(env(rp, pg, rp.password("observer")).let { m -> m::get }).start()
            try {
                val deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos()
                while (running.streamsState() != KafkaStreams.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(100)
                assertEquals(KafkaStreams.State.RUNNING, running.streamsState(), "the app runs under the finance principal")
                assertTrue(running.healthy())
                rp.admin("observer").use { assertTrue(it.listConsumerGroups().all().get(15, TimeUnit.SECONDS).any { g -> g.groupId() == config.applicationId }, "the finance group exists") }
                DriverManager.getConnection(pg.first, pg.second, java.nio.file.Files.readString(Path.of(pg.third)).trim()).use { c ->
                    c.prepareStatement("SELECT owner_epoch, last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps ->
                        ps.setString(1, opening.digest)
                        ps.executeQuery().use { r -> assertTrue(r.next(), "the projection was opened for this binding"); assertTrue(r.getLong(1) >= 1L, "the process claimed the owner epoch"); assertEquals(0L, r.getLong(2)); assertEquals(FinanceLedger(config.finance).genesisChain, r.getString(3), "the checkpoint starts at the genesis chain of this binding") }
                    }
                }
            } finally { running.close() }
        }
    }

    @Test fun aWrongObserverCredentialStartsNothing() {
        val pg = postgres()
        SecuredRedpanda(listOf("finance", "observer")).start().use { rp ->
            provision(rp)
            val failure = assertFailsWith<Exception>("evidence cannot be read, so the restore cut cannot run") { FiniteFinanceRuntime.create(env(rp, pg, "wrong-password").let { m -> m::get }).start().close() }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is org.apache.kafka.common.errors.SaslAuthenticationException }, "the failure is the broker refusing the observer credential, not some unrelated startup error: $failure")
            rp.admin("observer").use { assertTrue(it.listConsumerGroups().all().get(15, TimeUnit.SECONDS).none { g -> g.groupId() == config.applicationId }, "no app was started") }
        }
    }

    /** A clean stop (what SIGTERM triggers via the shutdown hook) ends supervision as a clean stop, not as an unhealthy runtime, and a second close is harmless. */
    @Test fun aCleanStopIsNotReportedAsAFailure() {
        val pg = postgres()
        SecuredRedpanda(listOf("finance", "observer")).start().use { rp ->
            provision(rp)
            val running = FiniteFinanceRuntime.create(env(rp, pg, rp.password("observer")).let { m -> m::get }).start()
            val reported = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val clean = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            val supervisor = Thread { clean.set(FiniteFinanceRuntime.supervise(running, pollMs = 50) { reported.set(it) }) }.also { it.start() }
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos()
            while (running.streamsState() != KafkaStreams.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(50)
            assertEquals(KafkaStreams.State.RUNNING, running.streamsState())
            running.close()                                        // what the shutdown hook does
            supervisor.join(15_000)
            assertTrue(!supervisor.isAlive, "supervision ends after a close")
            assertEquals(true, clean.get(), "a close is a clean stop")
            assertEquals(null, reported.get(), "a clean stop must not be reported as an unhealthy runtime")
            running.close()                                        // idempotent
        }
    }
}
