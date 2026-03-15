package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult

class EngineClient {
    private val engineBaseUrl: String =
        System.getenv("MATCHING_ENGINE_BASE_URL") ?: "http://localhost:8081"

    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val payload = """
            {
              "commandId":"${escape(command.commandId)}",
              "correlationId":"${escape(command.correlationId)}",
              "actorId":"${escape(command.actorId)}",
              "occurredAt":"${escape(command.occurredAt)}",
              "orderId":"${escape(command.orderId)}",
              "instrumentId":"${escape(command.instrumentId)}",
              "participantId":"${escape(command.participantId)}",
              "accountId":"${escape(command.accountId)}",
              "side":"${escape(command.side)}",
              "orderType":"${escape(command.orderType)}",
              "quantityUnits":"${escape(command.quantityUnits)}",
              "limitPrice":"${escape(command.limitPrice)}",
              "currency":"${escape(command.currency)}",
              "timeInForce":"${escape(command.timeInForce)}"
            }
        """.trimIndent()

        val connection = java.net.URI.create("$engineBaseUrl/orders/submit").toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { output ->
            output.write(payload.toByteArray())
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        return parseSubmitOrderResult(responseBody)
    }

    private fun parseSubmitOrderResult(body: String): SubmitOrderResult {
        if (body.contains("\"accepted\"")) {
            return SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = extract(body, "eventId"),
                    orderId = extract(body, "orderId"),
                    engineOrderId = extract(body, "engineOrderId"),
                    occurredAt = extractLast(body, "occurredAt")
                )
            )
        }

        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = extract(body, "eventId"),
                orderId = extract(body, "orderId"),
                code = extract(body, "code"),
                reason = extract(body, "reason"),
                occurredAt = extractLast(body, "occurredAt")
            )
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

    private fun extractLast(body: String, key: String): String {
        val marker = "\"$key\":\""
        val start = body.lastIndexOf(marker)
        if (start < 0) return ""
        val valueStart = start + marker.length
        val end = body.indexOf('"', valueStart)
        if (end < 0) return ""
        return body.substring(valueStart, end)
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
