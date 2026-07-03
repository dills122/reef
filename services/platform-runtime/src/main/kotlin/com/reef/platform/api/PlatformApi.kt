package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.persistence.CanonicalSubmitOutcome
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome

class PlatformApi(
    private val orderService: OrderApplicationService = OrderApplicationService()
) {
    fun health(): String {
        return """{"service":"platform-runtime","status":"ok"}"""
    }

    fun submitOrder(body: String): String {
        return toJson(orderService.submitOrder(PlatformCommandParsers.submitOrder(body)))
    }

    fun prepareSubmitOrder(body: String): PersistableSubmitOutcome {
        return orderService.prepareSubmitOrder(PlatformCommandParsers.submitOrder(body))
    }

    fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {
        orderService.persistSubmitOutcomes(outcomes)
    }

    fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        orderService.appendCanonicalSubmitOutcomes(outcomes)
    }

    fun submitOrderResponse(outcome: PersistableSubmitOutcome): String {
        return toJson(outcome.result)
    }

    fun cancelOrder(body: String): String {
        return toJson(orderService.cancelOrder(PlatformCommandParsers.cancelOrder(body)))
    }

    fun modifyOrder(body: String): String {
        return toJson(orderService.modifyOrder(PlatformCommandParsers.modifyOrder(body)))
    }

    fun createInstrument(body: String): String {
        val instrument = PlatformCommandParsers.instrument(body)
        orderService.createInstrument(instrument)
        return JsonCodec.writeObject("instrumentId" to instrument.instrumentId)
    }

    fun createParticipant(body: String): String {
        val participant = PlatformCommandParsers.participant(body)
        orderService.createParticipant(participant)
        return JsonCodec.writeObject("participantId" to participant.participantId)
    }

    fun createAccount(body: String): String {
        val account = PlatformCommandParsers.account(body)
        orderService.createAccount(account)
        return JsonCodec.writeObject("accountId" to account.accountId)
    }

    fun createRole(body: String): String {
        val role = PlatformCommandParsers.roleDefinition(body)
        orderService.createRole(role)
        return JsonCodec.writeObject("roleId" to role.roleId)
    }

    fun assignRole(body: String): String {
        val binding = PlatformCommandParsers.actorRoleBinding(body)
        orderService.assignRole(binding)
        return JsonCodec.writeObject("actorId" to binding.actorId, "roleId" to binding.roleId)
    }

    fun instruments(): String {
        return JsonCodec.writeObject("instruments" to orderService.instruments().map { instrument ->
            mapOf(
                "instrumentId" to instrument.instrumentId,
                "symbol" to instrument.symbol
            )
        })
    }

    fun participants(): String {
        return JsonCodec.writeObject("participants" to orderService.participants().map { participant ->
            mapOf(
                "participantId" to participant.participantId,
                "name" to participant.name
            )
        })
    }

    fun accounts(): String {
        return JsonCodec.writeObject("accounts" to orderService.accounts().map { account ->
            mapOf(
                "accountId" to account.accountId,
                "participantId" to account.participantId
            )
        })
    }

    fun roles(): String {
        return JsonCodec.writeObject("roles" to orderService.roles().map { role ->
            mapOf(
                "roleId" to role.roleId,
                "permissions" to role.permissions
            )
        })
    }

    fun actorRoles(actorId: String): String {
        return JsonCodec.writeObject("actorRoles" to orderService.actorRoleBindings(actorId).map { binding ->
            mapOf(
                "actorId" to binding.actorId,
                "roleId" to binding.roleId
            )
        })
    }

    fun order(orderId: String): String {
        val order = orderService.persistedOrder(orderId)
        if (order == null) {
            return JsonCodec.writeObject("error" to "order not found", "orderId" to orderId)
        }

        return JsonCodec.writeObject(
            "order" to toOrderMap(order),
            "executions" to orderService.persistedExecutions(orderId).map { it.toMap() },
            "trades" to orderService.persistedTrades(orderId).map { it.toMap() }
        )
    }

    fun orderEvents(orderId: String): String {
        return JsonCodec.writeObject(
            "orderId" to orderId,
            "events" to orderService.persistedEvents(orderId).map { it.toMap() }
        )
    }

    fun events(): String {
        return JsonCodec.writeObject("events" to orderService.events().map { it.toMap() })
    }

    fun recentEvents(limit: Int): String {
        return JsonCodec.writeObject("events" to orderService.recentEvents(limit).map { it.toMap() })
    }

    fun traceEvents(traceId: String): String {
        return JsonCodec.writeObject(
            "traceId" to traceId,
            "events" to orderService.persistedTraceEvents(traceId).map { it.toMap() }
        )
    }

    fun orders(): String {
        return JsonCodec.writeObject("orders" to orderService.persistedOrders().map { toOrderMap(it) })
    }

    fun trades(): String {
        return JsonCodec.writeObject("trades" to orderService.persistedTrades().map { it.toMap() })
    }

    fun recentTrades(limit: Int): String {
        return JsonCodec.writeObject("trades" to orderService.recentTrades(limit).map { it.toMap() })
    }

    private fun toJson(result: SubmitOrderResult): String {
        val accepted = result.accepted
        if (accepted != null) {
            return JsonCodec.writeObject(
                "accepted" to mapOf(
                    "eventId" to accepted.eventId,
                    "orderId" to accepted.orderId,
                    "engineOrderId" to accepted.engineOrderId,
                    "occurredAt" to accepted.occurredAt
                ),
                "executions" to result.executions.map { it.toMap() },
                "trades" to result.trades.map { it.toMap() }
            )
        }

        val rejected = result.rejected
        return JsonCodec.writeObject(
            "rejected" to mapOf(
                "eventId" to rejected?.eventId.orEmpty(),
                "orderId" to rejected?.orderId.orEmpty(),
                "code" to rejected?.code.orEmpty(),
                "reason" to rejected?.reason.orEmpty(),
                "occurredAt" to rejected?.occurredAt.orEmpty()
            ),
            "executions" to emptyList<Any>(),
            "trades" to emptyList<Any>()
        )
    }

    private fun ExecutionCreated.toMap(): Map<String, Any> = mapOf(
        "eventId" to eventId,
        "executionId" to executionId,
        "orderId" to orderId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "executionPrice" to executionPrice,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun TradeCreated.toMap(): Map<String, Any> = mapOf(
        "eventId" to eventId,
        "tradeId" to tradeId,
        "executionId" to executionId,
        "buyOrderId" to buyOrderId,
        "sellOrderId" to sellOrderId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "price" to price,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun RuntimeEvent.toMap(): Map<String, Any> = mapOf(
        "eventId" to eventId,
        "eventType" to eventType,
        "orderId" to orderId,
        "traceId" to traceId,
        "causationId" to causationId,
        "correlationId" to correlationId,
        "actorId" to actorId,
        "producer" to producer,
        "schemaVersion" to schemaVersion,
        "sequenceNumber" to sequenceNumber,
        "occurredAt" to occurredAt,
        "payloadJson" to payloadJson
    )

    private fun toOrderMap(order: PersistedOrder): Map<String, Any> = mapOf(
        "orderId" to order.orderId,
        "engineOrderId" to order.engineOrderId,
        "instrumentId" to order.instrumentId,
        "participantId" to order.participantId,
        "accountId" to order.accountId,
        "side" to order.side,
        "orderType" to order.orderType,
        "quantityUnits" to order.quantityUnits,
        "limitPrice" to order.limitPrice,
        "currency" to order.currency,
        "timeInForce" to order.timeInForce,
        "acceptedAt" to order.acceptedAt
    )
}
