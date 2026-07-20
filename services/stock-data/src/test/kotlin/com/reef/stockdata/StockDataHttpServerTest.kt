package com.reef.stockdata

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class StockDataHttpServerTest {
    @Test
    fun healthAndSeedEndpointsServeDeterministicPersistedBatch() {
        val provider = FakeStockDataProvider()
        val server = startServer(provider)
        try {
            val health = request(server, "GET", "/health")
            val methodRejected = request(server, "GET", "/v1/seed-snapshots")
            val unauthorized = request(
                server,
                "POST",
                "/v1/seed-snapshots",
                """{"gameSeedId":"game-1","symbols":["AAPL"]}"""
            )
            val seeded = request(
                server,
                "POST",
                "/v1/seed-snapshots",
                """{"gameSeedId":"game-1","symbols":["AAPL","BRK.B"],"asOf":"2026-07-08T15:00:00Z"}""",
                token = "seed-token"
            )
            val replayed = request(
                server,
                "POST",
                "/v1/seed-snapshots",
                """{"gameSeedId":"game-1","symbols":["MSFT"],"asOf":"2026-07-09T15:00:00Z"}""",
                token = "seed-token"
            )

            assertEquals(200, health.status)
            assertContains(health.body, "\"status\":\"ok\"")
            assertEquals(405, methodRejected.status)
            assertEquals(401, unauthorized.status)
            assertEquals(200, seeded.status, seeded.body)
            assertContains(seeded.body, "\"gameSeedId\":\"game-1\"")
            assertContains(seeded.body, "\"symbol\":\"AAPL\"")
            assertContains(seeded.body, "\"symbol\":\"BRK.B\"")
            assertContains(seeded.body, "\"batchSeedHash\":")
            assertEquals(200, replayed.status, replayed.body)
            assertContains(replayed.body, "\"symbol\":\"AAPL\"")
            assertEquals(1, provider.callCount)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun seedEndpointClassifiesMalformedOversizedAndProviderFailureRequests() {
        val provider = FakeStockDataProvider(unsupportedSymbols = setOf("MISSING"))
        val server = startServer(provider, maxRequestBodyBytes = 128)
        try {
            val malformed = request(
                server,
                "POST",
                "/v1/seed-snapshots",
                """{"gameSeedId":"game-1","symbols":["AAPL"]""",
                token = "seed-token"
            )
            val oversized = request(
                server,
                "POST",
                "/v1/seed-snapshots",
                """{"gameSeedId":"${"x".repeat(200)}","symbols":["AAPL"]}""",
                token = "seed-token"
            )
            val unsupported = request(
                server,
                "POST",
                "/v1/seed-snapshots",
                """{"gameSeedId":"game-2","symbols":["MISSING"]}""",
                token = "seed-token"
            )

            assertEquals(400, malformed.status, malformed.body)
            assertContains(malformed.body, "invalid request")
            assertEquals(400, oversized.status, oversized.body)
            assertContains(oversized.body, "request body too large")
            assertEquals(422, unsupported.status, unsupported.body)
            assertContains(unsupported.body, "\"category\":\"symbol_not_supported\"")
            assertContains(unsupported.body, "\"symbol\":\"MISSING\"")
        } finally {
            server.stop(0)
        }
    }

    private fun startServer(
        provider: StockDataProvider,
        maxRequestBodyBytes: Int = 4096
    ): HttpServer = StockDataHttpServer(
        workflow = SeedWorkflow(provider, InMemorySeedSnapshotRepository()),
        port = 0,
        security = StockDataHttpSecurityConfig(
            apiToken = "seed-token",
            maxRequestBodyBytes = maxRequestBodyBytes,
            maxSymbols = 10
        )
    ).start()

    private fun request(
        server: HttpServer,
        method: String,
        path: String,
        body: String? = null,
        token: String? = null
    ): Response {
        val connection = URI.create("http://127.0.0.1:${server.address.port}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.setRequestProperty("Content-Type", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val responseBody = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()
        return Response(status, responseBody)
    }

    private data class Response(val status: Int, val body: String)
}
