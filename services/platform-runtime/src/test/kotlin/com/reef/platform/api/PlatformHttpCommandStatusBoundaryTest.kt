package com.reef.platform.api

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.persistence.CanonicalSubmitOutcome
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformHttpCommandStatusBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun apiV1CommandStatusEndpointReturnsCapturedCommandState() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-status-1"
                ),
                body = validSubmitBody("cmd-status-1", "trace-status-1", "ord-status-1")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-status-1", headers = apiReadHeaders())

            assertEquals(200, response.status)
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-1\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"captured-sync-engine\"")
            assertContains(status.body, "\"responseStatus\":200")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointPrefersCanonicalCommandOutcome() {
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-canonical",
                commandId = "cmd-status-canonical",
                resultStatus = "accepted"
            )
        )
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine,
            runtimePersistence = persistence
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-canonical"
                ),
                body = validSubmitBody("cmd-status-canonical", "trace-status-canonical", "ord-cmd-status-canonical")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-status-canonical", headers = apiReadHeaders())

            assertEquals(200, submit.status)
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-canonical\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"batchId\":\"batch-status-canonical\"")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"instrumentId\":\"AAPL\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"orderId\":\"ord-cmd-status-canonical\"")
            assertContains(status.body, "\"clientId\":\"client-1\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsCompactCanonicalCommandResult() {
        val persistence = InMemoryRuntimePersistence()
        persistence.appendCanonicalSubmitOutcomes(
            listOf(
                CanonicalSubmitOutcome(
                    runId = "run-status-compact",
                    venueSessionId = "session-status-compact",
                    partitionId = 7,
                    partitionSequence = 42L,
                    streamName = "stream-status-compact",
                    streamSequence = 12L,
                    commandId = "cmd-status-compact",
                    idempotencyKey = "idem-status-compact",
                    payloadHash = "hash-status-compact",
                    instrumentId = "MSFT",
                    commandType = "SubmitOrder",
                    resultStatus = "accepted",
                    rejectCode = "",
                    acceptedAt = "2026-07-04T14:30:00.000Z",
                    completedAt = "2026-07-04T14:30:00.000Z",
                    engineShardId = "shard-compact",
                    outcome = PersistableSubmitOutcome(
                        commandId = "cmd-status-compact",
                        result = SubmitOrderResult(
                            accepted = EngineOrderAccepted(
                                eventId = "evt-status-compact",
                                orderId = "ord-status-compact",
                                engineOrderId = "eng-status-compact",
                                occurredAt = "2026-07-04T14:30:00.000Z"
                            )
                        ),
                        acceptedOrder = PersistedOrder(
                            orderId = "ord-status-compact",
                            engineOrderId = "eng-status-compact",
                            instrumentId = "MSFT",
                            participantId = "participant-compact",
                            accountId = "account-compact",
                            side = "BUY",
                            orderType = "LIMIT",
                            quantityUnits = "1",
                            limitPrice = "100000000",
                            currency = "USD",
                            timeInForce = "DAY",
                            acceptedAt = "2026-07-04T14:30:00.000Z"
                        ),
                        lifecycleEvents = emptyList()
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            runtimePersistence = persistence
        )
        try {
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-compact",
                headers = apiReadHeaders(participantId = "participant-compact")
            )

            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-compact\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"source\":\"canonical_result\"")
            assertContains(status.body, "\"partition\":7")
            assertContains(status.body, "\"streamSequence\":12")
            assertContains(status.body, "\"commandStream\":\"stream-status-compact\"")
            assertContains(status.body, "\"shardId\":\"shard-compact\"")
            assertContains(status.body, "\"instrumentId\":\"MSFT\"")
            assertContains(status.body, "\"participantId\":\"participant-compact\"")
            assertContains(status.body, "\"orderId\":\"ord-status-compact\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsEventPublishedForDurableVenueEventBatchBeforeCanonicalOutcome() {
        val persistence = InMemoryRuntimePersistence()
        persistence.recordVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-event-published",
                commandId = "cmd-status-event-published",
                resultStatus = "accepted"
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            runtimePersistence = persistence
        )
        try {
            val status = get(server.address.port, "/api/v1/commands/cmd-status-event-published", headers = apiReadHeaders())

            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-event-published\"")
            assertContains(status.body, "\"status\":\"EVENT_PUBLISHED\"")
            assertContains(status.body, "\"internalStatus\":\"PROCESSING\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"responseStatus\":202")
            assertContains(status.body, "\"canonicalMaterialized\":false")
            assertContains(status.body, "\"batchId\":\"batch-status-event-published\"")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"source\":\"event_batch\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusRejectsMismatchedReadPrincipal() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-status-scope"
                ),
                body = validSubmitBody("cmd-status-scope", "trace-status-scope", "ord-status-scope")
            )
            val deniedClient = get(
                server.address.port,
                "/api/v1/commands/cmd-status-scope",
                headers = apiReadHeaders(clientId = "client-2")
            )
            val deniedParticipant = get(
                server.address.port,
                "/api/v1/commands/cmd-status-scope",
                headers = apiReadHeaders(participantId = "participant-2")
            )
            val missingParticipant = get(
                server.address.port,
                "/api/v1/commands/cmd-status-scope",
                headers = mapOf("X-Client-Id" to "client-1")
            )

            assertEquals(200, submit.status)
            assertEquals(403, deniedClient.status)
            assertContains(deniedClient.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(403, deniedParticipant.status)
            assertContains(deniedParticipant.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(403, missingParticipant.status)
            assertContains(missingParticipant.body, "\"code\":\"OBJECT_AUTH_REQUIRED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesStreamReferenceScope() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-status-stream-scope"
                ),
                body = validSubmitBody("cmd-status-stream-scope", "trace-status-stream-scope", "ord-status-stream-scope", extra = streamRoutingExtra())
            )
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-stream-scope",
                headers = apiReadHeaders()
            )

            assertEquals(202, submit.status)
            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"clientId\":\"client-1\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"source\":\"stream_reference\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesHeaderScopeForStreamCancelReference() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.StreamAck
        )
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val extra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
            val cancel = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1",
                    "Idempotency-Key" to "idem-status-stream-cancel-scope"
                ),
                body = validCancelBody("cmd-status-stream-cancel-scope", "trace-status-stream-cancel-scope", "ord-status-stream-cancel-scope", extra = extra)
            )
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-stream-cancel-scope",
                headers = apiReadHeaders()
            )

            assertEquals(202, cancel.status)
            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"commandType\":\"CancelOrder\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"source\":\"stream_reference\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesStreamReferenceScopeForCanonicalCancelOutcome() {
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-stream-cancel-canonical",
                commandId = "cmd-status-stream-cancel-canonical",
                resultStatus = "accepted",
                commandType = "CancelOrder",
                orderId = "ord-status-stream-cancel-canonical",
                resultPayloadJson = """{"resultType":"accepted","acceptedOrder":null,"commandId":"cmd-status-stream-cancel-canonical","orderId":"ord-status-stream-cancel-canonical"}"""
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore().also { intake ->
                val envelope = when (val envelopeResult = StreamCommandEnvelopeBuilder.fromRequest(
                    clientId = "client-1",
                    participantId = "participant-1",
                    route = "/api/v1/orders/cancel",
                    idempotencyKey = "idem-status-stream-cancel-canonical",
                    body = validCancelBody(
                        "cmd-status-stream-cancel-canonical",
                        "trace-status-stream-cancel-canonical",
                        "ord-status-stream-cancel-canonical",
                        extra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
                    )
                )) {
                    is EitherBoundaryError.Envelope -> envelopeResult.envelope
                    is EitherBoundaryError.Error -> error(envelopeResult.error.message)
                }
                val reference = envelope.reference("REEF_COMMANDS", streamSequence = 7001)
                assertTrue(intake.reserve(envelope, reference) is StreamCommandReservation.Reserved)
                assertTrue(intake.markPublished(envelope.scope, envelope.idempotencyKey, 7001))
            },
            streamCommandPublisher = RecordingStreamCommandPublisher(),
            runtimePersistence = persistence
        )
        try {
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-stream-cancel-canonical",
                headers = apiReadHeaders()
            )

            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"commandType\":\"CancelOrder\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"clientId\":\"client-1\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesAuthenticatedTokenScopeForParticipantStatus() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            participantIds = setOf("participant-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1",
                    "Idempotency-Key" to "idem-status-token-scope"
                ),
                body = validSubmitBody("cmd-status-token-scope", "trace-status-token-scope", "ord-status-token-scope")
            )
            val allowed = get(
                server.address.port,
                "/api/v1/commands/cmd-status-token-scope",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1"
                )
            )
            val denied = get(
                server.address.port,
                "/api/v1/commands/cmd-status-token-scope",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1",
                    "X-Participant-Id" to "participant-2"
                )
            )

            assertEquals(200, submit.status)
            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"commandId\":\"cmd-status-token-scope\"")
            assertEquals(200, denied.status)
            assertContains(denied.body, "\"commandId\":\"cmd-status-token-scope\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointExposesCanonicalRejectMetadata() {
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-rejected",
                commandId = "cmd-status-rejected",
                resultStatus = "rejected",
                rejectCode = "ORDER_NOT_FOUND"
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            runtimePersistence = persistence
        )
        try {
            val status = get(server.address.port, "/api/v1/commands/cmd-status-rejected", headers = apiReadHeaders())

            assertEquals(200, status.status)
            assertContains(status.body, "\"status\":\"REJECTED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"resultStatus\":\"rejected\"")
            assertContains(status.body, "\"rejectCode\":\"ORDER_NOT_FOUND\"")
            assertContains(status.body, "\"responseStatus\":422")
            assertContains(status.body, "\"resultPayloadJson\":\"{\\\"rejected\\\":{\\\"code\\\":\\\"ORDER_NOT_FOUND\\\",\\\"participantId\\\":\\\"participant-1\\\",\\\"orderId\\\":\\\"ord-cmd-status-rejected\\\"}}\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedModesRequireCommandStatusLookup() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-no-status"
                ),
                body = validSubmitBody("cmd-no-status", "trace-no-status", "ord-no-status")
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"command status unavailable\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsNotFoundForUnknownCommand() {
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore()
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = get(server.address.port, "/api/v1/commands/missing-command", headers = apiReadHeaders())

            assertEquals(404, response.status)
            assertContains(response.body, "\"error\":\"command not found\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun syncResultCommandCaptureExposesCommandStatusEndpoint() {
        val captureStore = InMemoryCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val submitted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-sync-status",
                    "Idempotency-Key" to "idem-sync-status"
                ),
                body = validSubmitBody("cmd-sync-status", "trace-sync-status", "ord-sync-status")
            )
            val ready = get(server.address.port, "/readyz")
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-sync-status",
                headers = apiReadHeaders("client-sync-status")
            )

            assertEquals(200, submitted.status)
            assertEquals(200, ready.status)
            assertContains(ready.body, "\"commandStatusLookup\":true")
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-sync-status\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"sync-result\"")
            assertContains(status.body, "\"source\":\"command_capture\"")
        } finally {
            server.stop(0)
        }
    }
}
