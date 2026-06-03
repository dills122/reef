package com.reef.platform.api

import com.reef.platform.domain.Account
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.SubmitOrderCommand

object PlatformCommandParsers {
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
}
