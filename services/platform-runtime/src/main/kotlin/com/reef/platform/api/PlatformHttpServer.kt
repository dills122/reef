package com.reef.platform.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class PlatformHttpServer(
    private val api: PlatformApi = PlatformApi()
) {
    fun start() {
        val port = System.getenv("PLATFORM_RUNTIME_PORT")?.toIntOrNull() ?: 8080
        val server = HttpServer.create(InetSocketAddress(port), 0)

        server.createContext("/health") { exchange ->
            writeJson(exchange, 200, api.health())
        }

        server.createContext("/orders/submit") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }

            val body = exchange.requestBody.bufferedReader().readText()
            writeJson(exchange, 200, api.submitOrder(body))
        }

        server.start()
        println("platform-runtime listening on :$port")
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
