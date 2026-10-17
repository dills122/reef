package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlatformHttpRiskControlsBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun boundaryControlDiagnosticsExposeControlsDecisionsAndBreakers() {
        val accountRiskStore = RecordingAccountRiskStore()
        accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.DISABLED_BOT, "disabled", "100", "15025000000000", "USD")
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
                orderId = "ord-risk",
                quantityUnits = "101",
                limitPrice = "150250000000",
                currency = "USD"
            )
        )
        val breakerStore = RecordingCommandCircuitBreakerStore()
        breakerStore.setBreaker("INSTRUMENT", "AAPL", true, "halted")
        val collarStore = RecordingInstrumentPriceCollarStore()
        collarStore.setCollar("AAPL", "150000000000", "151000000000", "USD", "regular band")
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            accountRiskCheck = accountRiskStore,
            accountRiskControlStore = accountRiskStore,
            accountRiskDecisionLog = accountRiskStore,
            commandCircuitBreakerCheck = breakerStore,
            commandCircuitBreakerStore = breakerStore,
            instrumentPriceCollarCheck = collarStore,
            instrumentPriceCollarStore = collarStore
        )
        try {
            val controls = get(server.address.port, "/internal/boundary/account-risk/controls")
            val decisions = get(server.address.port, "/internal/boundary/account-risk/decisions/recent?limit=10")
            val breakers = get(server.address.port, "/internal/boundary/circuit-breakers")
            val collars = get(server.address.port, "/internal/boundary/price-collars")

            assertEquals(200, controls.status)
            assertContains(controls.body, "\"controlsCount\":1")
            assertContains(controls.body, "\"scopeType\":\"BOT\"")
            assertContains(controls.body, "\"decision\":\"DISABLED_BOT\"")
            assertContains(controls.body, "\"maxQuantityUnits\":\"100\"")
            assertContains(controls.body, "\"maxNotional\":\"15025000000000\"")
            assertContains(controls.body, "\"currency\":\"USD\"")
            assertEquals(200, decisions.status)
            assertContains(decisions.body, "\"decisionsCount\":1")
            assertContains(decisions.body, "\"code\":\"BOT_DISABLED\"")
            assertContains(decisions.body, "\"commandId\":\"cmd-risk\"")
            assertContains(decisions.body, "\"quantityUnits\":\"101\"")
            assertEquals(200, breakers.status)
            assertContains(breakers.body, "\"breakersCount\":1")
            assertContains(breakers.body, "\"scopeType\":\"INSTRUMENT\"")
            assertContains(breakers.body, "\"tripped\":true")
            assertEquals(200, collars.status)
            assertContains(collars.body, "\"collarsCount\":1")
            assertContains(collars.body, "\"instrumentId\":\"AAPL\"")
            assertContains(collars.body, "\"minPrice\":\"150000000000\"")
            assertContains(collars.body, "\"maxPrice\":\"151000000000\"")
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
                headers = mapOf("X-Reef-Actor-Id" to "ops-1", "X-Correlation-Id" to "corr-admin-risk"),
                body = """
                    {
                      "scopeType":"bot",
                      "scopeId":"bot-1",
                      "decision":"disabled-bot",
                      "reason":"operator disabled",
                      "maxQuantityUnits":"100",
                      "maxNotional":"15025000000000",
                      "currency":"usd",
                      "actorId":"spoofed-ops",
                      "correlationId":"spoofed-corr"
                    }
                """.trimIndent()
            )
            val controls = get(server.address.port, "/internal/boundary/account-risk/controls")
            val auditEvents = persistence.eventsForTrace("admin:ops-1")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"decision\":\"DISABLED_BOT\"")
            assertContains(response.body, "\"maxQuantityUnits\":\"100\"")
            assertContains(response.body, "\"maxNotional\":\"15025000000000\"")
            assertContains(response.body, "\"currency\":\"USD\"")
            assertContains(controls.body, "\"scopeType\":\"BOT\"")
            assertContains(controls.body, "\"reason\":\"operator disabled\"")
            assertEquals(1, auditEvents.size)
            assertEquals("AccountRiskControlChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousDecision\":\"\"")
            assertContains(auditEvents.single().payloadJson, "\"decision\":\"DISABLED_BOT\"")
            assertContains(auditEvents.single().payloadJson, "\"maxQuantityUnits\":\"100\"")
            assertContains(auditEvents.single().payloadJson, "\"maxNotional\":\"15025000000000\"")
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
                headers = mapOf("X-Reef-Actor-Id" to "ops-2", "X-Correlation-Id" to "corr-admin-breaker"),
                body = """
                    {
                      "scopeType":"instrument",
                      "scopeId":"AAPL",
                      "action":"trip",
                      "reason":"operator halt",
                      "actorId":"spoofed-ops",
                      "correlationId":"spoofed-corr"
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
    fun internalAdminPriceCollarEndpointSetsCollarAndAuditsChange() {
        val collarStore = RecordingInstrumentPriceCollarStore()
        val persistence = InMemoryRuntimePersistence()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            instrumentPriceCollarCheck = collarStore,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/internal/admin/price-collars",
                headers = mapOf("X-Reef-Actor-Id" to "ops-3", "X-Correlation-Id" to "corr-admin-collar"),
                body = """
                    {
                      "instrumentId":"AAPL",
                      "minPrice":"150000000000",
                      "maxPrice":"151000000000",
                      "currency":"usd",
                      "reason":"regular band",
                      "actorId":"spoofed-ops",
                      "correlationId":"spoofed-corr"
                    }
                """.trimIndent()
            )
            val collars = get(server.address.port, "/internal/boundary/price-collars")
            val auditEvents = persistence.eventsForTrace("admin:ops-3")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"instrumentId\":\"AAPL\"")
            assertContains(response.body, "\"currency\":\"USD\"")
            assertContains(collars.body, "\"minPrice\":\"150000000000\"")
            assertContains(collars.body, "\"maxPrice\":\"151000000000\"")
            assertEquals(1, auditEvents.size)
            assertEquals("InstrumentPriceCollarChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousMinPrice\":\"\"")
            assertContains(auditEvents.single().payloadJson, "\"maxPrice\":\"151000000000\"")
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
        accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.DISABLED_BOT, "disabled", "", "", "")
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
            assertEquals(
                404,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-risk-smoke-reject",
                    headers = apiReadHeaders("client-risk-smoke")
                ).status
            )

            accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.ALLOW, "cleared", "", "", "")
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
            assertEquals(
                200,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-risk-smoke-accept",
                    headers = apiReadHeaders("client-risk-smoke")
                ).status
            )
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
        val rejectionLog = RecordingBoundaryRejectionLog()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandCircuitBreakerCheck = breakerStore,
            boundaryRejectionLog = rejectionLog
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
            assertEquals(
                404,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-breaker-smoke-reject",
                    headers = apiReadHeaders("client-breaker-smoke")
                ).status
            )
            assertEquals(1, rejectionLog.records.size)
            assertEquals("command-circuit-breaker", rejectionLog.records.single().guardrailType)
            assertEquals("INSTRUMENT", rejectionLog.records.single().scopeType)
            assertEquals("AAPL", rejectionLog.records.single().scopeId)

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
            assertEquals(
                200,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-breaker-smoke-accept",
                    headers = apiReadHeaders("client-breaker-smoke")
                ).status
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun instrumentPriceCollarRejectsThenClearAllowsCapturedAckCommand() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val collarStore = RecordingInstrumentPriceCollarStore()
        collarStore.setCollar("AAPL", "150000000000", "151000000000", "USD", "regular band")
        val rejectionLog = RecordingBoundaryRejectionLog()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            instrumentPriceCollarCheck = collarStore,
            boundaryRejectionLog = rejectionLog
        )
        try {
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-collar-smoke",
                    "Idempotency-Key" to "idem-collar-smoke-reject"
                ),
                body = validSubmitBody(
                    "cmd-collar-smoke-reject",
                    "trace-collar-smoke-reject",
                    "ord-collar-smoke-reject",
                    extra = ",\"limitPrice\":\"149999999999\""
                )
            )
            assertEquals(422, rejected.status)
            assertContains(rejected.body, "\"code\":\"PRICE_COLLAR_LOW\"")
            assertEquals(
                404,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-collar-smoke-reject",
                    headers = apiReadHeaders("client-collar-smoke")
                ).status
            )
            assertEquals(1, rejectionLog.records.size)
            assertEquals("instrument-price-collar", rejectionLog.records.single().guardrailType)
            assertEquals("INSTRUMENT", rejectionLog.records.single().scopeType)
            assertEquals("AAPL", rejectionLog.records.single().scopeId)
            assertEquals("cmd-collar-smoke-reject", rejectionLog.records.single().request.commandId)

            collarStore.setCollar("AAPL", "", "", "USD", "cleared")
            val accepted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-collar-smoke",
                    "Idempotency-Key" to "idem-collar-smoke-accept"
                ),
                body = validSubmitBody("cmd-collar-smoke-accept", "trace-collar-smoke-accept", "ord-collar-smoke-accept")
            )

            assertEquals(202, accepted.status)
            assertContains(accepted.body, "\"commandId\":\"cmd-collar-smoke-accept\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(
                200,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-collar-smoke-accept",
                    headers = apiReadHeaders("client-collar-smoke")
                ).status
            )
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
}
