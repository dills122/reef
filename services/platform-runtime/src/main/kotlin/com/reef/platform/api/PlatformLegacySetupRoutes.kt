package com.reef.platform.api

import com.sun.net.httpserver.Headers

internal const val LEGACY_INTERNAL_ROUTE_HEADER = "X-Reef-Internal-Route"

internal class PlatformLegacySetupRoutes(
    private val maxRequestBodyBytes: Int,
    private val legacyMutationRoutesEnabled: Boolean,
    private val createInstrument: (String) -> String,
    private val instruments: () -> String,
    private val createParticipant: (String) -> String,
    private val participants: () -> String,
    private val createAccount: (String) -> String,
    private val accounts: () -> String,
    private val createRole: (String) -> String,
    private val roles: () -> String,
    private val assignRole: (String) -> String,
    private val actorRoles: (String) -> String
) {
    fun handle(request: PlatformHotPathRequest): PlatformHotPathResponse? {
        return when (request.path) {
            "/reference/instruments" -> handleSetupRequest(request, createInstrument, instruments)
            "/reference/participants" -> handleSetupRequest(request, createParticipant, participants)
            "/reference/accounts" -> handleSetupRequest(request, createAccount, accounts)
            "/auth/roles" -> handleSetupRequest(request, createRole, roles)
            "/auth/actor-roles" -> handleSetupRequest(
                request,
                assignRole,
                getOperation = { actorRoles(queryValue(request.query, "actorId")) }
            )
            else -> null
        }
    }

    private fun handleSetupRequest(
        request: PlatformHotPathRequest,
        postOperation: (String) -> String,
        getOperation: (() -> String)? = null
    ): PlatformHotPathResponse {
        return when (request.method) {
            "POST" -> handlePost(request, postOperation)
            "GET" -> handleGet(request.headers, getOperation)
            else -> methodNotAllowedResponse()
        }
    }

    private fun handlePost(
        request: PlatformHotPathRequest,
        postOperation: (String) -> String
    ): PlatformHotPathResponse {
        allowLegacyMutationRoute(request.headers)?.let { return it }
        if (request.body.toByteArray().size > maxRequestBodyBytes) {
            return PlatformHotPathResponse(
                413,
                JsonCodec.writeObject("error" to "request body too large", "maxBytes" to maxRequestBodyBytes)
            )
        }
        return try {
            PlatformHotPathResponse(200, postOperation(request.body))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, runtimeUnavailableJson(ex))
        }
    }

    private fun handleGet(
        headers: Headers,
        getOperation: (() -> String)?
    ): PlatformHotPathResponse {
        val operation = getOperation ?: return methodNotAllowedResponse()
        allowLegacyMutationRoute(headers)?.let { return it }
        return try {
            PlatformHotPathResponse(200, operation())
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, runtimeUnavailableJson(ex))
        }
    }

    private fun allowLegacyMutationRoute(headers: Headers): PlatformHotPathResponse? {
        if (!legacyMutationRoutesEnabled) {
            return PlatformHotPathResponse(403, simpleErrorJson("legacy mutation route disabled"))
        }
        val internalMarker = headers[LEGACY_INTERNAL_ROUTE_HEADER]?.firstOrNull()
        if (internalMarker != "true") {
            return PlatformHotPathResponse(
                403,
                JsonCodec.writeObject(
                    "error" to "legacy mutation route requires internal marker",
                    "header" to LEGACY_INTERNAL_ROUTE_HEADER
                )
            )
        }
        return null
    }

    private fun runtimeUnavailableJson(ex: Exception): String {
        return simpleErrorJson("runtime unavailable", ex.message ?: "unknown")
    }

    private fun simpleErrorJson(error: String, message: String? = null): String {
        return if (message == null) {
            JsonCodec.writeObject("error" to error)
        } else {
            JsonCodec.writeObject("error" to error, "message" to message)
        }
    }
}
