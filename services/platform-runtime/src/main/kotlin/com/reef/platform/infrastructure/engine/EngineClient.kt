package com.reef.platform.infrastructure.engine

import com.reef.platform.api.JsonCodec
import com.reef.platform.api.JsonDocument
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
        return postAndParse("/orders/submit", submitPayload(command))
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return postAndParse(
            "/orders/cancel",
            JsonCodec.writeObject(
                "commandId" to command.commandId,
                "traceId" to command.traceId,
                "causationId" to command.causationId,
                "correlationId" to command.correlationId,
                "actorId" to command.actorId,
                "occurredAt" to command.occurredAt,
                "orderId" to command.orderId,
                "reason" to command.reason
            )
        )
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return postAndParse(
            "/orders/modify",
            JsonCodec.writeObject(
                "commandId" to command.commandId,
                "traceId" to command.traceId,
                "causationId" to command.causationId,
                "correlationId" to command.correlationId,
                "actorId" to command.actorId,
                "occurredAt" to command.occurredAt,
                "orderId" to command.orderId,
                "quantityUnits" to command.quantityUnits,
                "limitPrice" to command.limitPrice
            )
        )
    }

    internal fun submitPayload(command: SubmitOrderCommand): String {
        return JsonCodec.writeObject(
            "commandId" to command.commandId,
            "correlationId" to command.correlationId,
            "actorId" to command.actorId,
            "occurredAt" to command.occurredAt,
            "orderId" to command.orderId,
            "instrumentId" to command.instrumentId,
            "participantId" to command.participantId,
            "accountId" to command.accountId,
            "side" to command.side,
            "orderType" to command.orderType,
            "quantityUnits" to command.quantityUnits,
            "limitPrice" to command.limitPrice,
            "currency" to command.currency,
            "timeInForce" to command.timeInForce
        )
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
