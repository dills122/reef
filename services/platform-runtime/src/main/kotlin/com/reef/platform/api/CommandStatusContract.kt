package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.CanonicalCommandOutcome
import com.reef.platform.infrastructure.persistence.CanonicalCommandResult
import com.reef.platform.infrastructure.persistence.VenueEventBatchCommandReference

data class CommandStatusView(
    val commandId: String,
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val status: CommandLogStatus,
    val processingMode: CommandProcessingMode,
    val responseStatus: Int,
    val responsePayloadJson: String,
    val lastError: String,
    val canonicalMaterialized: Boolean = false,
    val engineResultStatus: String = "",
    val batchId: String = "",
    val shardId: String = "",
    val partition: Int = -1,
    val commandStream: String = "",
    val eventStream: String = "",
    val streamSequence: Long = 0L,
    val deliveredCount: Long = 0L,
    val commandType: String = "",
    val payloadHash: String = "",
    val instrumentId: String = "",
    val participantId: String = "",
    val orderId: String = "",
    val rejectCode: String = "",
    val resultPayloadJson: String = "",
    val source: String = ""
)

interface CommandStatusLookup {
    fun findCommandStatus(commandId: String): CommandStatusView?
    fun findCommandStatus(clientId: String, route: String, idempotencyKey: String): CommandStatusView?
}

object CommandStatusResponse {
    fun statusJson(view: CommandStatusView): String {
        return JsonCodec.writeObject(
            "commandId" to view.commandId,
            "clientId" to view.clientId,
            "route" to view.route,
            "idempotencyKey" to view.idempotencyKey,
            "status" to view.publicStatus(),
            "internalStatus" to view.status.name,
            "processingMode" to view.processingMode.configValue,
            "responseStatus" to view.responseStatus,
            "responsePayloadJson" to view.responsePayloadJson,
            "lastError" to view.lastError,
            "canonicalMaterialized" to view.canonicalMaterialized,
            "engineResultStatus" to view.engineResultStatus,
            "resultStatus" to view.engineResultStatus,
            "batchId" to view.batchId,
            "shardId" to view.shardId,
            "partition" to view.partition,
            "commandStream" to view.commandStream,
            "eventStream" to view.eventStream,
            "streamSequence" to view.streamSequence,
            "deliveredCount" to view.deliveredCount,
            "commandType" to view.commandType,
            "payloadHash" to view.payloadHash,
            "instrumentId" to view.instrumentId,
            "participantId" to view.participantId,
            "orderId" to view.orderId,
            "rejectCode" to view.rejectCode,
            "resultPayloadJson" to view.resultPayloadJson,
            "source" to view.source
        )
    }

    fun acceptedJson(view: CommandStatusView): String {
        return JsonCodec.writeObject(
            "commandId" to view.commandId,
            "status" to "ACCEPTED",
            "processingMode" to view.processingMode.configValue,
            "statusUrl" to "/api/v1/commands/${view.commandId}"
        )
    }

    private fun CommandStatusView.publicStatus(): String {
        if (canonicalMaterialized) {
            return when {
                engineResultStatus.equals("failed", ignoreCase = true) -> "FAILED"
                engineResultStatus.equals("rejected", ignoreCase = true) -> "REJECTED"
                else -> "COMPLETED"
            }
        }
        if (source == "event_batch") {
            return "EVENT_PUBLISHED"
        }
        return when (status) {
            CommandLogStatus.RECEIVED -> "ACCEPTED"
            CommandLogStatus.PROCESSING -> "IN_FLIGHT"
            CommandLogStatus.COMPLETED -> "COMPLETED"
            CommandLogStatus.FAILED -> "FAILED"
        }
    }
}

fun CommandLogRecord.toStatusView(processingMode: CommandProcessingMode): CommandStatusView {
    return CommandStatusView(
        commandId = commandId,
        clientId = clientId,
        route = route,
        idempotencyKey = idempotencyKey,
        status = status,
        processingMode = processingMode,
        responseStatus = responseStatus,
        responsePayloadJson = responsePayloadJson,
        lastError = lastError,
        participantId = commandStatusParticipantId(payloadJson),
        source = "command_log"
    )
}

fun StreamCommandReference.toStatusView(): CommandStatusView {
    return CommandStatusView(
        commandId = commandId,
        clientId = "",
        route = route,
        idempotencyKey = "",
        status = CommandLogStatus.RECEIVED,
        processingMode = CommandProcessingMode.StreamAck,
        responseStatus = 202,
        responsePayloadJson = "",
        lastError = "",
        batchId = "",
        shardId = "",
        partition = partition,
        commandStream = streamName,
        eventStream = "",
        streamSequence = streamSequence,
        commandType = streamReferenceCommandType(route),
        source = "stream_reference"
    )
}

private fun streamReferenceCommandType(route: String): String {
    return when {
        route.endsWith("/orders/submit") -> "SubmitOrder"
        route.endsWith("/orders/cancel") -> "CancelOrder"
        route.endsWith("/orders/modify") -> "ModifyOrder"
        else -> ""
    }
}

fun CanonicalCommandOutcome.toStatusView(): CommandStatusView {
    val failed = resultStatus.equals("failed", ignoreCase = true)
    val responseStatus = when {
        failed -> 500
        resultStatus.equals("rejected", ignoreCase = true) -> 422
        else -> 200
    }
    return CommandStatusView(
        commandId = commandId,
        clientId = "",
        route = "",
        idempotencyKey = "",
        status = if (failed) CommandLogStatus.FAILED else CommandLogStatus.COMPLETED,
        processingMode = CommandProcessingMode.StreamAck,
        responseStatus = responseStatus,
        responsePayloadJson = resultPayloadJson,
        lastError = "",
        canonicalMaterialized = true,
        engineResultStatus = resultStatus,
        batchId = batchId,
        shardId = shardId,
        partition = partition,
        commandStream = commandStream,
        eventStream = eventStream,
        streamSequence = streamSequence,
        deliveredCount = deliveredCount,
        commandType = commandType,
        payloadHash = payloadHash,
        instrumentId = instrumentId,
        participantId = commandStatusParticipantId(resultPayloadJson),
        orderId = orderId,
        rejectCode = rejectCode,
        resultPayloadJson = resultPayloadJson,
        source = "canonical_outcome"
    )
}

fun CanonicalCommandResult.toStatusView(): CommandStatusView {
    val failed = resultStatus.equals("failed", ignoreCase = true)
    val responseStatus = when {
        failed -> 500
        resultStatus.equals("rejected", ignoreCase = true) -> 422
        else -> 200
    }
    return CommandStatusView(
        commandId = commandId,
        clientId = "",
        route = "",
        idempotencyKey = "",
        status = if (failed) CommandLogStatus.FAILED else CommandLogStatus.COMPLETED,
        processingMode = CommandProcessingMode.StreamAck,
        responseStatus = responseStatus,
        responsePayloadJson = resultPayloadJson,
        lastError = "",
        canonicalMaterialized = true,
        engineResultStatus = resultStatus,
        batchId = "",
        shardId = engineShardId,
        partition = partition,
        commandStream = commandStream,
        eventStream = "",
        streamSequence = streamSequence,
        deliveredCount = 0L,
        commandType = commandType,
        payloadHash = payloadHash,
        instrumentId = instrumentId,
        participantId = commandStatusParticipantId(resultPayloadJson),
        orderId = commandStatusOrderId(resultPayloadJson),
        rejectCode = rejectCode,
        resultPayloadJson = resultPayloadJson,
        source = "canonical_result"
    )
}

fun VenueEventBatchCommandReference.toStatusView(): CommandStatusView {
    return CommandStatusView(
        commandId = commandId,
        clientId = "",
        route = "",
        idempotencyKey = "",
        status = CommandLogStatus.PROCESSING,
        processingMode = CommandProcessingMode.StreamAck,
        responseStatus = 202,
        responsePayloadJson = "",
        lastError = "",
        canonicalMaterialized = false,
        engineResultStatus = resultStatus,
        batchId = batchId,
        shardId = shardId,
        partition = partition,
        commandStream = commandStream,
        eventStream = eventStream,
        streamSequence = streamSequence,
        deliveredCount = deliveredCount,
        commandType = commandType,
        payloadHash = payloadHash,
        instrumentId = instrumentId,
        participantId = commandStatusParticipantId(resultPayloadJson),
        orderId = orderId,
        rejectCode = rejectCode,
        resultPayloadJson = resultPayloadJson,
        source = "event_batch"
    )
}

internal fun commandStatusParticipantId(payloadJson: String): String {
    val root = JsonCodec.parseLegacyObjectOrEmpty(payloadJson)
    return root.string("participantId")
        .ifBlank { root.obj("acceptedOrder").string("participantId") }
        .ifBlank { root.obj("accepted").string("participantId") }
        .ifBlank { root.obj("rejected").string("participantId") }
}

private fun commandStatusOrderId(payloadJson: String): String {
    val root = JsonCodec.parseLegacyObjectOrEmpty(payloadJson)
    return root.string("orderId")
        .ifBlank { root.obj("acceptedOrder").string("orderId") }
        .ifBlank { root.obj("accepted").string("orderId") }
        .ifBlank { root.obj("rejected").string("orderId") }
}
