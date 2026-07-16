package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.util.concurrent.CompletableFuture

internal class StreamIngressSubmitHandler(
    private val maxRequestBodyBytes: Int,
    private val commandProcessingMode: CommandProcessingMode,
    private val defaultClientId: String = RuntimeEnv.string("STREAM_INGRESS_DEFAULT_CLIENT_ID", "stream-ingress"),
    private val submit: (
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ) -> CompletableFuture<PlatformHotPathResponse>
) {
    fun handle(body: String): CompletableFuture<PlatformHotPathResponse> {
        if (body.length > maxRequestBodyBytes) {
            return CompletableFuture.completedFuture(
                PlatformHotPathResponse(
                    413,
                    JsonCodec.writeObject("error" to "request body too large", "maxBytes" to maxRequestBodyBytes)
                )
            )
        }
        if (commandProcessingMode != CommandProcessingMode.StreamAck) {
            return CompletableFuture.completedFuture(
                PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "stream command intake unavailable"))
            )
        }

        val route = "/api/v1/orders/submit"
        val json = try {
            JsonCodec.parseObject(body)
        } catch (ex: IllegalArgumentException) {
            return CompletableFuture.completedFuture(
                PlatformHotPathResponse(
                    400,
                    JsonCodec.writeObject("error" to "INVALID_JSON", "message" to (ex.message ?: "invalid json payload"))
                )
            )
        }
        val commandId = json.string("commandId")
        val traceId = json.string("traceId")
        val correlationId = json.string("correlationId").ifBlank { traceId }
        val clientId = json.string("clientId").ifBlank { defaultClientId }
        val idempotencyKey = json.string("idempotencyKey").ifBlank { commandId }
        if (clientId.isBlank() || idempotencyKey.isBlank()) {
            return CompletableFuture.completedFuture(
                PlatformHotPathResponse(
                    400,
                    JsonCodec.writeObject("error" to "STREAM_INGRESS_METADATA_REQUIRED")
                )
            )
        }
        val validation = HotPathMetrics.time("streamIngress.command.validate") {
            PlatformCommandParsers.validateApiV1Command(route, json)
        }
        if (validation is ApiV1CommandValidation.Invalid) {
            return CompletableFuture.completedFuture(
                PlatformHotPathResponse(
                    400,
                    JsonCodec.writeObject("error" to "VALIDATION_ERROR", "message" to validation.error)
                )
            )
        }
        return submit(route, clientId, idempotencyKey, correlationId, body)
    }
}
