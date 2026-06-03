package com.reef.platform.infrastructure.engine

import com.reef.platform.api.JsonCodec
import com.reef.platform.api.JsonDocument
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
                    orderId = JsonCodec.parseObjectOrEmpty(payload).string("orderId"),
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
        val json = JsonCodec.parseObjectOrEmpty(body)
        if (json.has("accepted")) {
            val accepted = json.obj("accepted")
            return SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = accepted.string("eventId"),
                    orderId = accepted.string("orderId"),
                    engineOrderId = accepted.string("engineOrderId"),
                    occurredAt = accepted.string("occurredAt")
                ),
                executions = parseExecutions(json),
                trades = parseTrades(json)
            )
        }

        val rejected = json.obj("rejected")
        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = rejected.string("eventId"),
                orderId = rejected.string("orderId"),
                code = rejected.string("code"),
                reason = rejected.string("reason"),
                occurredAt = rejected.string("occurredAt")
            ),
            executions = emptyList(),
            trades = emptyList()
        )
    }

    private fun parseExecutions(body: JsonDocument): List<ExecutionCreated> {
        return body.objectDocuments("executions").map { execution ->
            ExecutionCreated(
                eventId = execution.string("eventId"),
                executionId = execution.string("executionId"),
                orderId = execution.string("orderId"),
                instrumentId = execution.string("instrumentId"),
                quantityUnits = execution.string("quantityUnits"),
                executionPrice = execution.string("executionPrice"),
                currency = execution.string("currency"),
                occurredAt = execution.string("occurredAt")
            )
        }
    }

    private fun parseTrades(body: JsonDocument): List<TradeCreated> {
        return body.objectDocuments("trades").map { trade ->
            TradeCreated(
                eventId = trade.string("eventId"),
                tradeId = trade.string("tradeId"),
                executionId = trade.string("executionId"),
                buyOrderId = trade.string("buyOrderId"),
                sellOrderId = trade.string("sellOrderId"),
                instrumentId = trade.string("instrumentId"),
                quantityUnits = trade.string("quantityUnits"),
                price = trade.string("price"),
                currency = trade.string("currency"),
                occurredAt = trade.string("occurredAt")
            )
        }
    }
}
