package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PlatformHttpServerBoundaryTest {
    @Test
    fun apiV1SubmitReturnsBoundaryErrorEnvelopeWhenClientIdMissing() {
        val server = testServer()
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf("Idempotency-Key" to "idem-1"),
                body = "{}"
            )
            assertEquals(401, response.status)
            assertContains(response.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            assertContains(response.body, "\"correlationId\":\"\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReturnsRateLimitedFromHook() {
        val server = testServer(
            boundary = ExternalApiBoundary(
                authHook = AllowAllAuthHook(),
                rateLimitHook = object : RateLimitHook {
                    override fun allow(clientId: String, route: String): BoundaryError? {
                        return BoundaryError(429, "RATE_LIMITED", "rate limit exceeded")
                    }
                }
            )
        )
        try {
            val reset = post(server.address.port, "/internal/perf/hot-path", emptyMap(), "")
            assertEquals(200, reset.status)

            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-1"
                ),
                body = "{}"
            )
            assertEquals(429, response.status)
            assertContains(response.body, "\"code\":\"RATE_LIMITED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReplaysFirstResponseForSameIdempotencyKey() {
        val server = testServerWithGateway(EchoOrderEngineGateway())
        try {
            val reset = post(server.address.port, "/internal/perf/hot-path", emptyMap(), "")
            assertEquals(200, reset.status)

            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-1"
            )
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = """
                    {
                      "commandId":"cmd-1",
                      "traceId":"trace-1",
                      "causationId":"",
                      "correlationId":"corr-1",
                      "actorId":"bot-1",
                      "occurredAt":"2026-05-22T00:00:00Z",
                      "orderId":"ord-first",
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
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = """
                    {
                      "commandId":"cmd-2",
                      "traceId":"trace-2",
                      "causationId":"",
                      "correlationId":"corr-2",
                      "actorId":"bot-2",
                      "occurredAt":"2026-05-22T00:00:01Z",
                      "orderId":"ord-second",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"200",
                      "limitPrice":"150250000001",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertEquals(first.body, second.body)
            assertContains(second.body, "\"orderId\":\"ord-first\"")

            val hotPath = get(server.address.port, "/internal/perf/hot-path")
            assertEquals(200, hotPath.status)
            assertContains(hotPath.body, "\"api.mutation.total\"")
            assertContains(hotPath.body, "\"api.parse.submitOrder\"")
            assertContains(hotPath.body, "\"runtime.submitOrder.total\"")
            assertContains(hotPath.body, "\"api.writeResponse\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitCapturesReceivedAndCompletedLifecycle() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-capture-1"
                ),
                body = """
                    {
                      "commandId":"cmd-capture-1",
                      "traceId":"trace-capture-1",
                      "causationId":"",
                      "correlationId":"corr-capture-1",
                      "actorId":"bot-capture-1",
                      "occurredAt":"2026-05-22T00:00:00Z",
                      "orderId":"ord-capture-1",
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
            )
            assertEquals(200, response.status)
            assertEquals(1, captureStore.receivedCalls)
            assertEquals(1, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
            assertTrue(captureStore.lastReceivedPayload.contains("\"commandId\":\"cmd-capture-1\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyMutationRoutesRejectWhenInternalGateDisabled() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            legacyMutationRoutesEnabled = false
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/orders/submit",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = validSubmitBody("cmd-legacy-disabled", "trace-legacy-disabled", "ord-legacy-disabled")
            )
            val reference = post(
                port = server.address.port,
                path = "/reference/instruments",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
            )
            val role = post(
                port = server.address.port,
                path = "/auth/roles",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )

            assertEquals(403, submit.status)
            assertEquals(403, reference.status)
            assertEquals(403, role.status)
            assertContains(submit.body, "\"error\":\"legacy mutation route disabled\"")
            assertContains(reference.body, "\"error\":\"legacy mutation route disabled\"")
            assertContains(role.body, "\"error\":\"legacy mutation route disabled\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun workerRoleDoesNotExposePublicCommandIntake() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            runtimeRole = PlatformRuntimeRole.Worker
        )
        try {
            val internal = get(server.address.port, "/internal/stream-ack/worker/stats")
            val publicSubmit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-worker-role"
                ),
                body = validSubmitBody("cmd-worker-role", "trace-worker-role", "ord-worker-role", extra = streamRoutingExtra())
            )

            assertEquals(200, internal.status)
            assertEquals(404, publicSubmit.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun materializerRoleExposesOnlyInternalMaterializerStats() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            runtimeRole = PlatformRuntimeRole.Materializer,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            venueEventMaterializerEnabled = true
        )
        try {
            val internal = get(server.address.port, "/internal/venue-event-materializer/stats")
            val publicSubmit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-materializer-role"
                ),
                body = validSubmitBody("cmd-materializer-role", "trace-materializer-role", "ord-materializer-role", extra = streamRoutingExtra())
            )

            assertEquals(200, internal.status)
            assertContains(internal.body, "\"role\":\"materializer\"")
            assertContains(internal.body, "\"source\":\"kafka\"")
            assertEquals(404, publicSubmit.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyMutationRoutesRequireInternalMarkerWhenGateEnabled() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            legacyMutationRoutesEnabled = true
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/orders/submit",
                headers = emptyMap(),
                body = validSubmitBody("cmd-legacy-marker", "trace-legacy-marker", "ord-legacy-marker")
            )
            val reference = post(
                port = server.address.port,
                path = "/reference/instruments",
                headers = emptyMap(),
                body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
            )
            val role = post(
                port = server.address.port,
                path = "/auth/roles",
                headers = emptyMap(),
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )

            assertEquals(403, submit.status)
            assertEquals(403, reference.status)
            assertEquals(403, role.status)
            assertContains(submit.body, "\"header\":\"X-Reef-Internal-Route\"")
            assertContains(reference.body, "\"header\":\"X-Reef-Internal-Route\"")
            assertContains(role.body, "\"header\":\"X-Reef-Internal-Route\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAuthSeedRoutesAllowExplicitOrderActors() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            seedOrderAuthorization = false
        )
        try {
            seedReferenceData(server.address.port)
            seedOrderRoleBindings(server.address.port, "bot-capture-1")

            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-auth-seeded"
                ),
                body = validSubmitBody("cmd-auth-seeded", "trace-auth-seeded", "ord-auth-seeded")
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"accepted\"")
            assertContains(response.body, "\"orderId\":\"ord-auth-seeded\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMalformedJsonBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            listOf(
                "/api/v1/orders/submit",
                "/api/v1/orders/cancel",
                "/api/v1/orders/modify"
            ).forEachIndexed { index, route ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-malformed-$index",
                        "X-Correlation-Id" to "corr-malformed-$index"
                    ),
                    body = """{"commandId":"""
                )

                assertEquals(400, response.status)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"invalid json payload\"")
                assertContains(response.body, "\"correlationId\":\"corr-malformed-$index\"")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectUnknownFieldsBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to validSubmitBody("cmd-unknown-submit", "trace-unknown-submit", "ord-unknown-submit", extra = ""","unexpected":"value""""),
                "/api/v1/orders/cancel" to validCancelBody("cmd-unknown-cancel", "trace-unknown-cancel", "ord-unknown-cancel", extra = ""","unexpected":"value""""),
                "/api/v1/orders/modify" to validModifyBody("cmd-unknown-modify", "trace-unknown-modify", "ord-unknown-modify", extra = ""","unexpected":"value"""")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-unknown-$index"
                    ),
                    body = body
                )

                assertEquals(400, response.status)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"unknown field: unexpected\"")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMissingRequiredFieldsBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to bodyWithoutField(validSubmitBody("cmd-missing-submit", "trace-missing-submit", "ord-missing-submit"), "orderId"),
                "/api/v1/orders/cancel" to bodyWithoutField(validCancelBody("cmd-missing-cancel", "trace-missing-cancel", "ord-missing-cancel"), "reason"),
                "/api/v1/orders/modify" to bodyWithoutField(validModifyBody("cmd-missing-modify", "trace-missing-modify", "ord-missing-modify"), "limitPrice")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-missing-$index"
                    ),
                    body = body
                )

                assertEquals(400, response.status, route)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"missing required field:")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsInvalidEnumValuesBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-invalid-enum"
                ),
                body = validSubmitBody("cmd-invalid-enum", "trace-invalid-enum", "ord-invalid-enum")
                    .replace("\"side\":\"BUY\"", "\"side\":\"BID\"")
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "\"message\":\"invalid side: BID\"")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsUnauthorizedActorBeforeEngineCall() {
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            seedOrderAuthorization = false
        )
        try {
            seedReferenceData(server.address.port)
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-unauthorized"
                ),
                body = validSubmitBody("cmd-unauthorized", "trace-unauthorized", "ord-unauthorized")
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"rejected\"")
            assertContains(response.body, "\"code\":\"AUTHORIZATION_ERROR\"")
            assertContains(response.body, "\"reason\":\"actorId missing permission order.submit\"")
            assertEquals(0, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReturnsRetryableErrorWhenEngineTransportFails() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = ThrowingEngineGateway(),
            captureStore = captureStore
        )
        try {
            seedReferenceData(server.address.port)
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-engine-down"
                ),
                body = validSubmitBody("cmd-engine-down", "trace-engine-down", "ord-engine-down")
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"runtime unavailable\"")
            assertFalse(response.body.contains("\"rejected\""))
            assertEquals(1, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(1, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

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
            val status = get(server.address.port, "/api/v1/commands/cmd-status-1")

            assertEquals(200, response.status)
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-1\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
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
            val status = get(server.address.port, "/api/v1/commands/cmd-status-canonical")

            assertEquals(200, submit.status)
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-canonical\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"batchId\":\"batch-status-canonical\"")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"instrumentId\":\"AAPL\"")
            assertContains(status.body, "\"orderId\":\"ord-cmd-status-canonical\"")
            assertFalse(status.body.contains("\"clientId\":\"client-1\""))
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
            val status = get(server.address.port, "/api/v1/commands/cmd-status-rejected")

            assertEquals(200, status.status)
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"resultStatus\":\"rejected\"")
            assertContains(status.body, "\"rejectCode\":\"ORDER_NOT_FOUND\"")
            assertContains(status.body, "\"responseStatus\":422")
            assertContains(status.body, "\"resultPayloadJson\":\"{\\\"rejected\\\":{\\\"code\\\":\\\"ORDER_NOT_FOUND\\\"}}\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedSyncEngineReturnsSynchronousResultAndCompletesCommandStatus() {
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
                    "Idempotency-Key" to "idem-sync-engine-1"
                ),
                body = validSubmitBody("cmd-sync-engine-1", "trace-sync-engine-1", "ord-sync-engine-1")
            )
            val record = commandLogStore.findByCommandId("cmd-sync-engine-1")

            assertEquals(200, response.status)
            assertContains(response.body, "\"orderId\":\"ord-sync-engine-1\"")
            assertEquals(CommandLogStatus.COMPLETED, record?.status)
            assertEquals(1, record?.attemptCount)
            assertEquals(200, record?.responseStatus)
            assertTrue(record?.responsePayloadJson?.contains("ord-sync-engine-1") == true)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedSyncEngineReplaysCompletedCommandForDuplicateCommandId() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            seedReferenceData(server.address.port)
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-duplicate-command-first"
                ),
                body = validSubmitBody("cmd-duplicate-command", "trace-duplicate-command-first", "ord-duplicate-first")
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-duplicate-command-second"
                ),
                body = validSubmitBody("cmd-duplicate-command", "trace-duplicate-command-second", "ord-duplicate-second")
            )

            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertContains(second.body, "\"orderId\":\"ord-duplicate-first\"")
            assertEquals(1, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedSyncEngineRejectsDuplicateInFlightCommandBeforeSecondEngineCall() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val gateway = BlockingFirstSubmitGateway()
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            seedReferenceData(server.address.port)
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-inflight-duplicate"
            )
            val first = executor.submit<HttpResponse> {
                post(
                    port = server.address.port,
                    path = "/api/v1/orders/submit",
                    headers = headers,
                    body = validSubmitBody("cmd-inflight-duplicate", "trace-inflight-first", "ord-inflight-first")
                )
            }
            assertTrue(gateway.awaitFirstSubmit())

            val duplicate = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-inflight-duplicate", "trace-inflight-second", "ord-inflight-second")
            )

            assertEquals(409, duplicate.status)
            assertContains(duplicate.body, "\"code\":\"COMMAND_ALREADY_IN_PROGRESS\"")
            assertEquals(1, gateway.submitCalls)

            gateway.release()
            assertEquals(200, first.get(5, TimeUnit.SECONDS).status)
        } finally {
            gateway.release()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun syncResultRejectsDuplicateInFlightMutationsBeforeSecondEngineCall() {
        val cases = listOf(
            Triple(
                "/api/v1/orders/submit",
                validSubmitBody("cmd-sync-inflight-submit-first", "trace-sync-inflight-submit-first", "ord-sync-inflight-submit-first"),
                validSubmitBody("cmd-sync-inflight-submit-second", "trace-sync-inflight-submit-second", "ord-sync-inflight-submit-second")
            ),
            Triple(
                "/api/v1/orders/cancel",
                validCancelBody("cmd-sync-inflight-cancel-first", "trace-sync-inflight-cancel-first", "ord-sync-inflight-cancel-first"),
                validCancelBody("cmd-sync-inflight-cancel-second", "trace-sync-inflight-cancel-second", "ord-sync-inflight-cancel-second")
            ),
            Triple(
                "/api/v1/orders/modify",
                validModifyBody("cmd-sync-inflight-modify-first", "trace-sync-inflight-modify-first", "ord-sync-inflight-modify-first"),
                validModifyBody("cmd-sync-inflight-modify-second", "trace-sync-inflight-modify-second", "ord-sync-inflight-modify-second")
            )
        )
        cases.forEachIndexed { index, (route, firstBody, secondBody) ->
            val gateway = BlockingFirstSubmitGateway()
            val server = testServerWithGateway(
                gateway = gateway,
                captureStore = InMemoryCommandCaptureStore()
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                if (route.endsWith("/submit")) {
                    seedReferenceData(server.address.port)
                }
                val headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-sync-inflight-duplicate-$index"
                )
                val first = executor.submit<HttpResponse> {
                    post(
                        port = server.address.port,
                        path = route,
                        headers = headers,
                        body = firstBody
                    )
                }
                assertTrue(gateway.awaitFirstSubmit())

                val duplicate = post(
                    port = server.address.port,
                    path = route,
                    headers = headers,
                    body = secondBody
                )

                assertEquals(409, duplicate.status)
                assertContains(duplicate.body, "\"code\":\"COMMAND_ALREADY_IN_PROGRESS\"")
                assertEquals(1, gateway.submitCalls)

                gateway.release()
                assertEquals(200, first.get(5, TimeUnit.SECONDS).status)
            } finally {
                gateway.release()
                executor.shutdownNow()
                server.stop(0)
            }
        }
    }

    @Test
    fun capturedAckReturnsAcceptedCommandWithoutExecutingEngine() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-ack-1"
                ),
                body = validSubmitBody("cmd-ack-1", "trace-ack-1", "ord-ack-1")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-ack-1")

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-ack-1\"")
            assertContains(response.body, "\"status\":\"RECEIVED\"")
            assertContains(response.body, "\"processingMode\":\"captured-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-ack-1\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(200, status.status)
            assertContains(status.body, "\"status\":\"RECEIVED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckRiskRejectsBeforeCommandLogAppend() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            accountRiskCheck = StaticAccountRiskCheck(rejectedAccounts = setOf("account-1"))
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-reject",
                    "Idempotency-Key" to "idem-risk-reject"
                ),
                body = validSubmitBody("cmd-risk-reject", "trace-risk-reject", "ord-risk-reject")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-risk-reject")

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"ACCOUNT_RISK_REJECTED\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckCircuitBreakerRejectsBeforeCommandLogAppend() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandCircuitBreakerCheck = StaticCircuitBreakerCheck("AAPL")
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-breaker-reject",
                    "Idempotency-Key" to "idem-breaker-reject"
                ),
                body = validSubmitBody("cmd-breaker-reject", "trace-breaker-reject", "ord-breaker-reject")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-breaker-reject")

            assertEquals(503, response.status)
            assertContains(response.body, "\"code\":\"COMMAND_CIRCUIT_BREAKER_TRIPPED\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun boundaryControlDiagnosticsExposeControlsDecisionsAndBreakers() {
        val accountRiskStore = RecordingAccountRiskStore()
        accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.DISABLED_BOT, "disabled")
        accountRiskStore.decisions.add(
            AccountRiskDecisionAudit(
                decisionId = "risk-decision-1",
                decidedAt = "2026-07-04T12:00:00Z",
                decision = AccountRiskDecision.DISABLED_BOT,
                code = "BOT_DISABLED",
                message = "bot is disabled",
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = "cmd-risk",
                correlationId = "corr-risk",
                actorId = "bot-1",
                participantId = "participant-1",
                accountId = "account-1",
                botId = "bot-1",
                venueSessionId = "session-1",
                instrumentId = "AAPL",
                orderId = "ord-risk"
            )
        )
        val breakerStore = RecordingCommandCircuitBreakerStore()
        breakerStore.setBreaker("INSTRUMENT", "AAPL", true, "halted")
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            accountRiskCheck = accountRiskStore,
            accountRiskControlStore = accountRiskStore,
            accountRiskDecisionLog = accountRiskStore,
            commandCircuitBreakerCheck = breakerStore,
            commandCircuitBreakerStore = breakerStore
        )
        try {
            val controls = get(server.address.port, "/internal/boundary/account-risk/controls")
            val decisions = get(server.address.port, "/internal/boundary/account-risk/decisions/recent?limit=10")
            val breakers = get(server.address.port, "/internal/boundary/circuit-breakers")

            assertEquals(200, controls.status)
            assertContains(controls.body, "\"controlsCount\":1")
            assertContains(controls.body, "\"scopeType\":\"BOT\"")
            assertContains(controls.body, "\"decision\":\"DISABLED_BOT\"")
            assertEquals(200, decisions.status)
            assertContains(decisions.body, "\"decisionsCount\":1")
            assertContains(decisions.body, "\"code\":\"BOT_DISABLED\"")
            assertContains(decisions.body, "\"commandId\":\"cmd-risk\"")
            assertEquals(200, breakers.status)
            assertContains(breakers.body, "\"breakersCount\":1")
            assertContains(breakers.body, "\"scopeType\":\"INSTRUMENT\"")
            assertContains(breakers.body, "\"tripped\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAdminAccountRiskEndpointSetsControlAndAuditsChange() {
        val accountRiskStore = RecordingAccountRiskStore()
        val persistence = InMemoryRuntimePersistence()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            accountRiskCheck = accountRiskStore,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/internal/admin/account-risk/controls",
                headers = emptyMap(),
                body = """
                    {
                      "scopeType":"bot",
                      "scopeId":"bot-1",
                      "decision":"disabled-bot",
                      "reason":"operator disabled",
                      "actorId":"ops-1",
                      "correlationId":"corr-admin-risk"
                    }
                """.trimIndent()
            )
            val controls = get(server.address.port, "/internal/boundary/account-risk/controls")
            val auditEvents = persistence.eventsForTrace("admin:ops-1")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"decision\":\"DISABLED_BOT\"")
            assertContains(controls.body, "\"scopeType\":\"BOT\"")
            assertContains(controls.body, "\"reason\":\"operator disabled\"")
            assertEquals(1, auditEvents.size)
            assertEquals("AccountRiskControlChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousDecision\":\"\"")
            assertContains(auditEvents.single().payloadJson, "\"decision\":\"DISABLED_BOT\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAdminCircuitBreakerEndpointSetsBreakerAndAuditsChange() {
        val breakerStore = RecordingCommandCircuitBreakerStore()
        val persistence = InMemoryRuntimePersistence()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            commandCircuitBreakerCheck = breakerStore,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/internal/admin/circuit-breakers",
                headers = emptyMap(),
                body = """
                    {
                      "scopeType":"instrument",
                      "scopeId":"AAPL",
                      "action":"trip",
                      "reason":"operator halt",
                      "actorId":"ops-2",
                      "correlationId":"corr-admin-breaker"
                    }
                """.trimIndent()
            )
            val breakers = get(server.address.port, "/internal/boundary/circuit-breakers")
            val auditEvents = persistence.eventsForTrace("admin:ops-2")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"tripped\":true")
            assertContains(breakers.body, "\"scopeType\":\"INSTRUMENT\"")
            assertContains(breakers.body, "\"reason\":\"operator halt\"")
            assertEquals(1, auditEvents.size)
            assertEquals("CommandCircuitBreakerChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousTripped\":false")
            assertContains(auditEvents.single().payloadJson, "\"tripped\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun accountRiskControlRejectsThenClearAllowsCapturedAckCommand() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val accountRiskStore = RecordingAccountRiskStore()
        accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.DISABLED_BOT, "disabled")
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            accountRiskCheck = accountRiskStore
        )
        try {
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-smoke",
                    "Idempotency-Key" to "idem-risk-smoke-reject"
                ),
                body = validSubmitBody(
                    "cmd-risk-smoke-reject",
                    "trace-risk-smoke-reject",
                    "ord-risk-smoke-reject",
                    extra = ",\"botId\":\"bot-1\""
                )
            )
            assertEquals(403, rejected.status)
            assertContains(rejected.body, "\"code\":\"BOT_DISABLED\"")
            assertEquals(404, get(server.address.port, "/api/v1/commands/cmd-risk-smoke-reject").status)

            accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.ALLOW, "cleared")
            val accepted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-smoke",
                    "Idempotency-Key" to "idem-risk-smoke-accept"
                ),
                body = validSubmitBody(
                    "cmd-risk-smoke-accept",
                    "trace-risk-smoke-accept",
                    "ord-risk-smoke-accept",
                    extra = ",\"botId\":\"bot-1\""
                )
            )

            assertEquals(202, accepted.status)
            assertContains(accepted.body, "\"commandId\":\"cmd-risk-smoke-accept\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(200, get(server.address.port, "/api/v1/commands/cmd-risk-smoke-accept").status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun commandCircuitBreakerRejectsThenResetAllowsCapturedAckCommand() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val breakerStore = RecordingCommandCircuitBreakerStore()
        breakerStore.setBreaker("INSTRUMENT", "AAPL", true, "halted")
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandCircuitBreakerCheck = breakerStore
        )
        try {
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-breaker-smoke",
                    "Idempotency-Key" to "idem-breaker-smoke-reject"
                ),
                body = validSubmitBody("cmd-breaker-smoke-reject", "trace-breaker-smoke-reject", "ord-breaker-smoke-reject")
            )
            assertEquals(503, rejected.status)
            assertContains(rejected.body, "\"code\":\"COMMAND_CIRCUIT_BREAKER_TRIPPED\"")
            assertEquals(404, get(server.address.port, "/api/v1/commands/cmd-breaker-smoke-reject").status)

            breakerStore.setBreaker("INSTRUMENT", "AAPL", false, "cleared")
            val accepted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-breaker-smoke",
                    "Idempotency-Key" to "idem-breaker-smoke-accept"
                ),
                body = validSubmitBody("cmd-breaker-smoke-accept", "trace-breaker-smoke-accept", "ord-breaker-smoke-accept")
            )

            assertEquals(202, accepted.status)
            assertContains(accepted.body, "\"commandId\":\"cmd-breaker-smoke-accept\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(200, get(server.address.port, "/api/v1/commands/cmd-breaker-smoke-accept").status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun asyncCommandStatsEndpointReturnsQueueDepths() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-async-stats"
                ),
                body = validSubmitBody("cmd-async-stats", "trace-async-stats", "ord-async-stats")
            )
            val stats = get(server.address.port, "/internal/commands/async/stats")

            assertEquals(202, submit.status)
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"captured-ack\"")
            assertContains(stats.body, "\"workerThreads\":1")
            assertContains(stats.body, "\"sampleMs\":100")
            assertContains(stats.body, "\"RECEIVED\":1")
            assertContains(stats.body, "\"PROCESSING\":0")
            assertContains(stats.body, "\"COMPLETED\":0")
            assertContains(stats.body, "\"FAILED\":0")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun acceptedAsyncSubmitReturnsReceiptBeforeEngineCompletes() {
        val gateway = BlockingFirstSubmitGateway()
        val server = testServerWithGateway(
            gateway = gateway,
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-accepted-async",
                    "Idempotency-Key" to "idem-accepted-async"
                ),
                body = validSubmitBody("cmd-accepted-async", "trace-accepted-async", "ord-accepted-async")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-accepted-async")

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-accepted-async\"")
            assertContains(response.body, "\"processingMode\":\"accepted-async\"")
            assertTrue(gateway.awaitFirstSubmit())
            assertEquals(200, status.status)
            assertContains(status.body, "\"processingMode\":\"accepted-async\"")
            assertTrue(status.body.contains("\"status\":\"RECEIVED\"") || status.body.contains("\"status\":\"PROCESSING\""))

            gateway.release()
            val completed = waitForCommandStatus(server.address.port, "cmd-accepted-async", "COMPLETED")
            assertContains(completed.body, "\"status\":\"COMPLETED\"")
            assertContains(completed.body, "\"responseStatus\":200")
        } finally {
            gateway.release()
            server.stop(0)
        }
    }

    @Test
    fun acceptedAsyncStatsEndpointReportsLaneDrain() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-accepted-async-stats",
                    "Idempotency-Key" to "idem-accepted-async-stats"
                ),
                body = validSubmitBody("cmd-accepted-async-stats", "trace-accepted-async-stats", "ord-accepted-async-stats")
            )
            waitForCommandStatus(server.address.port, "cmd-accepted-async-stats", "COMPLETED")
            val stats = get(server.address.port, "/internal/commands/async/stats")

            assertEquals(202, response.status)
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"accepted-async\"")
            assertContains(stats.body, "\"acceptedAsync\"")
            assertContains(stats.body, "\"enabled\":true")
            assertContains(stats.body, "\"activeLaneCount\"")
            assertContains(stats.body, "\"lanes\"")
            assertContains(stats.body, "\"received\":1")
            assertContains(stats.body, "\"completed\":1")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun nettyHotPathAcceptedAsyncSubmitStatusAndStats() {
        val server = testNettyHotPathServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val health = get(server.port, "/health")
            val response = post(
                port = server.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-accepted-async",
                    "Idempotency-Key" to "idem-netty-accepted-async"
                ),
                body = validSubmitBody("cmd-netty-accepted-async", "trace-netty-accepted-async", "ord-netty-accepted-async")
            )
            val completed = waitForCommandStatus(server.port, "cmd-netty-accepted-async", "COMPLETED")
            val stats = get(server.port, "/internal/commands/async/stats")

            assertEquals(200, health.status)
            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-netty-accepted-async\"")
            assertEquals(200, completed.status)
            assertContains(completed.body, "\"status\":\"COMPLETED\"")
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"accepted-async\"")
            assertContains(stats.body, "\"inFlightPerLane\"")
            assertContains(stats.body, "\"completed\":1")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathStreamAckPublishesThroughPartitionedPipeline() {
        val publisher = RecordingStreamCommandPublisher()
        val streamPublisher = PartitionedStreamCommandPublisher(
            delegate = publisher,
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = streamPublisher
        )
        try {
            val response = post(
                port = server.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream"
                ),
                body = validSubmitBody("cmd-netty-stream", "trace-netty-stream", "ord-netty-stream", extra = streamRoutingExtra())
            )
            val health = get(server.port, "/internal/stream-ack/health")

            assertEquals(202, response.status)
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"streamSequence\":1")
            assertEquals(1, publisher.published.size)
            assertEquals(200, health.status)
            assertContains(health.body, "\"publishMode\":\"partitioned-blocking-delegate:sync\"")
            assertContains(health.body, "\"publishLaneCount\":16")
            assertContains(health.body, "\"publishCompleted\":1")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathStreamAckPublishesModifyAndCancelRoutes() {
        val publisher = RecordingStreamCommandPublisher()
        val streamPublisher = PartitionedStreamCommandPublisher(
            delegate = publisher,
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = streamPublisher
        )
        try {
            val modifyCancelRoutingExtra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
            val modify = post(
                port = server.port,
                path = "/api/v1/orders/modify",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream-modify"
                ),
                body = validModifyBody("cmd-netty-stream-modify", "trace-netty-stream-modify", "ord-netty-stream", extra = modifyCancelRoutingExtra)
            )
            val cancel = post(
                port = server.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream-cancel"
                ),
                body = validCancelBody("cmd-netty-stream-cancel", "trace-netty-stream-cancel", "ord-netty-stream", extra = modifyCancelRoutingExtra)
            )

            assertEquals(202, modify.status, modify.body)
            assertEquals(202, cancel.status, cancel.body)
            assertContains(modify.body, "\"processingMode\":\"stream-ack\"")
            assertContains(cancel.body, "\"processingMode\":\"stream-ack\"")
            assertEquals(
                listOf("/api/v1/orders/modify", "/api/v1/orders/cancel"),
                publisher.published.map { it.route }
            )
            assertEquals(listOf("ModifyOrder", "CancelOrder"), publisher.published.map { it.commandType })
        } finally {
            server.stop()
        }
    }

    @Test
    fun commandAccountingEndpointReturnsRunScopedCounts() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-accounting"
                ),
                body = validSubmitBody("cmd-accounting", "trace-accounting", "ord-accounting")
                    .replace("\"timeInForce\":\"DAY\"", "\"timeInForce\":\"DAY\",\"runId\":\"run-1\",\"runKind\":\"stress\",\"scenarioId\":\"scenario-1\"")
            )
            val accounting = get(server.address.port, "/internal/commands/accounting?runId=run-1")

            assertEquals(202, submit.status)
            assertEquals(200, accounting.status)
            assertContains(accounting.body, "\"available\":true")
            assertContains(accounting.body, "\"runId\":\"run-1\"")
            assertContains(accounting.body, "\"accepted\":1")
            assertContains(accounting.body, "\"received\":1")
            assertContains(accounting.body, "\"active\":1")
            assertContains(accounting.body, "\"terminal\":0")
            assertContains(accounting.body, "\"accountingGap\":0")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckBackpressureRejectsNewCommandsButAllowsDuplicateReplay() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandIntakeMaxActive = 1,
            commandIntakeBackpressureSampleMs = 0
        )
        try {
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-backpressure-1"
                ),
                body = validSubmitBody("cmd-backpressure-1", "trace-backpressure-1", "ord-backpressure-1")
            )
            val duplicate = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-backpressure-1"
                ),
                body = validSubmitBody("cmd-backpressure-1", "trace-backpressure-1", "ord-backpressure-1")
            )
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-backpressure-2"
                ),
                body = validSubmitBody("cmd-backpressure-2", "trace-backpressure-2", "ord-backpressure-2")
            )

            assertEquals(202, first.status)
            assertEquals(202, duplicate.status)
            assertEquals(429, rejected.status)
            assertContains(rejected.body, "\"code\":\"COMMAND_INTAKE_BACKPRESSURE\"")
            assertEquals(1L, commandLogStore.accountingSnapshot().accepted)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun dbPoolStatsEndpointReturnsPoolList() {
        val server = testServer()
        try {
            val response = get(server.address.port, "/internal/perf/db-pools")

            assertEquals(200, response.status)
            assertContains(response.body, "\"pools\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckReplaysFirstAcceptedResponseForSameIdempotencyKey() {
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-ack-replay"
            )
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-ack-first", "trace-ack-first", "ord-ack-first")
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-ack-second", "trace-ack-second", "ord-ack-second")
            )

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertEquals(first.body, second.body)
            assertContains(second.body, "\"commandId\":\"cmd-ack-first\"")
            assertEquals(0, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckSkipsIdempotencyStoreForNewAcceptedCommand() {
        val idempotencyStore = CountingIdempotencyStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            idempotencyStore = idempotencyStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-ack-no-idempotency"
                ),
                body = validSubmitBody("cmd-ack-no-idempotency", "trace-ack-no-idempotency", "ord-ack-no-idempotency")
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-ack-no-idempotency\"")
            assertEquals(0, idempotencyStore.findCalls)
            assertEquals(0, idempotencyStore.saveCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckPublishesCommandAndReturnsAcceptedReferenceWithoutExecutingEngine() {
        val publisher = RecordingStreamCommandPublisher()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-1"
                ),
                body = validSubmitBody("cmd-stream-1", "trace-stream-1", "ord-stream-1", extra = streamRoutingExtra())
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-stream-1\"")
            assertContains(response.body, "\"status\":\"ACCEPTED\"")
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"stream\":\"REEF_COMMANDS\"")
            assertContains(response.body, "\"subject\":\"reef.cmd.v1.")
            assertContains(response.body, "\"streamSequence\":1")
            assertEquals(1, publisher.published.size)
            assertEquals("client-1|/api/v1/orders/submit|idem-stream-1", publisher.published.single().natsMessageId)
            assertEquals(0, gateway.submitCalls)

            val hotPath = get(server.address.port, "/internal/perf/hot-path")
            assertEquals(200, hotPath.status)
            assertContains(hotPath.body, "\"api.streamAck.reserve\"")
            assertContains(hotPath.body, "\"api.streamAck.publishAck\"")
            assertContains(hotPath.body, "\"api.streamAck.markPublished\"")
            assertContains(hotPath.body, "\"api.streamAck.total\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRiskDisabledBotRejectsBeforeReserveOrPublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher,
            accountRiskCheck = StaticAccountRiskCheck(disabledBots = setOf("bot-1"))
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-risk-disabled-bot"
                ),
                body = validSubmitBody("cmd-stream-risk-disabled-bot", "trace-stream-risk-disabled-bot", "ord-stream-risk-disabled-bot", extra = streamRoutingExtra())
            )

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"BOT_DISABLED\"")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-risk-disabled-bot"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckReplaysSameIdempotencyKeyAndBodyWithoutRepublishing() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-replay"
            )
            val body = validSubmitBody("cmd-stream-replay", "trace-stream-replay", "ord-stream-replay", extra = streamRoutingExtra())
            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            val second = post(server.address.port, "/api/v1/orders/submit", headers, body)

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertEquals(first.body, second.body)
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsSameIdempotencyKeyWithDifferentBody() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-conflict"
            )
            val first = post(
                server.address.port,
                "/api/v1/orders/submit",
                headers,
                validSubmitBody("cmd-stream-conflict", "trace-stream-conflict", "ord-stream-conflict", extra = streamRoutingExtra())
            )
            val second = post(
                server.address.port,
                "/api/v1/orders/submit",
                headers,
                validSubmitBody("cmd-stream-conflict-2", "trace-stream-conflict-2", "ord-stream-conflict-2", extra = streamRoutingExtra())
            )

            assertEquals(202, first.status)
            assertEquals(409, second.status)
            assertContains(second.body, "\"code\":\"IDEMPOTENCY_PAYLOAD_CONFLICT\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRequiresRoutingMetadataBeforePublish() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-missing-routing"
                ),
                body = validCancelBody("cmd-stream-missing-routing", "trace-stream-missing-routing", "ord-stream-missing-routing")
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"STREAM_ROUTING_METADATA_REQUIRED\"")
            assertEquals(0, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckReturnsUnavailableWhenPublishAckFails() {
        val publisher = RecordingStreamCommandPublisher(fail = true)
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-publish-fail"
                ),
                body = validSubmitBody("cmd-stream-publish-fail", "trace-stream-publish-fail", "ord-stream-publish-fail", extra = streamRoutingExtra())
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"stream command publish unavailable\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckHealthEndpointReturnsStreamSnapshot() {
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = RecordingStreamCommandPublisher(),
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = "REEF_COMMANDS",
                    messageCount = 3,
                    byteCount = 512,
                    maxBytes = 1024,
                    storageUtilization = 0.5,
                    publishAckLastMs = 7,
                    publishAckMaxMs = 11
                )
            )
        )
        try {
            val response = get(server.address.port, "/internal/stream-ack/health")

            assertEquals(200, response.status)
            assertContains(response.body, "\"available\":true")
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"stream\":\"REEF_COMMANDS\"")
            assertContains(response.body, "\"messages\":3")
            assertContains(response.body, "\"storageUtilization\":0.5")
            assertContains(response.body, "\"publishAckLastMs\":7")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckBackpressureCachesStreamHealthWithinSampleWindow() {
        val publisher = RecordingStreamCommandPublisher()
        val healthCheck = CountingStreamCommandHealthCheck(
            StreamCommandHealthSnapshot(
                available = true,
                streamName = "REEF_COMMANDS",
                byteCount = 100,
                maxBytes = 1000,
                storageUtilization = 0.1
            )
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = healthCheck,
            streamCommandBackpressureSampleMs = 10_000L
        )
        try {
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-cache-1"
                ),
                body = validSubmitBody("cmd-stream-health-cache-1", "trace-stream-health-cache-1", "ord-stream-health-cache-1", extra = streamRoutingExtra())
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-cache-2"
                ),
                body = validSubmitBody("cmd-stream-health-cache-2", "trace-stream-health-cache-2", "ord-stream-health-cache-2", extra = streamRoutingExtra())
            )

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertEquals(2, publisher.published.size)
            assertEquals(1, healthCheck.calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsBeforePublishWhenStreamHealthUnavailable() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = false,
                    streamName = "REEF_COMMANDS",
                    error = "stream not found"
                )
            )
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-down"
                ),
                body = validSubmitBody("cmd-stream-health-down", "trace-stream-health-down", "ord-stream-health-down", extra = streamRoutingExtra())
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"code\":\"STREAM_COMMAND_STREAM_UNAVAILABLE\"")
            assertEquals(0, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsBeforePublishWhenStreamStorageIsOverThreshold() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = "REEF_COMMANDS",
                    byteCount = 950,
                    maxBytes = 1000,
                    storageUtilization = 0.95
                )
            ),
            streamCommandMaxStorageUtilization = 0.90
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-storage-full"
                ),
                body = validSubmitBody("cmd-stream-storage-full", "trace-stream-storage-full", "ord-stream-storage-full", extra = streamRoutingExtra())
            )

            assertEquals(429, response.status)
            assertContains(response.body, "\"code\":\"STREAM_COMMAND_BACKPRESSURE\"")
            assertEquals(0, publisher.published.size)
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
            val response = get(server.address.port, "/api/v1/commands/missing-command")

            assertEquals(404, response.status)
            assertContains(response.body, "\"error\":\"command not found\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsOversizedBodyBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-too-large"
                ),
                body = """{"payload":"${"x".repeat(1024 * 1024)}"}"""
            )
            assertEquals(413, response.status)
            assertContains(response.body, "\"error\":\"request body too large\"")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitBlocksClientAfterRejectRateThreshold() {
        val captureStore = RecordingCommandCaptureStore()
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 60,
            blockSeconds = 120,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            clock = { java.time.Instant.ofEpochSecond(100) }
        )
        val server = testServerWithGateway(
            gateway = StaticRejectedEngineGateway("INVALID_STATE", "invalid transition"),
            captureStore = captureStore,
            abuseProtectionHook = hook
        )
        try {
            val headers = mapOf("X-Client-Id" to "client-1")
            seedReferenceData(server.address.port)
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-breaker-1"),
                body = validSubmitBody("cmd-breaker-1", "trace-breaker-1", "ord-breaker-1")
            )
            assertEquals(200, first.status)
            assertContains(first.body, "\"rejected\"")
            assertContains(first.body, "\"code\":\"INVALID_STATE\"")

            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-breaker-2"),
                body = validSubmitBody("cmd-breaker-2", "trace-breaker-2", "ord-breaker-2")
            )
            assertEquals(200, second.status)
            assertContains(second.body, "\"code\":\"INVALID_STATE\"")

            val third = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-breaker-3"),
                body = validSubmitBody("cmd-breaker-3", "trace-breaker-3", "ord-breaker-3")
            )
            assertEquals(429, third.status)
            assertContains(third.body, "\"code\":\"ABUSE_BLOCKED\"")
            assertEquals(3, captureStore.receivedCalls)
            assertEquals(2, captureStore.completedCalls)
            assertEquals(1, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAbuseStatsEndpointReturnsCountersAndConfig() {
        var now = java.time.Instant.ofEpochSecond(300)
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 60,
            blockSeconds = 30,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            routePolicies = mapOf(
                "/api/v1/orders/submit" to RejectRatePolicy(1, 60, 30)
            ),
            clock = { now }
        )
        val server = testServerWithGateway(
            gateway = StaticRejectedEngineGateway("INVALID_STATE", "invalid transition"),
            abuseProtectionHook = hook
        )
        try {
            seedReferenceData(server.address.port)
            val headers = mapOf("X-Client-Id" to "client-stats")
            post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-stats-1"),
                body = validSubmitBody("cmd-stats-1", "trace-stats-1", "ord-stats-1")
            )
            post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-stats-2"),
                body = validSubmitBody("cmd-stats-2", "trace-stats-2", "ord-stats-2")
            )
            val blocked = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-stats-3"),
                body = validSubmitBody("cmd-stats-3", "trace-stats-3", "ord-stats-3")
            )
            assertEquals(429, blocked.status)

            val statsResponse = get(server.address.port, "/internal/boundary/abuse/stats")
            assertEquals(200, statsResponse.status)
            assertContains(statsResponse.body, "\"mode\":\"reject-rate\"")
            assertContains(statsResponse.body, "\"enabled\":true")
            assertContains(statsResponse.body, "\"trackedRoutes\":[\"/api/v1/orders/submit\"]")
            assertContains(statsResponse.body, "\"routePolicyOverrides\":{\"/api/v1/orders/submit\":\"1/60/30\"}")
            assertContains(statsResponse.body, "\"trips\":1")
            assertContains(statsResponse.body, "\"activeBlockedClients\":1")

            now = now.plusSeconds(40)
            assertFalse(hook.allow("client-stats", "/api/v1/orders/submit")?.code == "ABUSE_BLOCKED")
        } finally {
            server.stop(0)
        }
    }

    data class HttpResponse(val status: Int, val body: String)

    private fun testServer(boundary: ExternalApiBoundary = ExternalApiBoundary()): com.sun.net.httpserver.HttpServer {
        return testServerWithGateway(StaticAcceptedEngineGateway(), boundary = boundary)
    }

    private fun testServerWithGateway(
        gateway: EngineGateway,
        runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.Api,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
        accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
        commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        legacyMutationRoutesEnabled: Boolean = true,
        seedOrderAuthorization: Boolean = true,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        commandIntakeBackpressureSampleMs: Long = 100L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        streamCommandIntakeStore: StreamCommandIntakeStore? = null,
        streamCommandPublisher: StreamCommandPublisher? = null,
        streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
        streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
        streamCommandMaxStorageUtilization: Double = 0.95,
        streamCommandBackpressureSampleMs: Long = 100L,
        venueEventMaterializerEnabled: Boolean = false,
        runtimePersistence: InMemoryRuntimePersistence = InMemoryRuntimePersistence()
    ): com.sun.net.httpserver.HttpServer {
        val persistence = runtimePersistence
        seedOrderReferenceData(persistence)
        if (seedOrderAuthorization) {
            seedOrderAuthorization(
                persistence,
                "bot-capture-1",
                "bot-1",
                "bot-2"
            )
        }
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        return PlatformHttpServer(
            port = 0,
            runtimeRole = runtimeRole,
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            accountRiskCheck = accountRiskCheck,
            accountRiskControlStore = accountRiskControlStore,
            accountRiskDecisionLog = accountRiskDecisionLog,
            commandCircuitBreakerCheck = commandCircuitBreakerCheck,
            commandCircuitBreakerStore = commandCircuitBreakerStore,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            streamCommandIntakeStore = streamCommandIntakeStore,
            streamCommandPublisher = streamCommandPublisher,
            streamCommandHealthCheck = streamCommandHealthCheck,
            streamCommandConfig = streamCommandConfig,
            streamCommandMaxStorageUtilization = streamCommandMaxStorageUtilization,
            streamCommandBackpressureSampleMs = streamCommandBackpressureSampleMs,
            venueEventMaterializerEnabled = venueEventMaterializerEnabled,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
            legacyMutationRoutesEnabled = legacyMutationRoutesEnabled
        ).start()
    }

    private fun testNettyHotPathServerWithGateway(
        gateway: EngineGateway,
        runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.Api,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
        accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
        commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        commandIntakeBackpressureSampleMs: Long = 100L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        streamCommandIntakeStore: StreamCommandIntakeStore? = null,
        streamCommandPublisher: StreamCommandPublisher? = null,
        streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
        streamCommandConfig: StreamCommandConfig = StreamCommandConfig()
    ): RunningPlatformNettyServer {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        seedOrderAuthorization(
            persistence,
            "bot-capture-1",
            "bot-1",
            "bot-2"
        )
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val delegate = PlatformHttpServer(
            port = 0,
            runtimeRole = runtimeRole,
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            accountRiskCheck = accountRiskCheck,
            accountRiskControlStore = accountRiskControlStore,
            accountRiskDecisionLog = accountRiskDecisionLog,
            commandCircuitBreakerCheck = commandCircuitBreakerCheck,
            commandCircuitBreakerStore = commandCircuitBreakerStore,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            streamCommandIntakeStore = streamCommandIntakeStore,
            streamCommandPublisher = streamCommandPublisher,
            streamCommandHealthCheck = streamCommandHealthCheck,
            streamCommandConfig = streamCommandConfig,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
            legacyMutationRoutesEnabled = true
        )
        return PlatformNettyHotPathServer(
            delegate = delegate,
            port = 0,
            applicationThreads = 4
        ).start()
    }

    private fun post(port: Int, path: String, headers: Map<String, String>, body: String): HttpResponse {
        val connection = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val text = stream.bufferedReader().readText()
        return HttpResponse(connection.responseCode, text)
    }

    private fun get(port: Int, path: String): HttpResponse {
        val connection = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val text = stream.bufferedReader().readText()
        return HttpResponse(connection.responseCode, text)
    }

    private fun waitForCommandStatus(port: Int, commandId: String, status: String): HttpResponse {
        var last = get(port, "/api/v1/commands/$commandId")
        repeat(50) {
            if (last.status == 200 && last.body.contains("\"status\":\"$status\"")) {
                return last
            }
            Thread.sleep(20)
            last = get(port, "/api/v1/commands/$commandId")
        }
        return last
    }

    private fun validSubmitBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
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
              "timeInForce":"DAY"$extra
            }
        """.trimIndent()
    }

    private fun validCancelBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "reason":"test"$extra
            }
        """.trimIndent()
    }

    private fun validModifyBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "quantityUnits":"100",
              "limitPrice":"150250000001"$extra
            }
        """.trimIndent()
    }

    private fun streamRoutingExtra(): String {
        return ",\"runId\":\"run-1\",\"venueSessionId\":\"session-1\",\"clientOrderId\":\"clord-1\",\"botId\":\"bot-1\",\"botVersion\":\"v1\""
    }

    private fun venueEventBatch(
        batchId: String,
        commandId: String,
        resultStatus: String,
        rejectCode: String = ""
    ): VenueEventBatchFact {
        val resultPayload = if (resultStatus == "rejected") {
            """{"rejected":{"code":"$rejectCode"}}"""
        } else {
            """{"accepted":{"eventId":"evt-$commandId"}}"""
        }
        return VenueEventBatchFact(
            batchId = batchId,
            shardId = "engine-0",
            partition = 7,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 7001,
            lastSequence = 7001,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = "checksum-$batchId",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = commandId,
                    commandType = "SubmitOrder",
                    streamSequence = 7001,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-$commandId",
                    instrumentId = "AAPL",
                    orderId = "ord-$commandId",
                    resultStatus = resultStatus,
                    rejectCode = rejectCode,
                    resultPayloadJson = resultPayload
                )
            )
        )
    }

    private fun bodyWithoutField(body: String, field: String): String {
        val withoutField = body
            .lines()
            .filterNot { it.trimStart().startsWith("\"$field\":") }
            .joinToString("\n")
        return Regex(""",(\s*})""").replace(withoutField) { match ->
            match.groupValues[1]
        }
    }

    private fun seedReferenceData(port: Int) {
        post(
            port = port,
            path = "/reference/instruments",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
        )
        post(
            port = port,
            path = "/reference/participants",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"participantId":"participant-1","name":"Participant 1"}"""
        )
        post(
            port = port,
            path = "/reference/accounts",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"accountId":"account-1","participantId":"participant-1"}"""
        )
    }

    private fun seedOrderRoleBindings(port: Int, vararg actorIds: String) {
        post(
            port = port,
            path = "/auth/roles",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"roleId":"order_trader","permissions":"order.submit,order.cancel,order.modify"}"""
        )
        actorIds.forEach { actorId ->
            post(
                port = port,
                path = "/auth/actor-roles",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"actorId":"$actorId","roleId":"order_trader"}"""
            )
        }
    }

    private fun seedOrderReferenceData(persistence: InMemoryRuntimePersistence) {
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
    }

    private fun seedOrderAuthorization(persistence: InMemoryRuntimePersistence, vararg actorIds: String) {
        persistence.saveRole(
            RoleDefinition(
                "order_trader",
                listOf(Permission.ORDER_SUBMIT, Permission.ORDER_CANCEL, Permission.ORDER_MODIFY)
            )
        )
        actorIds.forEach { actorId ->
            persistence.saveActorRoleBinding(ActorRoleBinding(actorId, "order_trader"))
        }
    }
}

private class RecordingCommandCaptureStore : CommandCaptureStore {
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

    override fun markProcessing(
        clientId: String,
        route: String,
        idempotencyKey: String
    ) {
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

private class CountingIdempotencyStore : IdempotencyStore {
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

private class RecordingStreamCommandPublisher(
    private val fail: Boolean = false
) : StreamCommandPublisher {
    val published = mutableListOf<StreamCommandEnvelope>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        published.add(envelope)
        if (fail) {
            error("publish ack timeout")
        }
        return StreamPublishAck("REEF_COMMANDS", published.size.toLong())
    }
}

private class FixedStreamCommandHealthCheck(
    private val snapshot: StreamCommandHealthSnapshot
) : StreamCommandHealthCheck {
    override fun snapshot(): StreamCommandHealthSnapshot = snapshot
}

private class CountingStreamCommandHealthCheck(
    private val snapshot: StreamCommandHealthSnapshot
) : StreamCommandHealthCheck {
    val calls = AtomicInteger(0)

    override fun snapshot(): StreamCommandHealthSnapshot {
        calls.incrementAndGet()
        return snapshot
    }
}

private class StaticAcceptedEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-1",
                orderId = "ord-1",
                engineOrderId = "eng-1",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )
}

private class EchoOrderEngineGateway : EngineGateway {
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

private class CountingEngineGateway(
    private val delegate: EngineGateway
) : EngineGateway {
    var submitCalls: Int = 0

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls++
        return delegate.submitOrder(command)
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return delegate.cancelOrder(command)
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return delegate.modifyOrder(command)
    }
}

private class BlockingFirstSubmitGateway : EngineGateway {
    private val calls = AtomicInteger(0)
    private val firstStarted = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)

    val submitCalls: Int
        get() = calls.get()

    fun awaitFirstSubmit(): Boolean {
        return firstStarted.await(5, TimeUnit.SECONDS)
    }

    fun release() {
        releaseFirst.countDown()
    }

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val call = calls.incrementAndGet()
        if (call == 1) {
            firstStarted.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        }
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

private class StaticRejectedEngineGateway(
    private val code: String,
    private val reason: String
) : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = "evt-rejected-${command.orderId}",
                orderId = command.orderId,
                code = code,
                reason = reason,
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

private class StaticCircuitBreakerCheck(
    private val trippedInstrumentId: String
) : CommandCircuitBreakerCheck {
    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        if (request.instrumentId != trippedInstrumentId) return null
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for INSTRUMENT:${request.instrumentId}"
        )
    }
}

private class RecordingAccountRiskStore : AccountRiskCheck, AccountRiskControlStore, AccountRiskDecisionLog {
    private val controls = linkedMapOf<String, AccountRiskControl>()
    val decisions = mutableListOf<AccountRiskDecisionAudit>()

    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        val botDecision = controls["BOT|${request.botId}"]
        if (botDecision != null && botDecision.decision != AccountRiskDecision.ALLOW) {
            return AccountRiskCheckResult(botDecision.decision, message = botDecision.reason)
        }
        val accountDecision = controls["ACCOUNT|${request.accountId}"]
        if (accountDecision != null && accountDecision.decision != AccountRiskDecision.ALLOW) {
            return AccountRiskCheckResult(accountDecision.decision, message = accountDecision.reason)
        }
        return AccountRiskCheckResult(AccountRiskDecision.ALLOW)
    }

    override fun upsertControl(scopeType: String, scopeId: String, decision: AccountRiskDecision, reason: String) {
        controls["$scopeType|$scopeId"] = AccountRiskControl(
            scopeType = scopeType,
            scopeId = scopeId,
            decision = decision,
            reason = reason,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()

    override fun recentDecisions(limit: Int): List<AccountRiskDecisionAudit> = decisions.take(limit)
}

private class RecordingCommandCircuitBreakerStore : CommandCircuitBreakerStore {
    private val breakers = linkedMapOf<String, CommandCircuitBreakerState>()

    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        val breaker = breakers["INSTRUMENT|${request.instrumentId}"] ?: breakers["VENUE_SESSION|${request.venueSessionId}"] ?: breakers["GLOBAL|*"]
        if (breaker?.tripped != true) return null
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for ${breaker.scopeType}:${breaker.scopeId}"
        )
    }

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        breakers["$scopeType|$scopeId"] = CommandCircuitBreakerState(
            scopeType = scopeType,
            scopeId = scopeId,
            tripped = tripped,
            reason = reason,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> = breakers.values.toList()
}

private class ThrowingEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }
}
