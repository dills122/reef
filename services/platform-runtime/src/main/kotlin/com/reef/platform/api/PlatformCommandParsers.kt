package com.reef.platform.api

import com.reef.platform.domain.Account
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.SubmitOrderCommand

object PlatformCommandParsers {
    private val apiV1CommonOptionalFields = setOf(
        "causationId",
        "actorType",
        "strategyId",
        "persona",
        "scenarioRunId",
        "seed"
    )
    private val apiV1Contracts = mapOf(
        "/api/v1/orders/submit" to apiV1Contract(
            listOf(
                "commandId",
                "traceId",
                "correlationId",
                "actorId",
                "occurredAt",
                "orderId",
                "instrumentId",
                "participantId",
                "accountId",
                "side",
                "orderType",
                "quantityUnits",
                "limitPrice",
                "currency",
                "timeInForce"
            )
        ),
        "/api/v1/orders/cancel" to apiV1Contract(
            listOf(
                "commandId",
                "traceId",
                "correlationId",
                "actorId",
                "occurredAt",
                "orderId",
                "reason"
            )
        ),
        "/api/v1/orders/modify" to apiV1Contract(
            listOf(
                "commandId",
                "traceId",
                "correlationId",
                "actorId",
                "occurredAt",
                "orderId",
                "quantityUnits",
                "limitPrice"
            )
        )
    )

    fun validateApiV1Command(route: String, body: String): String? {
        val json = try {
            JsonCodec.parseObject(body)
        } catch (_: Exception) {
            return "invalid json payload"
        }
        val contract = apiV1Contracts[route] ?: return null
        val unknownField = json.fieldNames().firstOrNull { it !in contract.allowedFields }
        if (unknownField != null) {
            return "unknown field: $unknownField"
        }
        val missingField = contract.requiredFields.firstOrNull { field ->
            !json.has(field) || json.string(field).isBlank()
        }
        if (missingField != null) {
            return "missing required field: $missingField"
        }
        if (route == "/api/v1/orders/submit") {
            enumValidationError(json, "side", setOf("BUY", "SELL"))?.let { return it }
            enumValidationError(json, "orderType", setOf("LIMIT"))?.let { return it }
            enumValidationError(json, "timeInForce", setOf("DAY", "IOC"))?.let { return it }
        }
        return null
    }

    private fun enumValidationError(json: JsonDocument, field: String, allowed: Set<String>): String? {
        val value = json.string(field)
        if (value.uppercase() in allowed) {
            return null
        }
        return "invalid ${fieldMessageName(field)}: $value"
    }

    private fun fieldMessageName(field: String): String {
        return when (field) {
            "orderType" -> "order type"
            "timeInForce" -> "time in force"
            else -> field
        }
    }

    private fun apiV1Contract(requiredFields: List<String>): ApiV1CommandContract {
        return ApiV1CommandContract(
            requiredFields = requiredFields,
            allowedFields = requiredFields.toSet() + apiV1CommonOptionalFields
        )
    }

    fun submitOrder(body: String): SubmitOrderCommand {
        val json = JsonCodec.parseObjectOrEmpty(body)
        return SubmitOrderCommand(
            commandId = json.string("commandId"),
            traceId = json.string("traceId"),
            causationId = json.string("causationId"),
            correlationId = json.string("correlationId"),
            actorId = json.string("actorId"),
            occurredAt = json.string("occurredAt"),
            orderId = json.string("orderId"),
            instrumentId = json.string("instrumentId"),
            participantId = json.string("participantId"),
            accountId = json.string("accountId"),
            side = json.string("side"),
            orderType = json.string("orderType"),
            quantityUnits = json.string("quantityUnits"),
            limitPrice = json.string("limitPrice"),
            currency = json.string("currency"),
            timeInForce = json.string("timeInForce")
        )
    }

    fun cancelOrder(body: String): CancelOrderCommand {
        val json = JsonCodec.parseObjectOrEmpty(body)
        return CancelOrderCommand(
            commandId = json.string("commandId"),
            traceId = json.string("traceId"),
            causationId = json.string("causationId"),
            correlationId = json.string("correlationId"),
            actorId = json.string("actorId"),
            occurredAt = json.string("occurredAt"),
            orderId = json.string("orderId"),
            reason = json.string("reason")
        )
    }

    fun modifyOrder(body: String): ModifyOrderCommand {
        val json = JsonCodec.parseObjectOrEmpty(body)
        return ModifyOrderCommand(
            commandId = json.string("commandId"),
            traceId = json.string("traceId"),
            causationId = json.string("causationId"),
            correlationId = json.string("correlationId"),
            actorId = json.string("actorId"),
            occurredAt = json.string("occurredAt"),
            orderId = json.string("orderId"),
            quantityUnits = json.string("quantityUnits"),
            limitPrice = json.string("limitPrice")
        )
    }

    fun instrument(body: String): Instrument {
        val json = JsonCodec.parseObjectOrEmpty(body)
        return Instrument(
            instrumentId = json.string("instrumentId"),
            symbol = json.string("symbol")
        )
    }

    fun participant(body: String): Participant {
        val json = JsonCodec.parseObjectOrEmpty(body)
        return Participant(
            participantId = json.string("participantId"),
            name = json.string("name")
        )
    }

    fun account(body: String): Account {
        val json = JsonCodec.parseObjectOrEmpty(body)
        return Account(
            accountId = json.string("accountId"),
            participantId = json.string("participantId")
        )
    }

    private data class ApiV1CommandContract(
        val requiredFields: List<String>,
        val allowedFields: Set<String>
    )
}
