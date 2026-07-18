package com.reef.platform.api

import com.sun.net.httpserver.Headers

internal data class PlatformHotPathRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Headers,
    val remoteAddress: String? = null,
    val body: String = ""
)

data class PlatformHotPathResponse(
    val status: Int,
    val body: String = "",
    val contentType: String? = "application/json"
)

fun parseGatewayJson(body: String): JsonDocument? {
    return try {
        JsonCodec.parseObject(body)
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun invalidJsonPayloadResponse(): PlatformHotPathResponse {
    return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid json payload"))
}
