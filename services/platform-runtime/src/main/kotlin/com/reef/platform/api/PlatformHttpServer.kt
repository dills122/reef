package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class PlatformHttpServer(
    private val orderService: OrderApplicationService = OrderApplicationService()
) {
    fun start() {
        val port = System.getenv("PLATFORM_RUNTIME_PORT")?.toIntOrNull() ?: 8080
        val server = HttpServer.create(InetSocketAddress(port), 0)

        server.createContext("/health") { exchange ->
            writeJson(
                exchange,
                200,
                """{"service":"platform-runtime","status":"ok"}"""
            )
        }

        server.createContext("/orders/submit") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }

            val command = parseSubmitOrder(exchange)
            val result = orderService.submitOrder(command)
            writeJson(exchange, 200, toJson(result))
        }

        server.start()
        println("platform-runtime listening on :$port")
    }

    private fun parseSubmitOrder(exchange: HttpExchange): SubmitOrderCommand {
        val body = exchange.requestBody.bufferedReader().readText()
        return SubmitOrderCommand(
            commandId = extract(body, "commandId"),
            correlationId = extract(body, "correlationId"),
            actorId = extract(body, "actorId"),
            occurredAt = extract(body, "occurredAt"),
            orderId = extract(body, "orderId"),
            instrumentId = extract(body, "instrumentId"),
            participantId = extract(body, "participantId"),
            accountId = extract(body, "accountId"),
            side = extract(body, "side"),
            orderType = extract(body, "orderType"),
            quantityUnits = extract(body, "quantityUnits"),
            limitPrice = extract(body, "limitPrice"),
            currency = extract(body, "currency"),
            timeInForce = extract(body, "timeInForce")
        )
    }

    private fun extract(body: String, key: String): String {
        val marker = "\"$key\":\""
        val start = body.indexOf(marker)
        if (start < 0) return ""
        val valueStart = start + marker.length
        val end = body.indexOf('"', valueStart)
        if (end < 0) return ""
        return body.substring(valueStart, end)
    }

    private fun toJson(result: SubmitOrderResult): String {
        val accepted = result.accepted
        if (accepted != null) {
            return """
                {
                  "accepted":{
                    "eventId":"${escape(accepted.eventId)}",
                    "orderId":"${escape(accepted.orderId)}",
                    "engineOrderId":"${escape(accepted.engineOrderId)}",
                    "occurredAt":"${escape(accepted.occurredAt)}"
                  }
                }
            """.trimIndent()
        }

        val rejected = result.rejected
        return """
            {
              "rejected":{
                "eventId":"${escape(rejected?.eventId.orEmpty())}",
                "orderId":"${escape(rejected?.orderId.orEmpty())}",
                "code":"${escape(rejected?.code.orEmpty())}",
                "reason":"${escape(rejected?.reason.orEmpty())}",
                "occurredAt":"${escape(rejected?.occurredAt.orEmpty())}"
              }
            }
        """.trimIndent()
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
