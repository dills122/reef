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

        server.createContext("/orders/cancel") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val body = exchange.requestBody.bufferedReader().readText()
            writeJson(exchange, 200, api.cancelOrder(body))
        }

        server.createContext("/orders/modify") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val body = exchange.requestBody.bufferedReader().readText()
            writeJson(exchange, 200, api.modifyOrder(body))
        }

        server.createContext("/orders/") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }

            val path = exchange.requestURI.path.removePrefix("/orders/")
            if (path.endsWith("/events")) {
                val orderId = path.removeSuffix("/events").trimEnd('/')
                writeJson(exchange, 200, api.orderEvents(orderId))
                return@createContext
            }

            val orderId = path.trimEnd('/')
            val response = api.order(orderId)
            val status = if (response.contains("\"error\":\"order not found\"")) 404 else 200
            writeJson(exchange, status, response)
        }

        server.createContext("/orders") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            writeJson(exchange, 200, api.orders())
        }

        server.createContext("/trades") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            writeJson(exchange, 200, api.trades())
        }

        server.createContext("/events") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            writeJson(exchange, 200, api.events())
        }

        server.createContext("/traces/") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }

            val path = exchange.requestURI.path.removePrefix("/traces/")
            if (!path.endsWith("/events")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }

            val traceId = path.removeSuffix("/events").trimEnd('/')
            writeJson(exchange, 200, api.traceEvents(traceId))
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
