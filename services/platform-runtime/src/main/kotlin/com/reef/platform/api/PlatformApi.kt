package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated

class PlatformApi(
    private val orderService: OrderApplicationService = OrderApplicationService()
) {
    fun health(): String {
        return """{"service":"platform-runtime","status":"ok"}"""
    }

    fun submitOrder(body: String): String {
        val command = SubmitOrderCommand(
            commandId = JsonFields.extract(body, "commandId"),
            correlationId = JsonFields.extract(body, "correlationId"),
            actorId = JsonFields.extract(body, "actorId"),
            occurredAt = JsonFields.extract(body, "occurredAt"),
            orderId = JsonFields.extract(body, "orderId"),
            instrumentId = JsonFields.extract(body, "instrumentId"),
            participantId = JsonFields.extract(body, "participantId"),
            accountId = JsonFields.extract(body, "accountId"),
            side = JsonFields.extract(body, "side"),
            orderType = JsonFields.extract(body, "orderType"),
            quantityUnits = JsonFields.extract(body, "quantityUnits"),
            limitPrice = JsonFields.extract(body, "limitPrice"),
            currency = JsonFields.extract(body, "currency"),
            timeInForce = JsonFields.extract(body, "timeInForce")
        )

        return toJson(orderService.submitOrder(command))
    }

    private fun toJson(result: SubmitOrderResult): String {
        val accepted = result.accepted
        if (accepted != null) {
            return """
                {
                  "accepted":{
                    "eventId":"${JsonFields.escape(accepted.eventId)}",
                    "orderId":"${JsonFields.escape(accepted.orderId)}",
                    "engineOrderId":"${JsonFields.escape(accepted.engineOrderId)}",
                    "occurredAt":"${JsonFields.escape(accepted.occurredAt)}"
                  },
                  "executions":${toExecutionsJson(result.executions)},
                  "trades":${toTradesJson(result.trades)}
                }
            """.trimIndent()
        }

        val rejected = result.rejected
        return """
            {
              "rejected":{
                "eventId":"${JsonFields.escape(rejected?.eventId.orEmpty())}",
                "orderId":"${JsonFields.escape(rejected?.orderId.orEmpty())}",
                "code":"${JsonFields.escape(rejected?.code.orEmpty())}",
                "reason":"${JsonFields.escape(rejected?.reason.orEmpty())}",
                "occurredAt":"${JsonFields.escape(rejected?.occurredAt.orEmpty())}"
              },
              "executions":[],
              "trades":[]
            }
        """.trimIndent()
    }

    private fun toExecutionsJson(executions: List<ExecutionCreated>): String {
        return executions.joinToString(prefix = "[", postfix = "]") { execution ->
            """
            {
              "eventId":"${JsonFields.escape(execution.eventId)}",
              "executionId":"${JsonFields.escape(execution.executionId)}",
              "orderId":"${JsonFields.escape(execution.orderId)}",
              "instrumentId":"${JsonFields.escape(execution.instrumentId)}",
              "quantityUnits":"${JsonFields.escape(execution.quantityUnits)}",
              "executionPrice":"${JsonFields.escape(execution.executionPrice)}",
              "currency":"${JsonFields.escape(execution.currency)}",
              "occurredAt":"${JsonFields.escape(execution.occurredAt)}"
            }
            """.trimIndent()
        }
    }

    private fun toTradesJson(trades: List<TradeCreated>): String {
        return trades.joinToString(prefix = "[", postfix = "]") { trade ->
            """
            {
              "eventId":"${JsonFields.escape(trade.eventId)}",
              "tradeId":"${JsonFields.escape(trade.tradeId)}",
              "executionId":"${JsonFields.escape(trade.executionId)}",
              "buyOrderId":"${JsonFields.escape(trade.buyOrderId)}",
              "sellOrderId":"${JsonFields.escape(trade.sellOrderId)}",
              "instrumentId":"${JsonFields.escape(trade.instrumentId)}",
              "quantityUnits":"${JsonFields.escape(trade.quantityUnits)}",
              "price":"${JsonFields.escape(trade.price)}",
              "currency":"${JsonFields.escape(trade.currency)}",
              "occurredAt":"${JsonFields.escape(trade.occurredAt)}"
            }
            """.trimIndent()
        }
    }
}
