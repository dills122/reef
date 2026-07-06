package com.reef.platform.application.arena

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class OpenBaoProvisioningServiceTest {
    @Test
    fun jwtLoginRejectsMalformedJsonResponse() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/auth/jwt/login") { exchange ->
            val body = """{"auth":"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val service = OpenBaoProvisioningService(
                OpenBaoProvisioningConfig("http://127.0.0.1:${server.address.port}")
            )

            val error = assertFailsWith<OpenBaoClientException> {
                service.provisionBotSecretSlice(
                    githubOidcToken = "oidc",
                    submitterIdentity = "submitter-1",
                    botId = "bot-1",
                    secretData = mapOf("key" to "value")
                )
            }

            assertContains(error.message ?: "", "OpenBao jwt login response was invalid JSON")
        } finally {
            server.stop(0)
        }
    }
}
