package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandCaptureStoreTest {
    @Test
    fun defaultCaptureStoreLeavesCommandLogDisabledByDefault() {
        val store = defaultCommandCaptureStore { key ->
            when (key) {
                "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> "inmemory"
                else -> null
            }
        }

        assertIs<InMemoryCommandCaptureStore>(store)
    }

    @Test
    fun defaultCaptureStoreWrapsCaptureStoreWhenCommandLogModeIsInMemory() {
        val store = defaultCommandCaptureStore { key ->
            when (key) {
                "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> "inmemory"
                "EXTERNAL_API_COMMAND_LOG_MODE" -> "inmemory"
                else -> null
            }
        }

        assertIs<CommandLogCommandCaptureStore>(store)
    }

    @Test
    fun commandLogCaptureAppendsReceivedCommandBeforeDelegating() {
        val delegate = RecordingCommandLogCaptureStore()
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = delegate,
            commandLogStore = commandLogStore,
            clock = { Instant.parse("2026-06-04T14:00:00Z") }
        )

        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            correlationId = "corr-header",
            requestPayload = """
                {
                  "commandId":"cmd-1",
                  "traceId":"trace-1",
                  "correlationId":"corr-payload",
                  "actorId":"actor-1"
                }
            """.trimIndent()
        )

        val record = commandLogStore.findByIdempotency("client-1", "/api/v1/orders/submit", "idem-1")
        assertNotNull(record)
        assertEquals("cmd-1", record.commandId)
        assertEquals("trace-1", record.traceId)
        assertEquals("corr-payload", record.correlationId)
        assertEquals("actor-1", record.actorId)
        assertEquals("SubmitOrder", record.commandType)
        assertEquals(Instant.parse("2026-06-04T14:00:00Z"), record.receivedAt)
        assertEquals(CommandLogStatus.RECEIVED, record.status)
        assertEquals(1, delegate.receivedCalls)
    }

    @Test
    fun commandLogCaptureUsesStableFallbacksForMalformedPayload() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            clock = { Instant.parse("2026-06-04T14:00:00Z") }
        )

        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/cancel",
            idempotencyKey = "idem-1",
            correlationId = "corr-header",
            requestPayload = "not-json"
        )

        val record = commandLogStore.findByIdempotency("client-1", "/api/v1/orders/cancel", "idem-1")
        assertNotNull(record)
        assertTrue(record.commandId.startsWith("generated-"))
        assertEquals("corr-header", record.traceId)
        assertEquals("corr-header", record.correlationId)
        assertEquals("client-1", record.actorId)
        assertEquals("CancelOrder", record.commandType)
        assertEquals("""{"rawPayload":"not-json"}""", record.payloadJson)
    }

    @Test
    fun commandLogCaptureDelegatesCompletionAndFailureToExistingCaptureStore() {
        val delegate = RecordingCommandLogCaptureStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = delegate,
            commandLogStore = InMemoryCommandLogStore()
        )

        captureStore.markCompleted(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            responseStatus = 200,
            responsePayload = """{"ok":true}"""
        )
        captureStore.markFailed(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-2",
            responseStatus = 503,
            errorClass = "RuntimeUnavailable",
            errorMessage = "runtime unavailable"
        )

        assertEquals(1, delegate.completedCalls)
        assertEquals(1, delegate.failedCalls)
    }
}

private class RecordingCommandLogCaptureStore : CommandCaptureStore {
    var receivedCalls: Int = 0
    var completedCalls: Int = 0
    var failedCalls: Int = 0

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        receivedCalls++
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
        completedCalls++
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
        failedCalls++
    }
}

class PostgresCommandLogCaptureIntegrationTest {
    @Test
    fun commandLogCaptureAppendsToMigratedPostgresCommandLogWhenConfigured() {
        val dataSource = postgresDataSourceOrNull() ?: return
        val commandLogStore = PostgresCommandLogStore(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            clock = { Instant.parse("2026-06-04T14:00:00Z") }
        )
        val suffix = UUID.randomUUID().toString()
        val commandId = "cmd-capture-$suffix"
        val idempotencyKey = "idem-capture-$suffix"

        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/modify",
            idempotencyKey = idempotencyKey,
            correlationId = "corr-header",
            requestPayload = """
                {
                  "commandId":"$commandId",
                  "traceId":"trace-$suffix",
                  "correlationId":"corr-$suffix",
                  "actorId":"actor-1"
                }
            """.trimIndent()
        )

        val found = commandLogStore.findByIdempotency("client-1", "/api/v1/orders/modify", idempotencyKey)
        assertNotNull(found)
        assertEquals(commandId, found.commandId)
        assertEquals("ModifyOrder", found.commandType)
        assertEquals(CommandLogStatus.RECEIVED, found.status)
    }

    private fun postgresDataSourceOrNull(): DataSource? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        return RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
    }
}
