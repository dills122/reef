package com.reef.platform.api

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformHttpCapturedModeBoundaryTest : PlatformHttpBoundaryTestSupport() {
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
            val status = get(server.address.port, "/api/v1/commands/cmd-ack-1", headers = apiReadHeaders())

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-ack-1\"")
            assertContains(response.body, "\"status\":\"ACCEPTED\"")
            assertContains(response.body, "\"processingMode\":\"captured-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-ack-1\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(200, status.status)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"internalStatus\":\"RECEIVED\"")
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
            val status = get(server.address.port, "/api/v1/commands/cmd-risk-reject", headers = apiReadHeaders("client-risk-reject"))

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"ACCOUNT_RISK_REJECTED\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckAccountRiskReceivesSubmitEconomicsBeforeCommandLogAppend() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val accountRiskCheck = object : AccountRiskCheck {
            var lastRequest: AccountRiskCheckRequest? = null

            override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
                lastRequest = request
                return AccountRiskCheckResult(
                    decision = AccountRiskDecision.REJECT,
                    code = "ACCOUNT_RISK_MAX_NOTIONAL_EXCEEDED",
                    message = "max notional exceeded"
                )
            }
        }
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            accountRiskCheck = accountRiskCheck
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-limit",
                    "Idempotency-Key" to "idem-risk-limit"
                ),
                body = validSubmitBody("cmd-risk-limit", "trace-risk-limit", "ord-risk-limit")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-risk-limit", headers = apiReadHeaders("client-risk-limit"))

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"ACCOUNT_RISK_MAX_NOTIONAL_EXCEEDED\"")
            assertEquals("100", accountRiskCheck.lastRequest?.quantityUnits)
            assertEquals("150250000000", accountRiskCheck.lastRequest?.limitPrice)
            assertEquals("USD", accountRiskCheck.lastRequest?.currency)
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
            val status = get(server.address.port, "/api/v1/commands/cmd-breaker-reject", headers = apiReadHeaders("client-breaker-reject"))

            assertEquals(503, response.status)
            assertContains(response.body, "\"code\":\"COMMAND_CIRCUIT_BREAKER_TRIPPED\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
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
}
