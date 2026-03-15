package com.reef.platform.infrastructure.engine

import com.reef.platform.api.JsonFields
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult

class EngineClient : EngineGateway {
    private val engineBaseUrl: String =
        System.getenv("MATCHING_ENGINE_BASE_URL") ?: "http://localhost:8081"

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val payload = """
            {
              "commandId":"${JsonFields.escape(command.commandId)}",
              "correlationId":"${JsonFields.escape(command.correlationId)}",
              "actorId":"${JsonFields.escape(command.actorId)}",
              "occurredAt":"${JsonFields.escape(command.occurredAt)}",
              "orderId":"${JsonFields.escape(command.orderId)}",
              "instrumentId":"${JsonFields.escape(command.instrumentId)}",
              "participantId":"${JsonFields.escape(command.participantId)}",
              "accountId":"${JsonFields.escape(command.accountId)}",
              "side":"${JsonFields.escape(command.side)}",
              "orderType":"${JsonFields.escape(command.orderType)}",
              "quantityUnits":"${JsonFields.escape(command.quantityUnits)}",
              "limitPrice":"${JsonFields.escape(command.limitPrice)}",
              "currency":"${JsonFields.escape(command.currency)}",
              "timeInForce":"${JsonFields.escape(command.timeInForce)}"
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

    internal fun parseSubmitOrderResult(body: String): SubmitOrderResult {
        if (body.contains("\"accepted\"")) {
            return SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = JsonFields.extract(body, "eventId"),
                    orderId = JsonFields.extract(body, "orderId"),
                    engineOrderId = JsonFields.extract(body, "engineOrderId"),
                    occurredAt = extractLast(body, "occurredAt")
                )
            )
        }

        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = JsonFields.extract(body, "eventId"),
                orderId = JsonFields.extract(body, "orderId"),
                code = JsonFields.extract(body, "code"),
                reason = JsonFields.extract(body, "reason"),
                occurredAt = extractLast(body, "occurredAt")
            )
        )
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
}
