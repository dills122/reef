package com.reef.platform.api

import java.time.Instant

class AllowAllAccountRiskCheck : AccountRiskCheck {
    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        return AccountRiskCheckResult(AccountRiskDecision.ALLOW)
    }
}

class AllowAllCommandCircuitBreakerCheck : CommandCircuitBreakerCheck {
    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? = null
}

class AllowAllInstrumentPriceCollarCheck : InstrumentPriceCollarCheck {
    override fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError? = null
}

class NoopBoundaryRejectionLog : BoundaryRejectionLog {
    override fun recordRejection(
        guardrailType: String,
        scopeType: String,
        scopeId: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ) {}
}

class StaticAccountRiskCheck(
    private val rejectedAccounts: Set<String> = emptySet(),
    private val backpressuredAccounts: Set<String> = emptySet(),
    private val disabledBots: Set<String> = emptySet()
) : AccountRiskCheck {
    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        return when {
            request.botId.isNotBlank() && request.botId in disabledBots ->
                AccountRiskCheckResult(AccountRiskDecision.DISABLED_BOT)
            request.accountId.isNotBlank() && request.accountId in backpressuredAccounts ->
                AccountRiskCheckResult(AccountRiskDecision.BACKPRESSURE)
            request.accountId.isNotBlank() && request.accountId in rejectedAccounts ->
                AccountRiskCheckResult(AccountRiskDecision.REJECT)
            else -> AccountRiskCheckResult(AccountRiskDecision.ALLOW)
        }
    }
}

class AllowAllAuthHook : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? = null
}

data class StaticTokenClientConfig(
    val token: String,
    val principal: ExternalApiPrincipal
)

class StaticTokenAuthHook(
    private val clientsById: Map<String, StaticTokenClientConfig>
) : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? {
        val expected = clientsById[clientId]?.token
            ?: return BoundaryError(401, "UNAUTHORIZED", "unknown client id")
        val provided = token?.removePrefix("Bearer ")?.trim()
        if (provided.isNullOrBlank() || provided != expected) {
            return BoundaryError(401, "UNAUTHORIZED", "invalid token")
        }
        return null
    }

    override fun principal(clientId: String): ExternalApiPrincipal {
        return clientsById[clientId]?.principal ?: ExternalApiPrincipal(clientId)
    }
}

class AllowAllRateLimitHook : RateLimitHook {
    override fun allow(clientId: String, route: String): BoundaryError? = null
}

class AllowAllAbuseProtectionHook : AbuseProtectionHook {
    override fun allow(clientId: String, route: String): BoundaryError? = null

    override fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?) {}

    override fun stats(): AbuseProtectionStats {
        return AbuseProtectionStats(
            mode = "off",
            enabled = false,
            warningOnly = false,
            maxRejects = 0,
            windowSeconds = 0,
            blockSeconds = 0,
            trackedRejectCodes = emptySet(),
            trackedRoutes = emptySet(),
            routePolicyOverrides = emptyMap(),
            trips = 0,
            blocks = 0,
            releases = 0,
            activeBlockedClients = 0
        )
    }
}

class FixedWindowRateLimitHook(
    private val store: RateLimitStore,
    private val maxRequests: Int,
    private val windowSeconds: Long,
    private val clock: () -> Instant = { Instant.now() }
) : RateLimitHook {
    override fun allow(clientId: String, route: String): BoundaryError? {
        if (maxRequests <= 0 || windowSeconds <= 0) return null
        val now = clock()
        val windowStart = now.epochSecond / windowSeconds * windowSeconds
        val count = store.increment(clientId, route, windowStart, windowSeconds)
        if (count > maxRequests) {
            return BoundaryError(429, "RATE_LIMITED", "rate limit exceeded")
        }
        return null
    }
}

class RequiredIdempotencyPolicy : IdempotencyPolicy {
    override fun validate(clientId: String, route: String, idempotencyKey: String?): BoundaryError? {
        if (idempotencyKey.isNullOrBlank()) {
            return BoundaryError(
                status = 400,
                code = "IDEMPOTENCY_KEY_REQUIRED",
                message = "idempotency key header is required for mutating requests"
            )
        }
        return null
    }
}
