package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PlatformHttpLegacyMutationGateBoundaryTest : PlatformHttpBoundaryTestSupport() {
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
        VenueEventBatchMaterializerMetrics.resetForTests()
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
            assertContains(internal.body, "\"sourcePartitions\":[]")
            assertContains(internal.body, "\"materializedSourceFrontiers\":[]")
            assertContains(internal.body, "\"checksumValidatedBatches\":0")
            assertContains(internal.body, "\"clockGuardFailures\":0")
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
    fun legacyMutationRoutesRejectRemoteSpoofedInternalMarkerWhenLocalOnly() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = EchoOrderEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val server = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            legacyMutationRoutesEnabled = true,
            internalHttpExposureMode = InternalHttpExposureMode.LocalOnly
        )
        val headers = Headers().apply {
            add("X-Reef-Internal-Route", "true")
        }

        val response = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/auth/roles",
                query = null,
                headers = headers,
                remoteAddress = "203.0.113.10",
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )
        )

        assertEquals(403, response?.status)
        assertContains(response?.body.orEmpty(), "\"error\":\"legacy mutation route requires loopback access\"")
    }

    @Test
    fun legacyMutationRoutesAllowRemoteInternalMarkerWhenExplicitlyEnabled() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = EchoOrderEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val server = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            legacyMutationRoutesEnabled = true,
            internalHttpExposureMode = InternalHttpExposureMode.Enabled
        )
        val headers = Headers().apply {
            add("X-Reef-Internal-Route", "true")
        }

        val response = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/auth/roles",
                query = null,
                headers = headers,
                remoteAddress = "203.0.113.10",
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )
        )

        assertEquals(200, response?.status)
        assertContains(response?.body.orEmpty(), "\"roleId\":\"order_trader\"")
    }

    @Test
    fun legacyMutationRoutesReturnNotFoundWhenInternalHttpDisabled() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = EchoOrderEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val server = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            legacyMutationRoutesEnabled = true,
            internalHttpExposureMode = InternalHttpExposureMode.Disabled
        )
        val headers = Headers().apply {
            add("X-Reef-Internal-Route", "true")
        }

        val response = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/auth/roles",
                query = null,
                headers = headers,
                remoteAddress = "127.0.0.1",
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )
        )

        assertEquals(404, response?.status)
        assertEquals("", response?.body)
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
}
