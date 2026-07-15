package com.reef.platform.api

import com.reef.platform.domain.Account
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.ActorRoleBinding

object PlatformCommandParsers {
    private val apiV1CommonOptionalFields = setOf(
        "causationId",
        "actorType",
        "strategyId",
        "persona",
        "runId",
        "runKind",
        "scenarioId",
        "scenarioRunId",
        "seed",
        "venueSessionId",
        "instrumentId",
        "clientOrderId",
        "botId",
        "botVersion"
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
        return when (val result = parseAndValidateApiV1Command(route, body)) {
            is ApiV1CommandValidation.Invalid -> result.error
            is ApiV1CommandValidation.Valid -> null
        }
    }

    // Parses body once and returns either the parsed JsonDocument (on
    // success) or the validation error, so callers that both validate and
    // then read fields out of the same body (e.g. PlatformHttpServer's
    // account-risk-request assembly) don't have to re-parse it.
    fun parseAndValidateApiV1Command(route: String, body: String): ApiV1CommandValidation {
        val json = try {
            JsonCodec.parseObject(body)
        } catch (_: Exception) {
            return ApiV1CommandValidation.Invalid("invalid json payload")
        }
        val contract = apiV1Contracts[route]
        if (contract == null) {
            return ApiV1CommandValidation.Valid(json)
        }
        val unknownField = json.fieldNames().firstOrNull { it !in contract.allowedFields }
        if (unknownField != null) {
            return ApiV1CommandValidation.Invalid("unknown field: $unknownField")
        }
        val missingField = contract.requiredFields.firstOrNull { field ->
            !json.has(field) || json.string(field).isBlank()
        }
        if (missingField != null) {
            return ApiV1CommandValidation.Invalid("missing required field: $missingField")
        }
        if (route == "/api/v1/orders/submit") {
            enumValidationError(json, "side", setOf("BUY", "SELL"))?.let { return ApiV1CommandValidation.Invalid(it) }
            enumValidationError(json, "orderType", setOf("LIMIT", "LIMIT_HIDDEN"))?.let { return ApiV1CommandValidation.Invalid(it) }
            enumValidationError(json, "timeInForce", setOf("DAY", "IOC"))?.let { return ApiV1CommandValidation.Invalid(it) }
            numericFieldValidationError(json, "quantityUnits")?.let { return ApiV1CommandValidation.Invalid(it) }
            numericFieldValidationError(json, "limitPrice")?.let { return ApiV1CommandValidation.Invalid(it) }
        }
        if (route == "/api/v1/orders/modify") {
            numericFieldValidationError(json, "quantityUnits")?.let { return ApiV1CommandValidation.Invalid(it) }
            numericFieldValidationError(json, "limitPrice")?.let { return ApiV1CommandValidation.Invalid(it) }
        }
        return ApiV1CommandValidation.Valid(json)
    }

    private fun enumValidationError(json: JsonDocument, field: String, allowed: Set<String>): String? {
        val value = json.string(field)
        if (value.uppercase() in allowed) {
            return null
        }
        return "invalid ${fieldMessageName(field)}: $value"
    }

    private fun numericFieldValidationError(json: JsonDocument, field: String): String? {
        val value = json.string(field)
        if (value.isBlank()) {
            return null
        }
        return if (value.toBigDecimalOrNull() == null) "invalid ${fieldMessageName(field)}: $value" else null
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
        val json = JsonCodec.parseObject(body)
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
            timeInForce = json.string("timeInForce"),
            clientOrderId = json.string("clientOrderId"),
            runId = json.string("runId").ifBlank { json.string("scenarioRunId") },
            venueSessionId = json.string("venueSessionId")
        )
    }

    fun cancelOrder(body: String): CancelOrderCommand {
        val json = JsonCodec.parseObject(body)
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
        val json = JsonCodec.parseObject(body)
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
        val json = JsonCodec.parseObject(body)
        return Instrument(
            instrumentId = json.string("instrumentId"),
            symbol = json.string("symbol")
        )
    }

    fun participant(body: String): Participant {
        val json = JsonCodec.parseObject(body)
        return Participant(
            participantId = json.string("participantId"),
            name = json.string("name")
        )
    }

    fun account(body: String): Account {
        val json = JsonCodec.parseObject(body)
        return Account(
            accountId = json.string("accountId"),
            participantId = json.string("participantId")
        )
    }

    fun roleDefinition(body: String): RoleDefinition {
        val json = JsonCodec.parseObject(body)
        return RoleDefinition(
            roleId = json.string("roleId"),
            permissions = json.string("permissions")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        )
    }

    fun actorRoleBinding(body: String): ActorRoleBinding {
        val json = JsonCodec.parseObject(body)
        return ActorRoleBinding(
            actorId = json.string("actorId"),
            roleId = json.string("roleId")
        )
    }

    private data class ApiV1CommandContract(
        val requiredFields: List<String>,
        val allowedFields: Set<String>
    )
}

sealed class ApiV1CommandValidation {
    data class Valid(val json: JsonDocument) : ApiV1CommandValidation()
    data class Invalid(val error: String) : ApiV1CommandValidation()
}
