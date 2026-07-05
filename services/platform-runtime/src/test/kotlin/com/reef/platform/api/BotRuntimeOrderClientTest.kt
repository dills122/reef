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
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BotRuntimeOrderClientTest {
    @Test
    fun sendRequiresBoundaryClientIdentity() {
        val client = BotRuntimeOrderClient(testPlatform())

        val response = client.send(
            BotRuntimeOrderCommand(
                route = "/api/v1/orders/submit",
                headers = mapOf("Idempotency-Key" to "idem-bot-1"),
                body = validSubmitBody()
            )
        )

        assertEquals(401, response.status)
        assertContains(response.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
    }

    @Test
    fun sendUsesSameBoundaryCaptureAndIdempotencyPathAsOrderApi() {
        val captureStore = RecordingBotCommandCaptureStore()
        val idempotencyStore = RecordingBotIdempotencyStore()
        val client = BotRuntimeOrderClient(
            testPlatform(
                captureStore = captureStore,
                idempotencyStore = idempotencyStore
            )
        )

        val response = client.send(
            BotRuntimeOrderCommand(
                route = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "bot-client-1",
                    "Idempotency-Key" to "idem-bot-1"
                ),
                body = validSubmitBody()
            )
        )

        assertEquals(200, response.status)
        assertEquals("cmd-bot-1", response.commandId)
        assertEquals(1, captureStore.receivedCalls)
        assertEquals(1, captureStore.completedCalls)
        assertEquals(1, idempotencyStore.findCalls)
        assertEquals(1, idempotencyStore.saveCalls)
        assertContains(captureStore.lastReceivedPayload, "\"botId\":\"bot-1\"")
    }

    @Test
    fun sendAppliesBotAwareAccountRiskBeforeCapture() {
        val captureStore = RecordingBotCommandCaptureStore()
        val client = BotRuntimeOrderClient(
            testPlatform(
                captureStore = captureStore,
                accountRiskCheck = object : AccountRiskCheck {
                    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
                        return if (request.botId == "bot-1") {
                            AccountRiskCheckResult(AccountRiskDecision.DISABLED_BOT)
                        } else {
                            AccountRiskCheckResult(AccountRiskDecision.ALLOW)
                        }
                    }
                }
            )
        )

        val response = client.send(
            BotRuntimeOrderCommand(
                route = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "bot-client-1",
                    "Idempotency-Key" to "idem-bot-1"
                ),
                body = validSubmitBody()
            )
        )

        assertEquals(403, response.status)
        assertContains(response.body, "\"code\":\"BOT_DISABLED\"")
        assertEquals(0, captureStore.receivedCalls)
    }

    @Test
    fun sendPersistsBotRunMetadataInCommandLogCapture() {
        val commandLogStore = InMemoryCommandLogStore()
        val client = BotRuntimeOrderClient(
            testPlatform(
                captureStore = CommandLogCommandCaptureStore(
                    delegate = NoopCommandCaptureStore(),
                    commandLogStore = commandLogStore
                )
            )
        )

        val response = client.send(
            BotRuntimeOrderCommand(
                route = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "bot:bot-1",
                    "Idempotency-Key" to "idem-bot-1"
                ),
                body = validSubmitBody()
            )
        )

        assertEquals(200, response.status)
        val record = commandLogStore.findByIdempotency("bot:bot-1", "/api/v1/orders/submit", "idem-bot-1")
        assertNotNull(record)
        assertEquals("cmd-bot-1", record.commandId)
        assertEquals("bot:bot-1", record.clientId)
        assertEquals("bot-1", record.actorId)
        assertEquals("SubmitOrder", record.commandType)
        assertEquals("run-bot-1", record.runId)
        assertEquals("scenario", record.runKind)
        assertEquals("scenario-bot-1", record.scenarioId)
        assertEquals(CommandLogStatus.COMPLETED, record.status)
    }

    @Test
    fun streamAckBotOrderCanBeWorkerProcessedProjectedAndReplayed() {
        StreamCommandWorkerMetrics.resetForTests()
        CanonicalProjectionMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        seedOrderAuthorization(persistence, "bot-1")
        val gateway = CountingBotEngineGateway()
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val streamStore = InMemoryStreamCommandIntakeStore()
        val publisher = RecordingBotStreamCommandPublisher()
        val platform = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = CommandLogCommandCaptureStore(
                delegate = NoopCommandCaptureStore(),
                commandLogStore = InMemoryCommandLogStore(),
                commandProcessingMode = CommandProcessingMode.StreamAck
            ),
            streamCommandIntakeStore = streamStore,
            streamCommandPublisher = publisher,
            streamCommandConfig = StreamCommandConfig(partitionCount = 1),
            commandProcessingMode = CommandProcessingMode.StreamAck
        )
        val client = BotRuntimeOrderClient(platform)

        val response = client.send(
            BotRuntimeOrderCommand(
                route = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "bot:bot-1",
                    "Idempotency-Key" to "idem-bot-stream-1"
                ),
                body = validSubmitBody(commandId = "cmd-bot-stream-1", orderId = "ord-bot-stream-1")
            )
        )

        assertEquals(202, response.status)
        assertContains(response.body, "\"status\":\"ACCEPTED\"")
        val envelope = publisher.published.single()
        assertEquals("cmd-bot-stream-1", envelope.commandId)
        assertEquals("bot:bot-1", envelope.clientId)
        assertEquals("bot-1", envelope.botId)
        assertEquals("run-bot-1", envelope.runId)
        assertEquals("session-1", envelope.venueSessionId)
        assertEquals(11L, streamStore.findByCommandId("cmd-bot-stream-1")?.streamSequence)

        val worker = StreamCommandWorker(
            source = FixedBotStreamCommandSource(
                BotStreamCommandDelivery(
                    envelope = envelope,
                    streamSequence = 11L
                )
            ),
            api = api,
            partition = envelope.partition
        )
        val processed = worker.processOnce()

        assertEquals(1, processed)
        assertEquals(1, gateway.submitCalls)
        assertEquals(1, persistence.canonicalSubmitOutcomes().size)
        val canonical = persistence.canonicalSubmitOutcomes().single()
        assertEquals("cmd-bot-stream-1", canonical.commandId)
        assertEquals("run-bot-1", canonical.runId)
        assertEquals("session-1", canonical.venueSessionId)
        assertEquals("AAPL", canonical.instrumentId)
        assertEquals("ord-bot-stream-1", canonical.outcome.result.accepted?.orderId)
        assertEquals("accepted", canonical.resultStatus)
        assertEquals(11L, canonical.streamSequence)

        val projector = CanonicalProjectionWorker(
            api = api,
            projectionName = "runtime-normalized-submit-bot-e2e",
            batchSize = 10
        )
        val projected = projector.processOnce()
        val replayed = projector.processOnce()

        assertEquals(1, projected)
        assertEquals(0, replayed)
        assertNotNull(persistence.acceptedOrder("ord-bot-stream-1"))
        assertEquals(0, persistence.projectionStatus("runtime-normalized-submit-bot-e2e").lag)
    }

    private fun testPlatform(
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck()
    ): PlatformHttpServer {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        seedOrderAuthorization(persistence, "bot-1")
        return PlatformHttpServer(
            port = 0,
            api = PlatformApi(
                OrderApplicationService(
                    engineGateway = AcceptedBotEngineGateway(),
                    runtimePersistence = persistence
                )
            ),
            boundary = ExternalApiBoundary(),
            accountRiskCheck = accountRiskCheck,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore
        )
    }

    private fun seedOrderReferenceData(persistence: InMemoryRuntimePersistence) {
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
    }

    private fun seedOrderAuthorization(persistence: InMemoryRuntimePersistence, actorId: String) {
        persistence.saveRole(
            RoleDefinition(
                "order_trader",
                listOf(Permission.ORDER_SUBMIT, Permission.ORDER_CANCEL, Permission.ORDER_MODIFY)
            )
        )
        persistence.saveActorRoleBinding(ActorRoleBinding(actorId, "order_trader"))
    }

    private fun validSubmitBody(commandId: String = "cmd-bot-1", orderId: String = "ord-bot-1"): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"trace-$commandId",
              "causationId":"",
              "correlationId":"corr-$commandId",
              "actorId":"bot-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY",
              "botId":"bot-1",
              "botVersion":"1.0.0",
              "runId":"run-bot-1",
              "runKind":"scenario",
              "scenarioId":"scenario-bot-1",
              "venueSessionId":"session-1"
            }
        """.trimIndent()
    }
}

private class AcceptedBotEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

private class CountingBotEngineGateway : EngineGateway {
    var submitCalls: Int = 0

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls++
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = command.occurredAt
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        error("not used")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        error("not used")
    }
}

private class RecordingBotStreamCommandPublisher : StreamCommandPublisher {
    val published = mutableListOf<StreamCommandEnvelope>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        published.add(envelope)
        return StreamPublishAck("REEF_COMMANDS", 10L + published.size)
    }
}

private class FixedBotStreamCommandSource(
    private val delivery: StreamCommandDelivery
) : StreamCommandSource {
    private var delivered = false

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        if (delivered) return emptyList()
        delivered = true
        return listOf(delivery)
    }
}

private class BotStreamCommandDelivery(
    private val envelope: StreamCommandEnvelope,
    override val streamSequence: Long,
    override val deliveredCount: Long = 1
) : StreamCommandDelivery {
    var ackCalls = 0
    var nakCalls = 0
    var termCalls = 0

    override val subject: String = envelope.subject
    override val payloadJson: String = envelope.payloadJson

    override fun ack() {
        ackCalls++
    }

    override fun nak() {
        nakCalls++
    }

    override fun term() {
        termCalls++
    }
}

private class RecordingBotCommandCaptureStore : CommandCaptureStore {
    var receivedCalls: Int = 0
    var completedCalls: Int = 0
    var failedCalls: Int = 0
    var lastReceivedPayload: String = ""

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        receivedCalls++
        lastReceivedPayload = requestPayload
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

private class RecordingBotIdempotencyStore : IdempotencyStore {
    var findCalls: Int = 0
    var saveCalls: Int = 0

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        findCalls++
        return null
    }

    override fun save(
        clientId: String,
        route: String,
        idempotencyKey: String,
        result: IdempotencyResult,
        ttlClass: IdempotencyTtlClass
    ) {
        saveCalls++
    }
}
