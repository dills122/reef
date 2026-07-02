package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
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
            assertContains(stats.body, "\"RECEIVED\":1")
            assertContains(stats.body, "\"PROCESSING\":0")
            assertContains(stats.body, "\"COMPLETED\":0")
            assertContains(stats.body, "\"FAILED\":0")
        } finally {
            server.stop(0)
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
            commandIntakeMaxActive = 1
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
        return testServerWithGateway(StaticAcceptedEngineGateway(), boundary)
    }

    private fun testServerWithGateway(
        gateway: EngineGateway,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        legacyMutationRoutesEnabled: Boolean = true,
        seedOrderAuthorization: Boolean = true,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore()
    ): com.sun.net.httpserver.HttpServer {
        val persistence = InMemoryRuntimePersistence()
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
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            legacyMutationRoutesEnabled = legacyMutationRoutesEnabled
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
