package com.reef.platform.infrastructure.engine

import com.reef.platform.api.JsonFields
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import java.time.Instant
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class EngineClient : EngineGateway {
    private val engineBaseUrl: String
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    constructor() {
        engineBaseUrl = System.getenv("MATCHING_ENGINE_BASE_URL") ?: "http://localhost:8081"
    }

    internal constructor(engineBaseUrl: String) {
        this.engineBaseUrl = engineBaseUrl
    }

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val payload = """
            {
              "commandId":"${JsonFields.escape(command.commandId)}",
              "traceId":"${JsonFields.escape(command.traceId)}",
              "causationId":"${JsonFields.escape(command.causationId)}",
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

        return postAndParse("/orders/submit", payload)
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        val payload = """
            {
              "commandId":"${JsonFields.escape(command.commandId)}",
              "traceId":"${JsonFields.escape(command.traceId)}",
              "causationId":"${JsonFields.escape(command.causationId)}",
              "correlationId":"${JsonFields.escape(command.correlationId)}",
              "actorId":"${JsonFields.escape(command.actorId)}",
              "occurredAt":"${JsonFields.escape(command.occurredAt)}",
              "orderId":"${JsonFields.escape(command.orderId)}",
              "reason":"${JsonFields.escape(command.reason)}"
            }
        """.trimIndent()

        return postAndParse("/orders/cancel", payload)
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        val payload = """
            {
              "commandId":"${JsonFields.escape(command.commandId)}",
              "traceId":"${JsonFields.escape(command.traceId)}",
              "causationId":"${JsonFields.escape(command.causationId)}",
              "correlationId":"${JsonFields.escape(command.correlationId)}",
              "actorId":"${JsonFields.escape(command.actorId)}",
              "occurredAt":"${JsonFields.escape(command.occurredAt)}",
              "orderId":"${JsonFields.escape(command.orderId)}",
              "quantityUnits":"${JsonFields.escape(command.quantityUnits)}",
              "limitPrice":"${JsonFields.escape(command.limitPrice)}"
            }
        """.trimIndent()

        return postAndParse("/orders/modify", payload)
    }

    private fun postAndParse(path: String, payload: String): SubmitOrderResult {
        return try {
            val request = HttpRequest.newBuilder(URI.create("$engineBaseUrl$path"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val responseBody = response.body()
            parseSubmitOrderResult(responseBody)
        } catch (ex: Exception) {
            SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-engine-transport-error",
                    orderId = JsonFields.extract(payload, "orderId"),
                    code = "ENGINE_UNAVAILABLE",
                    reason = ex.message ?: "engine transport error",
                    occurredAt = Instant.now().toString()
                ),
                executions = emptyList(),
                trades = emptyList()
            )
        }
    }

    internal fun parseSubmitOrderResult(body: String): SubmitOrderResult {
        if (body.contains("\"accepted\"")) {
            return SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = JsonFields.extract(body, "eventId"),
                    orderId = JsonFields.extract(body, "orderId"),
                    engineOrderId = JsonFields.extract(body, "engineOrderId"),
                    occurredAt = extractLast(body, "occurredAt")
                ),
                executions = parseExecutions(body),
                trades = parseTrades(body)
            )
        }

        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = JsonFields.extract(body, "eventId"),
                orderId = JsonFields.extract(body, "orderId"),
                code = JsonFields.extract(body, "code"),
                reason = JsonFields.extract(body, "reason"),
                occurredAt = extractLast(body, "occurredAt")
            ),
            executions = emptyList(),
            trades = emptyList()
        )
    }

    private fun parseExecutions(body: String): List<ExecutionCreated> {
        return JsonFields.extractObjects(body, "executions").map { execution ->
            ExecutionCreated(
                eventId = JsonFields.extract(execution, "eventId"),
                executionId = JsonFields.extract(execution, "executionId"),
                orderId = JsonFields.extract(execution, "orderId"),
                instrumentId = JsonFields.extract(execution, "instrumentId"),
                quantityUnits = JsonFields.extract(execution, "quantityUnits"),
                executionPrice = JsonFields.extract(execution, "executionPrice"),
                currency = JsonFields.extract(execution, "currency"),
                occurredAt = JsonFields.extract(execution, "occurredAt")
            )
        }
    }

    private fun parseTrades(body: String): List<TradeCreated> {
        return JsonFields.extractObjects(body, "trades").map { trade ->
            TradeCreated(
                eventId = JsonFields.extract(trade, "eventId"),
                tradeId = JsonFields.extract(trade, "tradeId"),
                executionId = JsonFields.extract(trade, "executionId"),
                buyOrderId = JsonFields.extract(trade, "buyOrderId"),
                sellOrderId = JsonFields.extract(trade, "sellOrderId"),
                instrumentId = JsonFields.extract(trade, "instrumentId"),
                quantityUnits = JsonFields.extract(trade, "quantityUnits"),
                price = JsonFields.extract(trade, "price"),
                currency = JsonFields.extract(trade, "currency"),
                occurredAt = JsonFields.extract(trade, "occurredAt")
            )
        }
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
