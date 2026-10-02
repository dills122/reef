package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.PersistedOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresRunOrderIsolationIntegrationTest {
    @Test
    fun sameOrderIdPreservesBothRunAcceptances() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") != null, "real Postgres integration requires RUNTIME_POSTGRES_JDBC_URL_TEST")
        val source = RuntimeDataSources.dataSource(
            requireNotNull(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_USER_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")), "run-isolation-test-${java.util.UUID.randomUUID()}"
        )
        val schema = "run_isolation_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        try {
        val persistence = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
        val order = PersistedOrder(
            orderId = "shared", engineOrderId = "shared", instrumentId = "AAPL",
            participantId = "participant-a", accountId = "account-a", side = "BUY",
            orderType = "LIMIT", quantityUnits = "10", limitPrice = "100",
            currency = "USD", timeInForce = "DAY", acceptedAt = "2026-10-01T00:00:00Z",
            runId = "run-a", venueSessionId = "session"
        )
        persistence.saveAcceptedOrder(order)
        persistence.saveAcceptedOrder(order.copy(runId = "run-b", participantId = "participant-b", accountId = "account-b"))
        assertEquals(setOf("run-a", "run-b"), persistence.acceptedOrders().map { it.runId }.toSet())
        assertEquals(setOf("participant-a", "participant-b"), persistence.acceptedOrders().map { it.participantId }.toSet())
        assertRunOrderHistoryIsolation(persistence)
        val reopened = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
        assertEquals("buyer-run-a", reopened.acceptedOrder(com.reef.platform.domain.RuntimeOrderIdentity("run-a", "shared"))?.participantId)
        assertEquals("buyer-run-b", reopened.acceptedOrder(com.reef.platform.domain.RuntimeOrderIdentity("run-b", "shared"))?.participantId)
        } finally {
            source.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun bulkProjectionKeepsRunOrderHistory() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") != null, "real Postgres integration requires RUNTIME_POSTGRES_JDBC_URL_TEST")
        val source = RuntimeDataSources.dataSource(
            requireNotNull(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_USER_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")), "run-isolation-test-${java.util.UUID.randomUUID()}"
        )
        val schema = "run_isolation_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        try {
        val persistence = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
        val order = PersistedOrder(
            orderId = "shared", engineOrderId = "shared", instrumentId = "AAPL",
            participantId = "participant-a", accountId = "account-a", side = "BUY",
            orderType = "LIMIT", quantityUnits = "10", limitPrice = "100",
            currency = "USD", timeInForce = "DAY", acceptedAt = "2026-10-01T00:00:00Z",
            runId = "run-a", venueSessionId = "session"
        )
        persistence.saveAcceptedOrder(order)
        persistence.saveAcceptedOrder(order.copy(runId = "run-b", participantId = "participant-b", accountId = "account-b"))
        assertEquals(setOf("run-a", "run-b"), persistence.acceptedOrders().map { it.runId }.toSet())
        assertEquals(setOf("participant-a", "participant-b"), persistence.acceptedOrders().map { it.participantId }.toSet())
        assertRunOrderHistoryIsolation(persistence, bulk = true)
        val reopened = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
        assertEquals("buyer-run-a", reopened.acceptedOrder(com.reef.platform.domain.RuntimeOrderIdentity("run-a", "shared"))?.participantId)
        assertEquals("buyer-run-b", reopened.acceptedOrder(com.reef.platform.domain.RuntimeOrderIdentity("run-b", "shared"))?.participantId)
        } finally {
            source.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun canonicalReplayPreservesScopedAcceptancesAndFills() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") != null, "real Postgres integration requires RUNTIME_POSTGRES_JDBC_URL_TEST")
        val source = RuntimeDataSources.dataSource(
            requireNotNull(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_USER_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")), "run-isolation-test-${java.util.UUID.randomUUID()}"
        )
        val schema = "run_isolation_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        try {
        val persistence = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Compat)
        val commandLog = com.reef.platform.api.PostgresCommandLogStore(source, bootstrapMode = PostgresBootstrapMode.Validate)
        val capturedIds = mutableListOf<String>()
        try {
            assertCanonicalRunOrderLifecycleIsolation(persistence) { commandId, payload ->
                commandLog.append(
                    com.reef.platform.api.CommandLogRecord(
                        commandId = commandId, clientId = "run-isolation", route = "/orders/shared",
                        idempotencyKey = commandId, traceId = commandId, correlationId = commandId,
                        actorId = "actor", commandType = com.reef.platform.api.JsonCodec.parseObject(payload).string("commandType"), receivedAt = java.time.Instant.now(),
                        payloadJson = payload
                    )
                )
                capturedIds += commandId
            }
        } finally {
            source.connection.use { connection ->
                connection.prepareStatement("DELETE FROM command_log.commands WHERE command_id = ?").use { statement ->
                    capturedIds.forEach { statement.setString(1, it); statement.addBatch() }
                    statement.executeBatch()
                }
            }
        }
        } finally {
            source.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
            (source as? AutoCloseable)?.close()
        }
    }

}
