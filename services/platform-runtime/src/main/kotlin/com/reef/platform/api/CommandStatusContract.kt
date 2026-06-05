package com.reef.platform.api

data class CommandStatusView(
    val commandId: String,
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val status: CommandLogStatus,
    val processingMode: CommandProcessingMode,
    val responseStatus: Int,
    val responsePayloadJson: String,
    val lastError: String
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
            "lastError" to view.lastError
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
