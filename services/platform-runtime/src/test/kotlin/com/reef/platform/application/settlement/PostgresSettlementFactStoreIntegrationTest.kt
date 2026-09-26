package com.reef.platform.application.settlement

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresSettlementFactStoreIntegrationTest {
    @Test
    fun identicalReplaySucceedsButCrossRunIdentityCollisionRollsBack() {
        withStore { store ->
            val first = position("run-first", "shared-position")
            store.appendFacts(first)
            store.appendFacts(first)
            store.appendFacts(position("clean-run", "clean-1").copy(
                resourcePositions = position("clean-run", "clean-1").resourcePositions +
                    position("clean-run", "clean-2").resourcePositions
            ))

            val conflictingBundle = position("run-second", "new-position").copy(
                resourcePositions = position("run-second", "new-position").resourcePositions +
                    position("run-second", "shared-position").resourcePositions
            )
            assertFailsWith<IllegalArgumentException> { store.appendFacts(conflictingBundle) }

            assertEquals(1, store.factsByScenarioRunId("run-first").resourcePositions.size)
            assertTrue(store.factsByScenarioRunId("run-second").isEmpty())
        }
    }

    @Test
    fun concurrentCrossRunIdentityCollisionHasOneWinner() {
        withStore { store ->
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val writes = listOf("run-a", "run-b").map { runId ->
                    executor.submit<Boolean> {
                        start.await(5, TimeUnit.SECONDS)
                        try {
                            store.appendFacts(position(runId, "concurrent-position"))
                            true
                        } catch (_: IllegalArgumentException) {
                            false
                        }
                    }
                }
                start.countDown()
                assertEquals(1, writes.count { it.get(10, TimeUnit.SECONDS) })
                assertEquals(1, listOf("run-a", "run-b").sumOf {
                    store.factsByScenarioRunId(it).resourcePositions.size
                })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun withStore(block: (PostgresSettlementFactStore) -> Unit) {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "settlement-facts-${UUID.randomUUID()}")
        val schema = "settlement_facts_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            val store = PostgresSettlementFactStore(
                source,
                PostgresSettlementSqlNames(schema),
                PostgresBootstrapMode.Compat
            )
            block(store)
        } finally {
            source.connection.use { conn ->
                conn.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
            (source as? AutoCloseable)?.close()
        }
    }

    private fun position(runId: String, id: String) = SettlementFactBundle(
        scenarioRunId = runId,
        resourcePositions = listOf(
            SettlementResourcePositionFact(
                resourcePositionId = id,
                scenarioRunId = runId,
                correlationId = "correlation-$runId",
                causationId = "cause-$runId",
                participantId = "participant-$runId",
                accountId = "account-$runId",
                assetType = "CASH",
                assetId = "USD",
                quantity = "10.00",
                occurredAt = Instant.parse("2026-09-25T00:00:00.123456789Z")
            )
        )
    )
}
