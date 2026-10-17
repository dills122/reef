package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformHttpReadApiBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun defaultMemoryCanonicalProjectorUsesConfiguredCapturedSource() {
        val sources = listOf(
            InMemoryCommandCaptureStore(),
            CommandLogCommandCaptureStore(InMemoryCommandCaptureStore(), InMemoryCommandLogStore())
        )
        sources.forEach { captured ->
            val persistence = InMemoryRuntimePersistence()
            val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
            val server = PlatformHttpServer(
                port = 0, api = api, boundary = ExternalApiBoundary(),
                idempotencyStore = InMemoryIdempotencyStore(),
                idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
                commandCaptureStore = captured
            ).start()
            try {
                com.reef.platform.infrastructure.persistence.assertCanonicalRunOrderLifecycleIsolation(persistence) { commandId, payload ->
                    val route = if (JsonCodec.parseObject(payload).string("commandType") == "CancelOrder") "/orders/cancel" else "/orders/modify"
                    captured.reserveReceived("client", route, commandId, commandId, payload)
                }
            } finally {
                server.stop(0)
            }
        }
    }

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
    fun apiV1DataAvailabilityReportsReadSurfaceFreshness() {
        val server = testServer()
        try {
            val response = get(server.address.port, "/api/v1/data/availability", headers = apiReadHeaders())

            assertEquals(200, response.status)
            assertContains(response.body, "\"source\":\"venue-event-batch\"")
            assertContains(response.body, "\"name\":\"marketDataSnapshots\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/market-data/snapshots/{instrumentId}\"")
            assertContains(response.body, "\"name\":\"currentOrders\"")
            assertContains(response.body, "\"freshness\":\"dirty-tracked lifecycle projection\"")
            assertContains(response.body, "\"name\":\"tradeTape\"")
            assertContains(response.body, "\"freshness\":\"durable fact rows\"")
            assertContains(response.body, "\"name\":\"settlementFacts\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/facts/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementObligations\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/obligations/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementLedger\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/ledger/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementExceptions\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/exceptions/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementProof\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/proof/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementScore\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/score/{scenarioRunId}\"")
            assertFalse(response.body.contains("\"name\":\"arenaLeaderboard\""))
            assertFalse(response.body.contains("/api/v1/arena/leaderboard"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ReadEndpointsRequireClientPrincipal() {
        val server = testServer()
        try {
            listOf(
                "/api/v1/commands/missing-command",
                "/api/v1/data/availability",
                "/api/v1/market-data/snapshots/AAPL",
                "/api/v1/market-data/depth/AAPL",
                "/api/v1/market-data/trades/AAPL",
                "/api/v1/market-data/bars/AAPL?interval=1m&start=2026-07-16T00:00:00Z&end=2026-07-16T01:00:00Z",
                "/api/v1/orders/current?participantId=participant-1",
                "/api/v1/orders/history?participantId=participant-1",
                "/api/v1/orders/fills?participantId=participant-1",
                "/api/v1/settlement/facts/run-public-denied",
                "/api/v1/settlement/obligations/run-public-denied",
                "/api/v1/settlement/ledger/run-public-denied",
                "/api/v1/settlement/proof/run-public-denied",
                "/api/v1/settlement/score/run-public-denied"
            ).forEach { path ->
                val response = get(server.address.port, path)

                assertEquals(401, response.status, path)
                assertContains(response.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun healthzAndReadyzExposeCheapRuntimeStatus() {
        val server = testServer()
        try {
            val health = get(server.address.port, "/healthz")
            val readiness = get(server.address.port, "/readyz")

            assertEquals(200, health.status)
            assertContains(health.body, "\"status\":\"ok\"")
            assertEquals(200, readiness.status)
            assertContains(readiness.body, "\"status\":\"ok\"")
            assertContains(readiness.body, "\"internalHttpMode\":\"localonly\"")
            assertContains(readiness.body, "\"pipeline\"")
            assertContains(readiness.body, "\"commandProcessingMode\":\"sync-result\"")
            assertContains(readiness.body, "\"dependencies\"")
            assertContains(readiness.body, "\"enabledDependencies\"")
            assertContains(readiness.body, "\"dbPools\":{\"enabled\":true,\"ready\":true")
            assertContains(readiness.body, "\"streamIngress\":{\"enabled\":false,\"ready\":true")
            assertContains(readiness.body, "\"adminStore\":{\"enabled\":false,\"ready\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun readyzDegradesWhenStreamAckPipelineIsNotConfigured() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.StreamAck
        )
        try {
            val readiness = get(server.address.port, "/readyz")

            assertEquals(200, readiness.status)
            assertContains(readiness.body, "\"status\":\"degraded\"")
            assertContains(readiness.body, "\"commandProcessingMode\":\"stream-ack\"")
            assertContains(readiness.body, "\"streamAckRequired\":true")
            assertContains(readiness.body, "\"streamPipelineConfigured\":false")
            assertContains(readiness.body, "\"streamReady\":false")
            assertContains(readiness.body, "\"streamIngress\":{\"enabled\":true,\"ready\":false")
            assertContains(readiness.body, "stream-ack pipeline is not fully configured or healthy")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ParticipantOrderReadsRequireMatchingParticipantPrincipal() {
        val server = testServer()
        try {
            val denied = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val allowed = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val historyDenied = get(
                server.address.port,
                "/api/v1/orders/history?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val historyAllowed = get(
                server.address.port,
                "/api/v1/orders/history?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val fillsDenied = get(
                server.address.port,
                "/api/v1/orders/fills?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val fillsAllowed = get(
                server.address.port,
                "/api/v1/orders/fills?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val missingParticipant = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-1",
                headers = mapOf("X-Client-Id" to "client-1")
            )

            assertEquals(403, denied.status)
            assertContains(denied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"orders\"")
            assertEquals(403, historyDenied.status)
            assertContains(historyDenied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(200, historyAllowed.status)
            assertContains(historyAllowed.body, "\"orders\"")
            assertEquals(403, fillsDenied.status)
            assertContains(fillsDenied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(200, fillsAllowed.status)
            assertContains(fillsAllowed.body, "\"fills\"")
            assertEquals(403, missingParticipant.status)
            assertContains(missingParticipant.body, "\"code\":\"OBJECT_AUTH_REQUIRED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ParticipantOrderReadsUseAuthenticatedTokenScopeBeforeParticipantHeader() {
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
        val server = testServer(boundary = boundary)
        try {
            val allowed = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1"
                )
            )
            val denied = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1",
                    "X-Participant-Id" to "participant-2"
                )
            )

            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"orders\"")
            assertEquals(403, denied.status)
            assertContains(denied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyGlobalReadEndpointsRequireApiBoundaryIdentity() {
        val server = testServerWithGateway(EchoOrderEngineGateway())
        try {
            listOf(
                "/orders",
                "/orders/ord-legacy-read",
                "/orders/ord-legacy-read/events",
                "/trades",
                "/events"
            ).forEach { path ->
                val denied = get(server.address.port, path)
                val allowed = get(server.address.port, path, headers = apiReadHeaders())

                assertEquals(401, denied.status, path)
                assertContains(denied.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
                assertTrue(allowed.status == 200 || allowed.status == 404, path)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyTradeAndEventReadsApplyBoundedDefaultsAndMaximums() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveTrades((1..525).map { index ->
            TradeCreated(
                eventId = "trade-event-$index",
                tradeId = "trade-$index",
                executionId = "execution-$index",
                buyOrderId = "buy-$index",
                sellOrderId = "sell-$index",
                instrumentId = "AAPL",
                quantityUnits = "1",
                price = "10",
                currency = "USD",
                occurredAt = "2026-09-25T00:00:00Z"
            )
        })
        persistence.saveEvents((1..525).map { index ->
            RuntimeEvent(
                eventId = "event-$index",
                eventType = "OrderAccepted",
                orderId = "order-$index",
                traceId = "trace-$index",
                causationId = "command-$index",
                correlationId = "correlation-$index",
                producer = "test",
                schemaVersion = "1",
                occurredAt = "2026-09-25T00:00:00Z"
            )
        })
        val server = testServerWithGateway(EchoOrderEngineGateway(), runtimePersistence = persistence)
        try {
            listOf("/trades" to "tradeId", "/events" to "eventId").forEach { (path, field) ->
                val default = get(server.address.port, path, headers = apiReadHeaders())
                val zero = get(server.address.port, "$path?limit=0", headers = apiReadHeaders())
                val oversized = get(server.address.port, "$path?limit=9999", headers = apiReadHeaders())

                assertEquals(200, default.status)
                assertEquals(50, Regex("\\\"$field\\\"").findAll(default.body).count(), path)
                assertEquals(50, Regex("\\\"$field\\\"").findAll(zero.body).count(), path)
                assertEquals(500, Regex("\\\"$field\\\"").findAll(oversized.body).count(), path)
            }
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
    fun projectorStatusReportsConfiguredDownstreamMaintainers() {
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            runtimeRole = PlatformRuntimeRole.Projector,
            orderLifecycleProjectorEnabled = true,
            marketDataProjectorEnabled = false
        )
        try {
            val response = get(server.address.port, "/internal/projector/status")

            assertEquals(200, response.status)
            assertContains(response.body, "\"orderLifecycleProjectorEnabled\":true")
            assertContains(response.body, "\"marketDataProjectorEnabled\":false")
            assertContains(response.body, "\"projectedCount\":")
            val withoutCount = get(server.address.port, "/internal/projector/status?includeProjectedCount=false")
            assertEquals(200, withoutCount.status)
            assertFalse(withoutCount.body.contains("\"projectedCount\":"))
            assertContains(withoutCount.body, "\"lag\":")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun downstreamProjectorStatusReportsOptionalCoverageInstrumentation() {
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            runtimeRole = PlatformRuntimeRole.Projector,
            orderLifecycleProjectorEnabled = true,
            marketDataProjectorEnabled = true,
            downstreamProjectionInstrumentationEnabled = true
        )
        try {
            val lifecycle = get(server.address.port, "/internal/order-lifecycle/projector/status")
            val marketData = get(server.address.port, "/internal/market-data/projector/status")

            assertEquals(200, lifecycle.status)
            assertContains(lifecycle.body, "\"instrumentation\":{")
            assertContains(lifecycle.body, "\"enabled\":true")
            assertContains(lifecycle.body, "\"dirtyQueues\":{")
            assertContains(lifecycle.body, "\"callerStats\":{")
            assertContains(lifecycle.body, "\"coverage\":{")
            assertEquals(200, marketData.status)
            assertContains(marketData.body, "\"instrumentation\":{")
            val lightweight = get(server.address.port, "/internal/order-lifecycle/projector/status?includeDirtyCounts=false")
            assertEquals(200, lightweight.status)
            assertContains(lightweight.body, "\"orderLifecycleEmpty\":true")
            assertContains(lightweight.body, "\"marketDataEmpty\":true")
            assertFalse(lightweight.body.contains("\"orderLifecyclePending\":"))
            assertFalse(lightweight.body.contains("\"marketDataPending\":"))
        } finally {
            server.stop(0)
        }
    }
}
