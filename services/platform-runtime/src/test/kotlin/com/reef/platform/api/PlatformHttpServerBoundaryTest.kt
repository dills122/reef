package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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

    data class HttpResponse(val status: Int, val body: String)

    private fun testServer(boundary: ExternalApiBoundary = ExternalApiBoundary()): com.sun.net.httpserver.HttpServer {
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = StaticAcceptedEngineGateway()
            )
        )
        return PlatformHttpServer(
            port = 0,
            api = api,
            boundary = boundary,
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy()
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
