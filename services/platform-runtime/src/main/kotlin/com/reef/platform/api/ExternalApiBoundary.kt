package com.reef.platform.api

import com.sun.net.httpserver.Headers

class ExternalApiBoundary(
    private val authHook: AuthHook = AllowAllAuthHook(),
    private val rateLimitHook: RateLimitHook = AllowAllRateLimitHook(),
    private val idempotencyPolicy: IdempotencyPolicy = RequiredIdempotencyPolicy()
) {
    fun checkWrite(headers: Headers, route: String): BoundaryError? {
        val clientId = clientId(headers)
            ?: return BoundaryError(401, "CLIENT_ID_REQUIRED", "missing X-Client-Id header")

        val authError = authHook.authorize(clientId, headers.firstValue("Authorization"))
        if (authError != null) return authError

        val rateLimitError = rateLimitHook.allow(clientId, route)
        if (rateLimitError != null) return rateLimitError

        return idempotencyPolicy.validate(clientId, route, idempotencyKey(headers))
    }

    fun checkRead(headers: Headers, route: String): BoundaryError? {
        val clientId = clientId(headers)
            ?: return BoundaryError(401, "CLIENT_ID_REQUIRED", "missing X-Client-Id header")

        val authError = authHook.authorize(clientId, headers.firstValue("Authorization"))
        if (authError != null) return authError

        return rateLimitHook.allow(clientId, route)
    }

    fun checkParticipantRead(headers: Headers, route: String, participantId: String): BoundaryError? {
        val baseError = checkRead(headers, route)
        if (baseError != null) return baseError

        return checkParticipantScope(headers, participantId)
    }

    fun checkParticipantScope(headers: Headers, participantId: String): BoundaryError? {
        val clientId = clientId(headers)
            ?: return BoundaryError(401, "CLIENT_ID_REQUIRED", "missing X-Client-Id header")
        val principal = authHook.principal(clientId)
        if (principal.participantIds.isNotEmpty()) {
            return requireScope("participant", participantId, principal.participantIds)
        }

        val headerParticipant = headers.firstValue("X-Participant-Id").orEmpty()
        if (headerParticipant.isBlank()) {
            return BoundaryError(403, "OBJECT_AUTH_REQUIRED", "missing X-Participant-Id header")
        }
        if (participantId.isBlank() || participantId != headerParticipant) {
            return BoundaryError(403, "OBJECT_AUTH_DENIED", "participant scope does not match authenticated principal")
        }
        return null
    }

    /**
     * Strict account-scoped read: the principal must be explicitly scoped to [accountId]. Unlike [requireScope], an empty account scope
     * is a refusal, never "unrestricted", so a principal with no account mapping cannot enumerate other participants' accounts.
     */
    fun checkAccountRead(headers: Headers, route: String, accountId: String): BoundaryError? {
        val baseError = checkRead(headers, route)
        if (baseError != null) return baseError
        val principal = authHook.principal(clientId(headers).orEmpty())
        if (principal.accountIds.isEmpty()) return BoundaryError(403, "OBJECT_AUTH_REQUIRED", "principal has no account scope")
        if (accountId.isBlank()) return BoundaryError(403, "OBJECT_AUTH_REQUIRED", "missing account identity")
        if (accountId !in principal.accountIds) return BoundaryError(403, "OBJECT_AUTH_DENIED", "account scope does not match authenticated principal")
        return null
    }

    fun checkOrderMutationIdentity(headers: Headers, command: JsonDocument): BoundaryError? {
        val clientId = clientId(headers)
            ?: return BoundaryError(401, "CLIENT_ID_REQUIRED", "missing X-Client-Id header")
        val principal = authHook.principal(clientId)

        val actorError = requireScope("actor", command.string("actorId"), principal.actorIds)
        if (actorError != null) return actorError

        val participantError = requireScope("participant", command.string("participantId"), principal.participantIds)
        if (participantError != null) return participantError
        val accountError = requireScope("account", command.string("accountId"), principal.accountIds)
        if (accountError != null) return accountError

        val botId = command.string("botId")
        if (botId.isNotBlank()) {
            val botError = requireScope("bot", botId, principal.botIds)
            if (botError != null) return botError
        }
        return null
    }

    fun toErrorJson(error: BoundaryError, correlationId: String): String {
        return """
            {
              "code":"${JsonCodec.escapeString(error.code)}",
              "message":"${JsonCodec.escapeString(error.message)}",
              "correlationId":"${JsonCodec.escapeString(correlationId)}"
            }
        """.trimIndent()
    }

    fun clientId(headers: Headers): String? = headers.firstValue("X-Client-Id")
    fun idempotencyKey(headers: Headers): String? = headers.firstValue("Idempotency-Key")

    private fun requireScope(name: String, value: String, allowed: Set<String>): BoundaryError? {
        if (allowed.isEmpty()) return null
        if (value.isBlank()) {
            return BoundaryError(403, "OBJECT_AUTH_REQUIRED", "missing $name identity")
        }
        if (!allowed.contains(value)) {
            return BoundaryError(403, "OBJECT_AUTH_DENIED", "$name scope does not match authenticated principal")
        }
        return null
    }
}

private fun Headers.firstValue(name: String): String? {
    return this[name]?.firstOrNull()
}

data class BoundaryHooks(
    val authHook: AuthHook,
    val rateLimitHook: RateLimitHook,
    val abuseProtectionHook: AbuseProtectionHook,
    val accountRiskCheck: AccountRiskCheck,
    val commandCircuitBreakerCheck: CommandCircuitBreakerCheck,
    val instrumentPriceCollarCheck: InstrumentPriceCollarCheck,
    val boundaryRejectionLog: BoundaryRejectionLog,
    val idempotencyStore: IdempotencyStore,
    val idempotencyRetentionPolicy: IdempotencyRetentionPolicy,
    val commandCaptureStore: CommandCaptureStore,
    val commandProcessingMode: CommandProcessingMode
)
