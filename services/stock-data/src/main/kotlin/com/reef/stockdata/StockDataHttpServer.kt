package com.reef.stockdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors

private data class SeedRequest(val gameSeedId: String, val symbols: List<String>, val asOf: Instant?)

/**
 * Minimal seed-time HTTP surface. This service is only called during game
 * creation - see docs/STOCK_DATA_SEEDING_PLAN.md "Usage Shape". Wiring a real
 * game-seeding flow to this endpoint is deferred; callers pass gameSeedId and
 * symbols explicitly for now.
 */
class StockDataHttpServer(
    private val workflow: SeedWorkflow,
    private val port: Int = 8081,
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
        try {
            val request = mapper.readValue(exchange.requestBody.readBytes(), SeedRequest::class.java)
            val batch = workflow.seed(request.gameSeedId, request.symbols, request.asOf ?: Instant.now())
            writeJson(exchange, 200, mapper.writeValueAsString(BatchResponse.from(batch)))
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
            writeJson(exchange, 500, mapper.writeValueAsString(mapOf("error" to (ex.message ?: "internal error"))))
        }
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
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
