package com.reef.platform.api

import com.sun.net.httpserver.Headers
import java.util.concurrent.CompletableFuture

data class BotRuntimeOrderCommand(
    val route: String,
    val headers: Map<String, String>,
    val body: String,
    val method: String = "POST"
)

data class BotRuntimeOrderResponse(
    val route: String,
    val status: Int,
    val body: String,
    val commandId: String
)

class BotRuntimeOrderClient(
    private val platform: PlatformHttpServer
) {
    fun send(command: BotRuntimeOrderCommand): BotRuntimeOrderResponse {
        val response = platform.handleHotPathRequest(command.toHotPathRequest())
            ?: PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "bot order route not found"))
        return command.toBotResponse(response)
    }

    fun sendAsync(command: BotRuntimeOrderCommand): CompletableFuture<BotRuntimeOrderResponse> {
        return platform.handleHotPathRequestAsync(command.toHotPathRequest())
            .thenApply { response ->
                command.toBotResponse(response ?: PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "bot order route not found")))
            }
    }

    private fun BotRuntimeOrderCommand.toHotPathRequest(): PlatformHotPathRequest {
        require(route in apiV1OrderMutationRoutes) {
            "route must be one of ${apiV1OrderMutationRoutes.joinToString(",")}"
        }
        val hotPathHeaders = Headers()
        headers.forEach { (name, value) -> hotPathHeaders.add(name, value) }
        return PlatformHotPathRequest(
            method = method,
            path = route,
            query = null,
            headers = hotPathHeaders,
            body = body
        )
    }

    private fun BotRuntimeOrderCommand.toBotResponse(response: PlatformHotPathResponse): BotRuntimeOrderResponse {
        return BotRuntimeOrderResponse(
            route = route,
            status = response.status,
            body = response.body,
            commandId = JsonCodec.fieldAsString(body, "commandId")
        )
    }
}
