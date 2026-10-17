package com.reef.platform.api

import java.time.Instant

data class BoundaryError(
    val status: Int,
    val code: String,
    val message: String
)

data class ExternalApiPrincipal(
    val clientId: String,
    val actorIds: Set<String> = emptySet(),
    val participantIds: Set<String> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    val botIds: Set<String> = emptySet()
) {
    fun hasIdentityScope(): Boolean {
        return actorIds.isNotEmpty() || participantIds.isNotEmpty() || accountIds.isNotEmpty() || botIds.isNotEmpty()
    }

    fun hasOrderMutationScopes(): Boolean {
        return actorIds.isNotEmpty() && participantIds.isNotEmpty() && accountIds.isNotEmpty()
    }
}

interface AuthHook {
    fun authorize(clientId: String, token: String?): BoundaryError?
    fun principal(clientId: String): ExternalApiPrincipal = ExternalApiPrincipal(clientId)
}

interface RateLimitHook {
    fun allow(clientId: String, route: String): BoundaryError?
}

interface IdempotencyPolicy {
    fun validate(clientId: String, route: String, idempotencyKey: String?): BoundaryError?
}

interface AbuseProtectionHook {
    fun allow(clientId: String, route: String): BoundaryError?
    fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?)
    fun stats(): AbuseProtectionStats
}

data class AccountRiskCheckRequest(
    val clientId: String,
    val route: String,
    val commandType: String,
    val commandId: String,
    val idempotencyKey: String,
    val correlationId: String,
    val actorId: String,
    val participantId: String,
    val accountId: String,
    val botId: String,
    val botVersion: String = "",
    val runId: String,
    val venueSessionId: String,
    val instrumentId: String,
    val orderId: String,
    val quantityUnits: String = "",
    val limitPrice: String = "",
    val currency: String = "",
    val payloadHash: String
)

enum class AccountRiskDecision {
    ALLOW,
    REJECT,
    BACKPRESSURE,
    DISABLED_BOT
}

data class AccountRiskCheckResult(
    val decision: AccountRiskDecision,
    val code: String = decision.defaultCode(),
    val message: String = decision.defaultMessage()
) {
    fun toBoundaryError(): BoundaryError? {
        return when (decision) {
            AccountRiskDecision.ALLOW -> null
            AccountRiskDecision.REJECT -> BoundaryError(403, code, message)
            AccountRiskDecision.BACKPRESSURE -> BoundaryError(429, code, message)
            AccountRiskDecision.DISABLED_BOT -> BoundaryError(403, code, message)
        }
    }

    private companion object {
        fun AccountRiskDecision.defaultCode(): String {
            return when (this) {
                AccountRiskDecision.ALLOW -> "ACCOUNT_RISK_ALLOWED"
                AccountRiskDecision.REJECT -> "ACCOUNT_RISK_REJECTED"
                AccountRiskDecision.BACKPRESSURE -> "ACCOUNT_RISK_BACKPRESSURE"
                AccountRiskDecision.DISABLED_BOT -> "BOT_DISABLED"
            }
        }

        fun AccountRiskDecision.defaultMessage(): String {
            return when (this) {
                AccountRiskDecision.ALLOW -> "account risk check allowed command"
                AccountRiskDecision.REJECT -> "account risk check rejected command"
                AccountRiskDecision.BACKPRESSURE -> "account risk check requested backpressure"
                AccountRiskDecision.DISABLED_BOT -> "bot is disabled"
            }
        }
    }
}

interface AccountRiskCheck {
    fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult
}

/**
 * Optional, product-neutral pre-acceptance policy. Returning null delegates to
 * the next check; a non-allow result stops command acceptance before durable
 * ingress. Implementations belong to the optional product that owns the
 * policy, not to the Reef boundary.
 */
fun interface AccountRiskCheckExtension {
    fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult?
}

fun interface AccountRiskCheckExtensionProvider {
    fun extensions(lookup: (String) -> String?): List<AccountRiskCheckExtension>
}

class ChainedAccountRiskCheck(
    private val primary: AccountRiskCheck,
    private val extensions: List<AccountRiskCheckExtension>
) : AccountRiskCheck {
    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        extensions.forEach { extension ->
            extension.evaluate(request)?.let { return it }
        }
        return primary.evaluate(request)
    }
}

data class AccountRiskControl(
    val scopeType: String,
    val scopeId: String,
    val decision: AccountRiskDecision,
    val reason: String,
    val maxQuantityUnits: String = "",
    val maxNotional: String = "",
    val currency: String = "",
    val updatedAt: String = ""
)

data class AccountRiskDecisionAudit(
    val decisionId: String,
    val decidedAt: String,
    val decision: AccountRiskDecision,
    val code: String,
    val message: String,
    val clientId: String,
    val route: String,
    val commandType: String,
    val commandId: String,
    val correlationId: String,
    val actorId: String,
    val participantId: String,
    val accountId: String,
    val botId: String,
    val venueSessionId: String,
    val instrumentId: String,
    val orderId: String,
    val quantityUnits: String,
    val limitPrice: String,
    val currency: String
)

interface AccountRiskControlStore {
    fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String = "",
        maxQuantityUnits: String = "",
        maxNotional: String = "",
        currency: String = ""
    )
    fun listControls(): List<AccountRiskControl>
}

interface AccountRiskDecisionLog {
    fun recentDecisions(limit: Int = 50): List<AccountRiskDecisionAudit>
}

data class CommandCircuitBreakerRequest(
    val clientId: String,
    val route: String,
    val commandType: String,
    val commandId: String,
    val correlationId: String,
    val venueSessionId: String,
    val instrumentId: String
)

data class CommandCircuitBreakerState(
    val scopeType: String,
    val scopeId: String,
    val tripped: Boolean,
    val reason: String,
    val updatedAt: String = ""
)

interface CommandCircuitBreakerCheck {
    fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError?
}

interface CommandCircuitBreakerStore : CommandCircuitBreakerCheck {
    fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String = "")
    fun listBreakers(): List<CommandCircuitBreakerState>
}

data class InstrumentPriceCollarRequest(
    val clientId: String,
    val route: String,
    val commandType: String,
    val commandId: String,
    val correlationId: String,
    val instrumentId: String,
    val limitPrice: String,
    val currency: String
)

data class InstrumentPriceCollarState(
    val instrumentId: String,
    val minPrice: String,
    val maxPrice: String,
    val currency: String,
    val reason: String,
    val updatedAt: String = ""
)

interface InstrumentPriceCollarCheck {
    fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError?
}

interface InstrumentPriceCollarStore : InstrumentPriceCollarCheck {
    fun setCollar(
        instrumentId: String,
        minPrice: String = "",
        maxPrice: String = "",
        currency: String = "",
        reason: String = ""
    )
    fun listCollars(): List<InstrumentPriceCollarState>
}

interface BoundaryRejectionLog {
    fun recordRejection(
        guardrailType: String,
        scopeType: String,
        scopeId: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    )
}

data class AbuseProtectionStats(
    val mode: String,
    val enabled: Boolean,
    val warningOnly: Boolean,
    val maxRejects: Int,
    val windowSeconds: Long,
    val blockSeconds: Long,
    val trackedRejectCodes: Set<String>,
    val trackedRoutes: Set<String>,
    val routePolicyOverrides: Map<String, String>,
    val trips: Long,
    val blocks: Long,
    val releases: Long,
    val activeBlockedClients: Int
)

interface IdempotencyStore {
    fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult?
    fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass)
    fun cleanupExpired(now: Instant = Instant.now()) {}
}

data class IdempotencyResult(
    val status: Int,
    val payload: String
)

enum class IdempotencyTtlClass {
    SHORT,
    STANDARD,
    LONG
}

interface IdempotencyRetentionPolicy {
    fun ttlFor(route: String): IdempotencyTtlClass
    fun durationSeconds(ttlClass: IdempotencyTtlClass): Long
}

class DefaultIdempotencyRetentionPolicy : IdempotencyRetentionPolicy {
    override fun ttlFor(route: String): IdempotencyTtlClass {
        return when {
            route.contains("/cancel") || route.contains("/modify") -> IdempotencyTtlClass.STANDARD
            else -> IdempotencyTtlClass.LONG
        }
    }

    override fun durationSeconds(ttlClass: IdempotencyTtlClass): Long {
        return when (ttlClass) {
            IdempotencyTtlClass.SHORT -> 15 * 60L
            IdempotencyTtlClass.STANDARD -> 24 * 60 * 60L
            IdempotencyTtlClass.LONG -> 7 * 24 * 60 * 60L
        }
    }
}
