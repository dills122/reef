package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandCaptureStoreTest {
    @Test
    fun defaultCaptureStoreLeavesCommandLogDisabledByDefault() {
        val store = defaultCommandCaptureStore(CommandProcessingMode.SyncResult) { key ->
            when (key) {
                "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> "inmemory"
                else -> null
            }
        }

        assertIs<InMemoryCommandCaptureStore>(store)
    }

    @Test
    fun defaultCaptureStoreWrapsCaptureStoreWhenCommandLogModeIsInMemory() {
        val store = defaultCommandCaptureStore(CommandProcessingMode.SyncResult) { key ->
            when (key) {
                "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> "inmemory"
                "EXTERNAL_API_COMMAND_LOG_MODE" -> "inmemory"
                else -> null
            }
        }

        assertIs<CommandLogCommandCaptureStore>(store)
    }

    @Test
    fun defaultCaptureStoreIsNoopWhenCaptureModeDisabled() {
        for (mode in listOf("disabled", "off", "none")) {
            val store = defaultCommandCaptureStore(CommandProcessingMode.SyncResult) { key ->
                when (key) {
                    "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> mode
                    else -> null
                }
            }
            assertIs<NoopCommandCaptureStore>(store)
        }
    }

    @Test
    fun defaultCaptureStoreCommandLogDisabledLeavesCaptureStoreUnwrapped() {
        for (mode in listOf("disabled", "off", "none")) {
            val store = defaultCommandCaptureStore(CommandProcessingMode.SyncResult) { key ->
                when (key) {
                    "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> "inmemory"
                    "EXTERNAL_API_COMMAND_LOG_MODE" -> mode
                    else -> null
                }
            }
            assertIs<InMemoryCommandCaptureStore>(store)
        }
    }

    @Test
    fun inMemoryCaptureStoreExposesCommandStatusLookup() {
        val store = InMemoryCommandCaptureStore()

        store.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            correlationId = "corr-1",
            requestPayload = """{"commandId":"cmd-1","participantId":"participant-1"}"""
        )
        store.markCompleted(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            responseStatus = 200,
            responsePayload = """{"accepted":{"orderId":"order-1"}}"""
        )

        val status = store.findCommandStatus("cmd-1")

        assertNotNull(status)
        assertEquals("cmd-1", status.commandId)
        assertEquals("client-1", status.clientId)
        assertEquals(CommandLogStatus.COMPLETED, status.status)
        assertEquals(CommandProcessingMode.SyncResult, status.processingMode)
        assertEquals("participant-1", status.participantId)
        assertEquals("command_capture", status.source)
    }

    @Test
    fun defaultCaptureStoreRejectsUnsupportedCommandLogMode() {
        assertFailsWith<IllegalArgumentException> {
            defaultCommandCaptureStore(CommandProcessingMode.SyncResult) { key ->
                when (key) {
                    "EXTERNAL_API_COMMAND_CAPTURE_MODE" -> "inmemory"
                    "EXTERNAL_API_COMMAND_LOG_MODE" -> "not-a-real-mode"
                    else -> null
                }
            }
        }
    }

    @Test
    fun commandLogCaptureAppendsReceivedCommandBeforeDelegating() {
        val delegate = RecordingCommandLogCaptureStore()
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = delegate,
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine,
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
                  "actorId":"actor-1",
                  "runId":"run-1",
                  "runKind":"stress",
                  "scenarioId":"scenario-1"
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
        assertEquals("run-1", record.runId)
        assertEquals("stress", record.runKind)
        assertEquals("scenario-1", record.scenarioId)
        assertEquals(Instant.parse("2026-06-04T14:00:00Z"), record.receivedAt)
        assertEquals(CommandLogStatus.RECEIVED, record.status)
        assertEquals(CommandProcessingMode.CapturedSyncEngine, captureStore.findCommandStatus("cmd-1")?.processingMode)
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
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = delegate,
            commandLogStore = commandLogStore
        )

        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            correlationId = "corr-1",
            requestPayload = """{"commandId":"cmd-1"}"""
        )
        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-2",
            correlationId = "corr-2",
            requestPayload = """{"commandId":"cmd-2"}"""
        )

        captureStore.markProcessing(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1"
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
        assertEquals(CommandLogStatus.COMPLETED, commandLogStore.findByCommandId("cmd-1")?.status)
        assertEquals(200, commandLogStore.findByCommandId("cmd-1")?.responseStatus)
        assertEquals(CommandLogStatus.FAILED, commandLogStore.findByCommandId("cmd-2")?.status)
        assertEquals("runtime unavailable", commandLogStore.findByCommandId("cmd-2")?.lastError)
    }

    @Test
    fun commandLogStoreReturnsPendingCommandsInReceivedOrder() {
        val commandLogStore = InMemoryCommandLogStore()
        val first = CommandLogRecord(
            commandId = "cmd-pending-1",
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-pending-1",
            traceId = "trace-pending-1",
            correlationId = "trace-pending-1",
            actorId = "actor-1",
            commandType = "SubmitOrder",
            receivedAt = Instant.parse("2026-06-04T14:00:00Z"),
            payloadJson = "{}"
        )
        val second = first.copy(
            commandId = "cmd-pending-2",
            idempotencyKey = "idem-pending-2",
            receivedAt = Instant.parse("2026-06-04T14:00:01Z")
        )
        commandLogStore.append(second)
        commandLogStore.append(first)
        commandLogStore.markProcessing("cmd-pending-2")

        val pending = commandLogStore.findByStatus(CommandLogStatus.RECEIVED, 10)

        assertEquals(listOf("cmd-pending-1"), pending.map { it.commandId })
    }

    @Test
    fun asyncCommandProcessorCompletesCapturedAckCommand() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
        persistence.saveRole(RoleDefinition("order_trader", listOf(Permission.ORDER_SUBMIT)))
        persistence.saveActorRoleBinding(ActorRoleBinding("actor-1", "order_trader"))
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            clock = { Instant.parse("2026-06-04T14:00:00Z") }
        )
        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-async-1",
            correlationId = "trace-async-1",
            requestPayload = validAsyncSubmitPayload("cmd-async-1", "trace-async-1", "ord-async-1")
        )
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = AsyncAcceptedEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val processor = AsyncCommandProcessor(
            queue = captureStore,
            api = api,
            batchSize = 10,
            pollIntervalMs = 1L
        )

        assertEquals(1, processor.processOnce())

        val status = captureStore.findCommandStatus("cmd-async-1")
        assertNotNull(status)
        assertEquals(CommandLogStatus.COMPLETED, status.status)
        assertEquals(200, status.responseStatus)
        assertTrue(status.responsePayloadJson.contains("\"accepted\""))
        assertNotNull(persistence.acceptedOrder("ord-async-1"))
    }

    @Test
    fun asyncCommandProcessorPersistsSubmitBatchBeforeCompletingCommands() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
        persistence.saveRole(RoleDefinition("order_trader", listOf(Permission.ORDER_SUBMIT)))
        persistence.saveActorRoleBinding(ActorRoleBinding("actor-1", "order_trader"))
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            clock = { Instant.parse("2026-06-04T14:00:00Z") }
        )
        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-async-batch-1",
            correlationId = "trace-async-batch-1",
            requestPayload = validAsyncSubmitPayload("cmd-async-batch-1", "trace-async-batch-1", "ord-async-batch-1")
        )
        captureStore.captureReceived(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-async-batch-2",
            correlationId = "trace-async-batch-2",
            requestPayload = validAsyncSubmitPayload("cmd-async-batch-2", "trace-async-batch-2", "ord-async-batch-2")
        )
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = AsyncAcceptedEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val processor = AsyncCommandProcessor(
            queue = captureStore,
            api = api,
            batchSize = 10,
            pollIntervalMs = 1L
        )

        assertEquals(2, processor.processOnce())

        val first = captureStore.findCommandStatus("cmd-async-batch-1")
        val second = captureStore.findCommandStatus("cmd-async-batch-2")
        assertEquals(CommandLogStatus.COMPLETED, first?.status)
        assertEquals(CommandLogStatus.COMPLETED, second?.status)
        assertNotNull(persistence.acceptedOrder("ord-async-batch-1"))
        assertNotNull(persistence.acceptedOrder("ord-async-batch-2"))
        assertEquals(2, persistence.acceptedOrders().size)
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

private fun validAsyncSubmitPayload(commandId: String, traceId: String, orderId: String): String {
    return """
        {
          "commandId":"$commandId",
          "traceId":"$traceId",
          "causationId":"",
          "correlationId":"$traceId",
          "actorId":"actor-1",
          "occurredAt":"2026-06-04T14:00:00Z",
          "orderId":"$orderId",
          "instrumentId":"AAPL",
          "participantId":"participant-1",
          "accountId":"account-1",
          "side":"BUY",
          "orderType":"LIMIT",
          "quantityUnits":"100",
          "limitPrice":"150250000000",
          "currency":"USD",
          "timeInForce":"DAY"
        }
    """.trimIndent()
}

private class AsyncAcceptedEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-06-04T14:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        error("cancel not expected")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        error("modify not expected")
    }
}
