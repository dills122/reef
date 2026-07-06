package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.CanonicalCommandOutcome

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
    val orderId: String = "",
    val rejectCode: String = "",
    val resultPayloadJson: String = ""
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
            "status" to view.status.name,
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
            "orderId" to view.orderId,
            "rejectCode" to view.rejectCode,
            "resultPayloadJson" to view.resultPayloadJson
        )
    }

    fun acceptedJson(view: CommandStatusView): String {
        return JsonCodec.writeObject(
            "commandId" to view.commandId,
            "status" to view.status.name,
            "processingMode" to view.processingMode.configValue,
            "statusUrl" to "/api/v1/commands/${view.commandId}"
        )
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
        lastError = lastError
    )
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
        orderId = orderId,
        rejectCode = rejectCode,
        resultPayloadJson = resultPayloadJson
    )
}
