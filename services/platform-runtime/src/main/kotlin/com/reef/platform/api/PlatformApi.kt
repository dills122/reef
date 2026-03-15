package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult

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
                  }
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
              }
            }
        """.trimIndent()
    }
}
