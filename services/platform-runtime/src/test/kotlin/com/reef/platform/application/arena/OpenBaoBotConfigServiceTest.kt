package com.reef.platform.application.arena

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenBaoBotConfigServiceTest {
    @Test
    fun storesOpaqueConfigJsonAndReturnsAuthorizedStatusWithConfig() {
        val writes = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/auth/approle/login") { exchange ->
            json(exchange, 200, """{"auth":{"client_token":"bao-token"}}""")
        }
        server.createContext("/v1/secret/data/bots/dills122/sample-bot") { exchange ->
            assertEquals("bao-token", exchange.requestHeaders.getFirst("X-Vault-Token"))
            if (exchange.requestMethod == "POST") {
                writes += exchange.requestBody.bufferedReader().use { it.readText() }
                json(exchange, 200, """{"data":{"version":2}}""")
            } else {
                json(
                    exchange,
                    200,
                    """{"data":{"data":{"config_json":"{\"apiKey\":\"secret\",\"riskLimit\":12}","updated_at":"2026-07-10T00:00:00Z","updated_by":"user-gh-1"},"metadata":{"version":2}}}"""
                )
            }
        }
        server.start()
        try {
            val service = OpenBaoBotConfigService(
                OpenBaoBotConfigServiceConfig(
                    baoAddr = "http://127.0.0.1:${server.address.port}",
                    roleId = "role-id",
                    secretId = "secret-id"
                ),
                now = { Instant.parse("2026-07-10T00:00:00Z") }
            )

            val result = service.replaceConfig("dills122", "sample-bot", """{"apiKey":"secret","riskLimit":12}""", "user-gh-1")
            val status = service.status("dills122", "sample-bot")

            assertEquals(listOf("apiKey", "riskLimit"), result.keys)
            assertTrue(writes.single().contains("config_json"))
            assertFalse(writes.single().contains(""""apiKey":"secret""""))
            assertEquals(true, status.hasConfig)
            assertEquals("secret", result.config.path("apiKey").asText())
            assertEquals(12, result.config.path("riskLimit").asInt())
            assertEquals("secret", status.config?.path("apiKey")?.asText())
            assertEquals(12, status.config?.path("riskLimit")?.asInt())
            assertEquals(listOf("apiKey", "riskLimit"), status.keys)
            assertEquals("secret/bots/dills122/sample-bot", status.secretPath)
            assertEquals(2L, status.version)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsNonObjectConfig() {
        val service = OpenBaoBotConfigService(
            OpenBaoBotConfigServiceConfig(
                baoAddr = "http://127.0.0.1:1",
                roleId = "role-id",
                secretId = "secret-id"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.replaceConfig("dills122", "sample-bot", """["secret"]""", "user-gh-1")
        }

        assertContains(error.message ?: "", "JSON object")
    }

    @Test
    fun rejectsUnsafeRootKey() {
        val service = OpenBaoBotConfigService(
            OpenBaoBotConfigServiceConfig(
                baoAddr = "http://127.0.0.1:1",
                roleId = "role-id",
                secretId = "secret-id"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.replaceConfig("dills122", "sample-bot", """{"bad/key":"secret"}""", "user-gh-1")
        }

        assertContains(error.message ?: "", "bot config key")
    }

    private fun json(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
