package com.reef.platform.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class PlatformHttpServer(
    private val port: Int = System.getenv("PLATFORM_RUNTIME_PORT")?.toIntOrNull() ?: 8080,
    private val api: PlatformApi = PlatformApi(),
    private val boundary: ExternalApiBoundary,
    private val idempotencyStore: IdempotencyStore,
    private val idempotencyRetentionPolicy: IdempotencyRetentionPolicy
) {
    constructor(
        port: Int = System.getenv("PLATFORM_RUNTIME_PORT")?.toIntOrNull() ?: 8080,
        api: PlatformApi = PlatformApi()
    ) : this(
        port = port,
        api = api,
        boundary = defaultBoundary().first,
        idempotencyStore = defaultBoundary().second,
        idempotencyRetentionPolicy = defaultBoundary().third
    )

    fun start(): HttpServer {
        val backlog = System.getenv("PLATFORM_HTTP_BACKLOG")?.toIntOrNull()?.coerceAtLeast(64) ?: 1024
        val server = HttpServer.create(InetSocketAddress(port), backlog)
        val workerThreads = System.getenv("PLATFORM_HTTP_THREADS")?.toIntOrNull()?.coerceAtLeast(4) ?: 64
        server.executor = Executors.newFixedThreadPool(workerThreads)

        server.createContext("/health") { exchange ->
            writeJson(exchange, 200, api.health())
        }

        server.createContext("/orders/submit") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                writeJson(exchange, 200, api.submitOrder(body))
            } catch (ex: Exception) {
                writeJson(exchange, 503, """{"error":"runtime unavailable","message":"${JsonFields.escape(ex.message ?: "unknown")}"}""")
            }
        }

        server.createContext("/api/v1/orders/submit") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val violation = boundary.checkWrite(exchange.requestHeaders, "/api/v1/orders/submit")
            if (violation != null) {
                writeJson(exchange, violation.status, boundary.toErrorJson(violation, correlationId(exchange)))
                return@createContext
            }
            val cached = readIdempotentResult(exchange, "/api/v1/orders/submit")
            if (cached != null) {
                writeJson(exchange, cached.status, cached.payload)
                return@createContext
            }
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                val payload = api.submitOrder(body)
                rememberIdempotentResult(exchange, "/api/v1/orders/submit", 200, payload)
                writeJson(exchange, 200, payload)
            } catch (ex: Exception) {
                writeJson(exchange, 503, """{"error":"runtime unavailable","message":"${JsonFields.escape(ex.message ?: "unknown")}"}""")
            }
        }

        server.createContext("/reference/instruments") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    writeJson(exchange, 200, api.createInstrument(body))
                }
                "GET" -> writeJson(exchange, 200, api.instruments())
                else -> {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                }
            }
        }

        server.createContext("/reference/participants") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    writeJson(exchange, 200, api.createParticipant(body))
                }
                "GET" -> writeJson(exchange, 200, api.participants())
                else -> {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                }
            }
        }

        server.createContext("/reference/accounts") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    writeJson(exchange, 200, api.createAccount(body))
                }
                "GET" -> writeJson(exchange, 200, api.accounts())
                else -> {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                }
            }
        }

        server.createContext("/orders/cancel") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                writeJson(exchange, 200, api.cancelOrder(body))
            } catch (ex: Exception) {
                writeJson(exchange, 503, """{"error":"runtime unavailable","message":"${JsonFields.escape(ex.message ?: "unknown")}"}""")
            }
        }

        server.createContext("/api/v1/orders/cancel") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val violation = boundary.checkWrite(exchange.requestHeaders, "/api/v1/orders/cancel")
            if (violation != null) {
                writeJson(exchange, violation.status, boundary.toErrorJson(violation, correlationId(exchange)))
                return@createContext
            }
            val cached = readIdempotentResult(exchange, "/api/v1/orders/cancel")
            if (cached != null) {
                writeJson(exchange, cached.status, cached.payload)
                return@createContext
            }
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                val payload = api.cancelOrder(body)
                rememberIdempotentResult(exchange, "/api/v1/orders/cancel", 200, payload)
                writeJson(exchange, 200, payload)
            } catch (ex: Exception) {
                writeJson(exchange, 503, """{"error":"runtime unavailable","message":"${JsonFields.escape(ex.message ?: "unknown")}"}""")
            }
        }

        server.createContext("/orders/modify") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                writeJson(exchange, 200, api.modifyOrder(body))
            } catch (ex: Exception) {
                writeJson(exchange, 503, """{"error":"runtime unavailable","message":"${JsonFields.escape(ex.message ?: "unknown")}"}""")
            }
        }

        server.createContext("/api/v1/orders/modify") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val violation = boundary.checkWrite(exchange.requestHeaders, "/api/v1/orders/modify")
            if (violation != null) {
                writeJson(exchange, violation.status, boundary.toErrorJson(violation, correlationId(exchange)))
                return@createContext
            }
            val cached = readIdempotentResult(exchange, "/api/v1/orders/modify")
            if (cached != null) {
                writeJson(exchange, cached.status, cached.payload)
                return@createContext
            }
            try {
                val body = exchange.requestBody.bufferedReader().readText()
                val payload = api.modifyOrder(body)
                rememberIdempotentResult(exchange, "/api/v1/orders/modify", 200, payload)
                writeJson(exchange, 200, payload)
            } catch (ex: Exception) {
                writeJson(exchange, 503, """{"error":"runtime unavailable","message":"${JsonFields.escape(ex.message ?: "unknown")}"}""")
            }
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
            val limit = queryLimit(exchange, 0)
            if (limit > 0) {
                writeJson(exchange, 200, api.recentTrades(limit))
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
            val limit = queryLimit(exchange, 0)
            if (limit > 0) {
                writeJson(exchange, 200, api.recentEvents(limit))
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
        return server
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun queryLimit(exchange: HttpExchange, defaultValue: Int): Int {
        val query = exchange.requestURI.query ?: return defaultValue
        val values = query.split("&")
        for (value in values) {
            val parts = value.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "limit") {
                return parts[1].toIntOrNull() ?: defaultValue
            }
        }
        return defaultValue
    }

    private fun correlationId(exchange: HttpExchange): String {
        return exchange.requestHeaders["X-Correlation-Id"]?.firstOrNull() ?: ""
    }

    private fun readIdempotentResult(exchange: HttpExchange, route: String): IdempotencyResult? {
        val clientId = boundary.clientId(exchange.requestHeaders) ?: return null
        val idempotencyKey = boundary.idempotencyKey(exchange.requestHeaders) ?: return null
        return idempotencyStore.find(clientId, route, idempotencyKey)
    }

    private fun rememberIdempotentResult(exchange: HttpExchange, route: String, status: Int, payload: String) {
        val clientId = boundary.clientId(exchange.requestHeaders) ?: return
        val idempotencyKey = boundary.idempotencyKey(exchange.requestHeaders) ?: return
        idempotencyStore.save(
            clientId,
            route,
            idempotencyKey,
            IdempotencyResult(status, payload),
            idempotencyRetentionPolicy.ttlFor(route)
        )
    }
}

private fun defaultBoundary(): Triple<ExternalApiBoundary, IdempotencyStore, IdempotencyRetentionPolicy> {
    val hooks = defaultBoundaryHooks()
    return Triple(
        ExternalApiBoundary(
            authHook = hooks.authHook,
            rateLimitHook = hooks.rateLimitHook
        ),
        hooks.idempotencyStore,
        hooks.idempotencyRetentionPolicy
    )
}
