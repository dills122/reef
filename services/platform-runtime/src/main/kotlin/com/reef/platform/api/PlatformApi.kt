package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.Account
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RuntimeEvent
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
            traceId = JsonFields.extract(body, "traceId"),
            causationId = JsonFields.extract(body, "causationId"),
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

    fun cancelOrder(body: String): String {
        val command = CancelOrderCommand(
            commandId = JsonFields.extract(body, "commandId"),
            traceId = JsonFields.extract(body, "traceId"),
            causationId = JsonFields.extract(body, "causationId"),
            correlationId = JsonFields.extract(body, "correlationId"),
            actorId = JsonFields.extract(body, "actorId"),
            occurredAt = JsonFields.extract(body, "occurredAt"),
            orderId = JsonFields.extract(body, "orderId"),
            reason = JsonFields.extract(body, "reason")
        )
        return toJson(orderService.cancelOrder(command))
    }

    fun modifyOrder(body: String): String {
        val command = ModifyOrderCommand(
            commandId = JsonFields.extract(body, "commandId"),
            traceId = JsonFields.extract(body, "traceId"),
            causationId = JsonFields.extract(body, "causationId"),
            correlationId = JsonFields.extract(body, "correlationId"),
            actorId = JsonFields.extract(body, "actorId"),
            occurredAt = JsonFields.extract(body, "occurredAt"),
            orderId = JsonFields.extract(body, "orderId"),
            quantityUnits = JsonFields.extract(body, "quantityUnits"),
            limitPrice = JsonFields.extract(body, "limitPrice")
        )
        return toJson(orderService.modifyOrder(command))
    }

    fun createInstrument(body: String): String {
        val instrument = Instrument(
            instrumentId = JsonFields.extract(body, "instrumentId"),
            symbol = JsonFields.extract(body, "symbol")
        )
        orderService.createInstrument(instrument)
        return """{"instrumentId":"${JsonFields.escape(instrument.instrumentId)}"}"""
    }

    fun createParticipant(body: String): String {
        val participant = Participant(
            participantId = JsonFields.extract(body, "participantId"),
            name = JsonFields.extract(body, "name")
        )
        orderService.createParticipant(participant)
        return """{"participantId":"${JsonFields.escape(participant.participantId)}"}"""
    }

    fun createAccount(body: String): String {
        val account = Account(
            accountId = JsonFields.extract(body, "accountId"),
            participantId = JsonFields.extract(body, "participantId")
        )
        orderService.createAccount(account)
        return """{"accountId":"${JsonFields.escape(account.accountId)}"}"""
    }

    fun instruments(): String {
        val values = orderService.instruments().joinToString(prefix = "[", postfix = "]") { instrument ->
            """{"instrumentId":"${JsonFields.escape(instrument.instrumentId)}","symbol":"${JsonFields.escape(instrument.symbol)}"}"""
        }
        return """{"instruments":$values}"""
    }

    fun participants(): String {
        val values = orderService.participants().joinToString(prefix = "[", postfix = "]") { participant ->
            """{"participantId":"${JsonFields.escape(participant.participantId)}","name":"${JsonFields.escape(participant.name)}"}"""
        }
        return """{"participants":$values}"""
    }

    fun accounts(): String {
        val values = orderService.accounts().joinToString(prefix = "[", postfix = "]") { account ->
            """{"accountId":"${JsonFields.escape(account.accountId)}","participantId":"${JsonFields.escape(account.participantId)}"}"""
        }
        return """{"accounts":$values}"""
    }

    fun order(orderId: String): String {
        val order = orderService.persistedOrder(orderId)
        if (order == null) {
            return """{"error":"order not found","orderId":"${JsonFields.escape(orderId)}"}"""
        }

        return """
            {
              "order":${toOrderJson(order)},
              "executions":${toExecutionsJson(orderService.persistedExecutions(orderId))},
              "trades":${toTradesJson(orderService.persistedTrades(orderId))}
            }
        """.trimIndent()
    }

    fun orderEvents(orderId: String): String {
        return """
            {
              "orderId":"${JsonFields.escape(orderId)}",
              "events":${toEventsJson(orderService.persistedEvents(orderId))}
            }
        """.trimIndent()
    }

    fun events(): String {
        return """
            {
              "events":${toEventsJson(orderService.events())}
            }
        """.trimIndent()
    }

    fun traceEvents(traceId: String): String {
        return """
            {
              "traceId":"${JsonFields.escape(traceId)}",
              "events":${toEventsJson(orderService.persistedTraceEvents(traceId))}
            }
        """.trimIndent()
    }

    fun orders(): String {
        return """
            {
              "orders":${toOrdersJson(orderService.persistedOrders())}
            }
        """.trimIndent()
    }

    fun trades(): String {
        return """
            {
              "trades":${toTradesJson(orderService.persistedTrades())}
            }
        """.trimIndent()
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

    private fun toOrderJson(order: PersistedOrder): String {
        return """
            {
              "orderId":"${JsonFields.escape(order.orderId)}",
              "engineOrderId":"${JsonFields.escape(order.engineOrderId)}",
              "instrumentId":"${JsonFields.escape(order.instrumentId)}",
              "participantId":"${JsonFields.escape(order.participantId)}",
              "accountId":"${JsonFields.escape(order.accountId)}",
              "side":"${JsonFields.escape(order.side)}",
              "orderType":"${JsonFields.escape(order.orderType)}",
              "quantityUnits":"${JsonFields.escape(order.quantityUnits)}",
              "limitPrice":"${JsonFields.escape(order.limitPrice)}",
              "currency":"${JsonFields.escape(order.currency)}",
              "timeInForce":"${JsonFields.escape(order.timeInForce)}",
              "acceptedAt":"${JsonFields.escape(order.acceptedAt)}"
            }
        """.trimIndent()
    }

    private fun toEventsJson(events: List<RuntimeEvent>): String {
        return events.joinToString(prefix = "[", postfix = "]") { event ->
            """
            {
              "eventId":"${JsonFields.escape(event.eventId)}",
              "eventType":"${JsonFields.escape(event.eventType)}",
              "orderId":"${JsonFields.escape(event.orderId)}",
              "traceId":"${JsonFields.escape(event.traceId)}",
              "causationId":"${JsonFields.escape(event.causationId)}",
              "correlationId":"${JsonFields.escape(event.correlationId)}",
              "producer":"${JsonFields.escape(event.producer)}",
              "schemaVersion":"${JsonFields.escape(event.schemaVersion)}",
              "occurredAt":"${JsonFields.escape(event.occurredAt)}"
            }
            """.trimIndent()
        }
    }

    private fun toOrdersJson(orders: List<PersistedOrder>): String {
        return orders.joinToString(prefix = "[", postfix = "]") { order ->
            toOrderJson(order)
        }
    }
}
