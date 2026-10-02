package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.EngineOrderCancelled
import com.reef.platform.domain.RuntimeEvent

internal fun cancellationFromResultPayload(payload: String): EngineOrderCancelled? {
    val document = JsonCodec.parseLegacyObjectOrEmpty(payload)
    if (!document.has("cancelled") || document.raw("cancelled") == "null") return null
    val fact = document.obj("cancelled")
    return EngineOrderCancelled(fact.string("eventId"), fact.string("orderId"),
        fact.string("cancelledQuantityUnits"), fact.string("reason"), fact.string("occurredAt"))
}

internal fun EngineOrderCancelled.toJsonObject(): String = jsonObject(
    "eventId" to eventId, "orderId" to orderId, "cancelledQuantityUnits" to cancelledQuantityUnits,
    "reason" to reason, "occurredAt" to occurredAt
)

internal fun cancellationEvent(fact: EngineOrderCancelled, acceptance: RuntimeEvent, commandId: String): RuntimeEvent =
    acceptance.copy(eventId = fact.eventId, eventType = "OrderCancelled", orderId = fact.orderId,
        causationId = acceptance.eventId, occurredAt = fact.occurredAt,
        payloadJson = JsonCodec.writeObject("commandId" to commandId,
            "cancelledQuantityUnits" to fact.cancelledQuantityUnits, "reason" to fact.reason))
