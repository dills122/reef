package com.reef.stockdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors

internal data class SeedRequest(val gameSeedId: String, val symbols: List<String>, val asOf: Instant?)

data class StockDataHttpSecurityConfig(
    val apiToken: String,
    val maxRequestBodyBytes: Int,
    val maxSymbols: Int,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): StockDataHttpSecurityConfig =
            StockDataHttpSecurityConfig(
                apiToken = env["STOCK_DATA_API_TOKEN"].orEmpty().trim(),
                maxRequestBodyBytes = env["STOCK_DATA_HTTP_MAX_REQUEST_BYTES"]?.toIntOrNull()?.coerceAtLeast(1024) ?: 64 * 1024,
                maxSymbols = env["STOCK_DATA_MAX_SEED_SYMBOLS"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100,
            )
    }
}

/**
 * Minimal seed-time HTTP surface. This service is only called during game
 * creation - see docs/STOCK_DATA_SEEDING_PLAN.md "Usage Shape". Wiring a real
 * game-seeding flow to this endpoint is deferred; callers pass gameSeedId and
 * symbols explicitly for now.
 */
class StockDataHttpServer(
    private val workflow: SeedWorkflow,
    private val port: Int = 8081,
    private val security: StockDataHttpSecurityConfig = StockDataHttpSecurityConfig.fromEnv(),
) {
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/health") { exchange -> writeJson(exchange, 200, """{"status":"ok"}""") }
        server.createContext("/v1/seed-snapshots") { exchange -> handleSeedRequest(exchange) }
        server.start()
        return server
    }

    private fun handleSeedRequest(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            writeJson(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val authError = stockDataAuthError(
            authorization = exchange.requestHeaders["Authorization"]?.firstOrNull().orEmpty(),
            isLoopback = exchange.remoteAddress?.address?.isLoopbackAddress ?: false,
            security = security,
        )
        if (authError != null) {
            writeJson(exchange, authError.first, mapper.writeValueAsString(mapOf("error" to authError.second)))
            return
        }
        try {
            val request = mapper.readValue(readLimitedBody(exchange, security.maxRequestBodyBytes), SeedRequest::class.java)
            validateSeedRequest(request, security)
            val batch = workflow.seed(request.gameSeedId, request.symbols, request.asOf ?: Instant.now())
            writeJson(exchange, 200, mapper.writeValueAsString(BatchResponse.from(batch)))
        } catch (ex: IllegalArgumentException) {
            writeJson(exchange, 400, mapper.writeValueAsString(mapOf("error" to (ex.message ?: "invalid request"))))
        } catch (ex: StockDataSeedException) {
            writeJson(
                exchange,
                422,
                mapper.writeValueAsString(
                    mapOf(
                        "error" to mapOf(
                            "symbol" to ex.symbol,
                            "category" to ex.category.wireValue,
                            "message" to (ex.message ?: ""),
                        ),
                    ),
                ),
            )
        } catch (ex: Exception) {
            writeJson(exchange, 500, mapper.writeValueAsString(mapOf("error" to "internal error")))
        }
    }

    private fun readLimitedBody(exchange: HttpExchange, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = exchange.requestBody.read(buffer)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("request body too large")
            }
            out.write(buffer, 0, read)
        }
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

internal fun stockDataAuthError(
    authorization: String,
    isLoopback: Boolean,
    security: StockDataHttpSecurityConfig,
): Pair<Int, String>? {
    if (security.apiToken.isBlank()) {
        return if (isLoopback) null else 503 to "stock data API token is not configured"
    }
    return if (bearerTokenMatches(authorization, security.apiToken)) null else 401 to "unauthorized"
}

private fun bearerTokenMatches(authorization: String, expectedToken: String): Boolean {
    if (!authorization.startsWith("Bearer ")) return false
    val suppliedToken = authorization.removePrefix("Bearer ")
    return MessageDigest.isEqual(
        suppliedToken.toByteArray(Charsets.UTF_8),
        expectedToken.toByteArray(Charsets.UTF_8),
    )
}

internal fun validateSeedRequest(request: SeedRequest, security: StockDataHttpSecurityConfig) {
    require(request.gameSeedId.isNotBlank() && request.gameSeedId.length <= 128) { "gameSeedId is required" }
    require(request.symbols.isNotEmpty()) { "symbols are required" }
    require(request.symbols.size <= security.maxSymbols) { "too many symbols" }
    val symbolPattern = Regex("^[A-Z][A-Z0-9.-]{0,15}$")
    request.symbols.forEach { symbol ->
        require(symbolPattern.matches(symbol)) { "invalid symbol" }
    }
}

private data class BatchResponse(
    val gameSeedId: String,
    val asOf: Instant,
    val batchSeedHash: String,
    val snapshots: List<StockSeedSnapshot>,
) {
    companion object {
        fun from(batch: StockSeedSnapshotBatch) =
            BatchResponse(batch.gameSeedId, batch.asOf, batch.batchSeedHash, batch.snapshots)
    }
}
