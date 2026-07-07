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

internal data class PlatformHotPathResponse(
    val status: Int,
    val body: String = "",
    val contentType: String? = "application/json"
)
