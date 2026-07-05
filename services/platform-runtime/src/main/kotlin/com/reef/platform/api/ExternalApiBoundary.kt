package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.sun.net.httpserver.Headers
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class BoundaryError(
    val status: Int,
    val code: String,
    val message: String
)

interface AuthHook {
    fun authorize(clientId: String, token: String?): BoundaryError?
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

class PostgresAccountRiskCheck(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val cacheTtlMillis: Long = 1_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : AccountRiskCheck, AccountRiskControlStore, AccountRiskDecisionLog {
    private data class CachedControl(
        val control: AccountRiskControl?,
        val expiresAtMillis: Long
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedControl>()

    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryAccountRisk(
                        names.accountRiskControls,
                        names.accountRiskDecisions
                    )
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.accountRiskControls} (
                      scope_type TEXT NOT NULL,
                      scope_id TEXT NOT NULL,
                      decision TEXT NOT NULL,
                      reason TEXT NOT NULL DEFAULT '',
                      max_quantity_units TEXT NOT NULL DEFAULT '',
                      max_notional TEXT NOT NULL DEFAULT '',
                      currency TEXT NOT NULL DEFAULT '',
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      PRIMARY KEY (scope_type, scope_id),
                      CHECK (scope_type IN ('ACCOUNT', 'BOT')),
                      CHECK (decision IN ('ALLOW', 'REJECT', 'BACKPRESSURE', 'DISABLED_BOT'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.accountRiskDecisions} (
                      decision_id TEXT NOT NULL PRIMARY KEY,
                      decided_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      decision TEXT NOT NULL,
                      code TEXT NOT NULL,
                      message TEXT NOT NULL,
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      command_type TEXT NOT NULL,
                      command_id TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      actor_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      bot_id TEXT NOT NULL,
                      run_id TEXT NOT NULL,
                      venue_session_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      quantity_units TEXT NOT NULL DEFAULT '',
                      limit_price TEXT NOT NULL DEFAULT '',
                      currency TEXT NOT NULL DEFAULT '',
                      payload_hash TEXT NOT NULL,
                      CHECK (decision IN ('REJECT', 'BACKPRESSURE', 'DISABLED_BOT'))
                    )
                    """.trimIndent()
                )
                stmt.execute("ALTER TABLE ${names.accountRiskControls} ADD COLUMN IF NOT EXISTS max_quantity_units TEXT NOT NULL DEFAULT ''")
                stmt.execute("ALTER TABLE ${names.accountRiskControls} ADD COLUMN IF NOT EXISTS max_notional TEXT NOT NULL DEFAULT ''")
                stmt.execute("ALTER TABLE ${names.accountRiskControls} ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT ''")
                stmt.execute("ALTER TABLE ${names.accountRiskDecisions} ADD COLUMN IF NOT EXISTS quantity_units TEXT NOT NULL DEFAULT ''")
                stmt.execute("ALTER TABLE ${names.accountRiskDecisions} ADD COLUMN IF NOT EXISTS limit_price TEXT NOT NULL DEFAULT ''")
                stmt.execute("ALTER TABLE ${names.accountRiskDecisions} ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT ''")
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_account_risk_decisions_scope_time
                      ON ${names.accountRiskDecisions}(account_id, bot_id, decided_at DESC)
                    """.trimIndent()
                )
            }
        }
    }

    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        val controls = listOfNotNull(
            if (request.botId.isBlank()) null else controlFor("BOT", request.botId),
            if (request.accountId.isBlank()) null else controlFor("ACCOUNT", request.accountId)
        )
        val decision = firstNonAllow(controls.map { it.toDecision() })
            ?: controls.firstNotNullOfOrNull { limitViolation(it, request) }
            ?: return AccountRiskCheckResult(AccountRiskDecision.ALLOW)

        recordDecision(request, decision)
        return decision
    }

    override fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String,
        maxQuantityUnits: String,
        maxNotional: String,
        currency: String
    ) {
        require(scopeType == "ACCOUNT" || scopeType == "BOT") { "scopeType must be ACCOUNT or BOT" }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.accountRiskControls}(
                  scope_type,
                  scope_id,
                  decision,
                  reason,
                  max_quantity_units,
                  max_notional,
                  currency,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (scope_type, scope_id)
                DO UPDATE SET decision = EXCLUDED.decision,
                              reason = EXCLUDED.reason,
                              max_quantity_units = EXCLUDED.max_quantity_units,
                              max_notional = EXCLUDED.max_notional,
                              currency = EXCLUDED.currency,
                              updated_at = NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scopeType)
                ps.setString(2, scopeId)
                ps.setString(3, decision.name)
                ps.setString(4, reason)
                ps.setString(5, maxQuantityUnits)
                ps.setString(6, maxNotional)
                ps.setString(7, currency)
                ps.executeUpdate()
            }
        }
        cache.remove("$scopeType|$scopeId")
    }

    override fun listControls(): List<AccountRiskControl> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT scope_type, scope_id, decision, reason, max_quantity_units, max_notional, currency, updated_at
                FROM ${names.accountRiskControls}
                ORDER BY scope_type, scope_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<AccountRiskControl>()
                    while (rs.next()) {
                        rows.add(
                            AccountRiskControl(
                                scopeType = rs.getString("scope_type"),
                                scopeId = rs.getString("scope_id"),
                                decision = AccountRiskDecision.valueOf(rs.getString("decision")),
                                reason = rs.getString("reason").orEmpty(),
                                maxQuantityUnits = rs.getString("max_quantity_units").orEmpty(),
                                maxNotional = rs.getString("max_notional").orEmpty(),
                                currency = rs.getString("currency").orEmpty(),
                                updatedAt = rs.getString("updated_at").orEmpty()
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    override fun recentDecisions(limit: Int): List<AccountRiskDecisionAudit> {
        val boundedLimit = limit.coerceIn(1, 500)
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT decision_id,
                       decided_at,
                       decision,
                       code,
                       message,
                       client_id,
                       route,
                       command_type,
                       command_id,
                       correlation_id,
                       actor_id,
                       participant_id,
                       account_id,
                       bot_id,
                       venue_session_id,
                       instrument_id,
                       order_id,
                       quantity_units,
                       limit_price,
                       currency
                FROM ${names.accountRiskDecisions}
                ORDER BY decided_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, boundedLimit)
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<AccountRiskDecisionAudit>()
                    while (rs.next()) {
                        rows.add(
                            AccountRiskDecisionAudit(
                                decisionId = rs.getString("decision_id"),
                                decidedAt = rs.getString("decided_at"),
                                decision = AccountRiskDecision.valueOf(rs.getString("decision")),
                                code = rs.getString("code"),
                                message = rs.getString("message"),
                                clientId = rs.getString("client_id"),
                                route = rs.getString("route"),
                                commandType = rs.getString("command_type"),
                                commandId = rs.getString("command_id"),
                                correlationId = rs.getString("correlation_id"),
                                actorId = rs.getString("actor_id"),
                                participantId = rs.getString("participant_id"),
                                accountId = rs.getString("account_id"),
                                botId = rs.getString("bot_id"),
                                venueSessionId = rs.getString("venue_session_id"),
                                instrumentId = rs.getString("instrument_id"),
                                orderId = rs.getString("order_id"),
                                quantityUnits = rs.getString("quantity_units"),
                                limitPrice = rs.getString("limit_price"),
                                currency = rs.getString("currency")
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    private fun firstNonAllow(decisions: List<AccountRiskCheckResult>): AccountRiskCheckResult? {
        return decisions.firstOrNull { it.decision != AccountRiskDecision.ALLOW }
    }

    private fun AccountRiskControl.toDecision(): AccountRiskCheckResult {
        return AccountRiskCheckResult(
            decision = decision,
            message = reason.ifBlank { AccountRiskCheckResult(decision).message }
        )
    }

    private fun limitViolation(control: AccountRiskControl, request: AccountRiskCheckRequest): AccountRiskCheckResult? {
        if (request.commandType != "SubmitOrder") return null
        val quantity = request.quantityUnits.toBigDecimalOrNull() ?: return null
        val maxQuantity = control.maxQuantityUnits.toBigDecimalOrNull()
        if (maxQuantity != null && quantity > maxQuantity) {
            return AccountRiskCheckResult(
                decision = AccountRiskDecision.REJECT,
                code = "ACCOUNT_RISK_MAX_QUANTITY_EXCEEDED",
                message = "max quantity exceeded for ${control.scopeType}:${control.scopeId}: ${request.quantityUnits} > ${control.maxQuantityUnits}"
            )
        }
        val maxNotional = control.maxNotional.toBigDecimalOrNull()
        if (maxNotional != null) {
            val price = request.limitPrice.toBigDecimalOrNull() ?: return null
            val notional = quantity.multiply(price)
            val expectedCurrency = control.currency.trim()
            if (expectedCurrency.isBlank() || request.currency.equals(expectedCurrency, ignoreCase = true)) {
                if (notional > maxNotional) {
                    return AccountRiskCheckResult(
                        decision = AccountRiskDecision.REJECT,
                        code = "ACCOUNT_RISK_MAX_NOTIONAL_EXCEEDED",
                        message = "max notional exceeded for ${control.scopeType}:${control.scopeId}: ${notional.toPlainString()} > ${control.maxNotional}"
                    )
                }
            }
        }
        return null
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? {
        val trimmed = trim()
        if (trimmed.isBlank()) return null
        return try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun controlFor(scopeType: String, scopeId: String): AccountRiskControl? {
        val key = "$scopeType|$scopeId"
        val now = nowMillis()
        cache[key]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.control
        }
        val loaded = loadControl(scopeType, scopeId)
        if (cacheTtlMillis > 0) {
            cache[key] = CachedControl(loaded, now + cacheTtlMillis)
        }
        return loaded
    }

    private fun loadControl(scopeType: String, scopeId: String): AccountRiskControl? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT scope_type, scope_id, decision, reason, max_quantity_units, max_notional, currency, updated_at
                FROM ${names.accountRiskControls}
                WHERE scope_type = ? AND scope_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scopeType)
                ps.setString(2, scopeId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return AccountRiskControl(
                        scopeType = rs.getString("scope_type"),
                        scopeId = rs.getString("scope_id"),
                        decision = AccountRiskDecision.valueOf(rs.getString("decision")),
                        reason = rs.getString("reason").orEmpty(),
                        maxQuantityUnits = rs.getString("max_quantity_units").orEmpty(),
                        maxNotional = rs.getString("max_notional").orEmpty(),
                        currency = rs.getString("currency").orEmpty(),
                        updatedAt = rs.getString("updated_at").orEmpty()
                    )
                }
            }
        }
    }

    private fun recordDecision(request: AccountRiskCheckRequest, result: AccountRiskCheckResult) {
        if (result.decision == AccountRiskDecision.ALLOW) return
        try {
            connection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.accountRiskDecisions}(
                      decision_id,
                      decision,
                      code,
                      message,
                      client_id,
                      route,
                      command_type,
                      command_id,
                      idempotency_key,
                      correlation_id,
                      actor_id,
                      participant_id,
                      account_id,
                      bot_id,
                      run_id,
                      venue_session_id,
                      instrument_id,
                      order_id,
                      quantity_units,
                      limit_price,
                      currency,
                      payload_hash
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, result.decision.name)
                    ps.setString(3, result.code)
                    ps.setString(4, result.message)
                    ps.setString(5, request.clientId)
                    ps.setString(6, request.route)
                    ps.setString(7, request.commandType)
                    ps.setString(8, request.commandId)
                    ps.setString(9, request.idempotencyKey)
                    ps.setString(10, request.correlationId)
                    ps.setString(11, request.actorId)
                    ps.setString(12, request.participantId)
                    ps.setString(13, request.accountId)
                    ps.setString(14, request.botId)
                    ps.setString(15, request.runId)
                    ps.setString(16, request.venueSessionId)
                    ps.setString(17, request.instrumentId)
                    ps.setString(18, request.orderId)
                    ps.setString(19, request.quantityUnits)
                    ps.setString(20, request.limitPrice)
                    ps.setString(21, request.currency)
                    ps.setString(22, request.payloadHash)
                    ps.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            val message = ex.message ?: "unknown"
            System.err.println(
                "account_risk_audit_failed clientId=${request.clientId} route=${request.route} commandId=${request.commandId} decision=${result.decision} message=${JsonFields.escape(message)}"
            )
        }
    }

    private fun connection() = dataSource.connection
}

class PostgresCommandCircuitBreakerStore(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val cacheTtlMillis: Long = 1_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : CommandCircuitBreakerStore {
    private data class CachedState(
        val state: CommandCircuitBreakerState?,
        val expiresAtMillis: Long
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedState>()

    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryCommandCircuitBreakers(names.commandCircuitBreakers)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandCircuitBreakers} (
                      scope_type TEXT NOT NULL,
                      scope_id TEXT NOT NULL,
                      tripped BOOLEAN NOT NULL,
                      reason TEXT NOT NULL DEFAULT '',
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      PRIMARY KEY (scope_type, scope_id),
                      CHECK (scope_type IN ('GLOBAL', 'VENUE_SESSION', 'INSTRUMENT'))
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        val tripped = firstTripped(
            stateFor("GLOBAL", "*"),
            if (request.venueSessionId.isBlank()) null else stateFor("VENUE_SESSION", request.venueSessionId),
            if (request.instrumentId.isBlank()) null else stateFor("INSTRUMENT", request.instrumentId)
        ) ?: return null
        val suffix = if (tripped.reason.isBlank()) {
            ""
        } else {
            ": ${tripped.reason}"
        }
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for ${tripped.scopeType}:${tripped.scopeId}$suffix"
        )
    }

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        require(scopeType == "GLOBAL" || scopeType == "VENUE_SESSION" || scopeType == "INSTRUMENT") {
            "scopeType must be GLOBAL, VENUE_SESSION, or INSTRUMENT"
        }
        val normalizedScopeId = if (scopeType == "GLOBAL") "*" else scopeId
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.commandCircuitBreakers}(scope_type, scope_id, tripped, reason, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (scope_type, scope_id)
                DO UPDATE SET tripped = EXCLUDED.tripped, reason = EXCLUDED.reason, updated_at = NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scopeType)
                ps.setString(2, normalizedScopeId)
                ps.setBoolean(3, tripped)
                ps.setString(4, reason)
                ps.executeUpdate()
            }
        }
        cache.remove("$scopeType|$normalizedScopeId")
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT scope_type, scope_id, tripped, reason, updated_at
                FROM ${names.commandCircuitBreakers}
                ORDER BY scope_type, scope_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<CommandCircuitBreakerState>()
                    while (rs.next()) {
                        rows.add(
                            CommandCircuitBreakerState(
                                scopeType = rs.getString("scope_type"),
                                scopeId = rs.getString("scope_id"),
                                tripped = rs.getBoolean("tripped"),
                                reason = rs.getString("reason").orEmpty(),
                                updatedAt = rs.getString("updated_at").orEmpty()
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    private fun firstTripped(vararg states: CommandCircuitBreakerState?): CommandCircuitBreakerState? {
        return states.firstOrNull { it?.tripped == true }
    }

    private fun stateFor(scopeType: String, scopeId: String): CommandCircuitBreakerState? {
        val key = "$scopeType|$scopeId"
        val now = nowMillis()
        cache[key]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.state
        }
        val loaded = loadState(scopeType, scopeId)
        if (cacheTtlMillis > 0) {
            cache[key] = CachedState(loaded, now + cacheTtlMillis)
        }
        return loaded
    }

    private fun loadState(scopeType: String, scopeId: String): CommandCircuitBreakerState? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT scope_type, scope_id, tripped, reason, updated_at
                FROM ${names.commandCircuitBreakers}
                WHERE scope_type = ? AND scope_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scopeType)
                ps.setString(2, scopeId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return CommandCircuitBreakerState(
                        scopeType = rs.getString("scope_type"),
                        scopeId = rs.getString("scope_id"),
                        tripped = rs.getBoolean("tripped"),
                        reason = rs.getString("reason").orEmpty(),
                        updatedAt = rs.getString("updated_at").orEmpty()
                    )
                }
            }
        }
    }

    private fun connection() = dataSource.connection
}

class PostgresInstrumentPriceCollarStore(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val cacheTtlMillis: Long = 1_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : InstrumentPriceCollarStore {
    private data class CachedState(
        val state: InstrumentPriceCollarState?,
        val expiresAtMillis: Long
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedState>()

    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryInstrumentPriceCollars(names.instrumentPriceCollars)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.instrumentPriceCollars} (
                      instrument_id TEXT NOT NULL PRIMARY KEY,
                      min_price TEXT NOT NULL DEFAULT '',
                      max_price TEXT NOT NULL DEFAULT '',
                      currency TEXT NOT NULL DEFAULT '',
                      reason TEXT NOT NULL DEFAULT '',
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError? {
        if (request.commandType != "SubmitOrder") return null
        if (request.instrumentId.isBlank()) return null
        val collar = stateFor(request.instrumentId) ?: return null
        val expectedCurrency = collar.currency.trim()
        if (expectedCurrency.isNotBlank() && !request.currency.equals(expectedCurrency, ignoreCase = true)) {
            return null
        }
        val price = request.limitPrice.toBigDecimalOrNull() ?: return null
        val minPrice = collar.minPrice.toBigDecimalOrNull()
        if (minPrice != null && price < minPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_LOW",
                "limit price below collar for ${collar.instrumentId}: ${price.toPlainString()} < ${minPrice.toPlainString()}"
            )
        }
        val maxPrice = collar.maxPrice.toBigDecimalOrNull()
        if (maxPrice != null && price > maxPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_HIGH",
                "limit price above collar for ${collar.instrumentId}: ${price.toPlainString()} > ${maxPrice.toPlainString()}"
            )
        }
        return null
    }

    override fun setCollar(instrumentId: String, minPrice: String, maxPrice: String, currency: String, reason: String) {
        require(instrumentId.isNotBlank()) { "instrumentId is required" }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.instrumentPriceCollars}(instrument_id, min_price, max_price, currency, reason, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (instrument_id)
                DO UPDATE SET min_price = EXCLUDED.min_price,
                              max_price = EXCLUDED.max_price,
                              currency = EXCLUDED.currency,
                              reason = EXCLUDED.reason,
                              updated_at = NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.setString(2, minPrice)
                ps.setString(3, maxPrice)
                ps.setString(4, currency)
                ps.setString(5, reason)
                ps.executeUpdate()
            }
        }
        cache.remove(instrumentId)
    }

    override fun listCollars(): List<InstrumentPriceCollarState> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT instrument_id, min_price, max_price, currency, reason, updated_at
                FROM ${names.instrumentPriceCollars}
                ORDER BY instrument_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<InstrumentPriceCollarState>()
                    while (rs.next()) {
                        rows.add(
                            InstrumentPriceCollarState(
                                instrumentId = rs.getString("instrument_id"),
                                minPrice = rs.getString("min_price").orEmpty(),
                                maxPrice = rs.getString("max_price").orEmpty(),
                                currency = rs.getString("currency").orEmpty(),
                                reason = rs.getString("reason").orEmpty(),
                                updatedAt = rs.getString("updated_at").orEmpty()
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    private fun stateFor(instrumentId: String): InstrumentPriceCollarState? {
        val now = nowMillis()
        cache[instrumentId]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.state
        }
        val loaded = loadState(instrumentId)
        if (cacheTtlMillis > 0) {
            cache[instrumentId] = CachedState(loaded, now + cacheTtlMillis)
        }
        return loaded
    }

    private fun loadState(instrumentId: String): InstrumentPriceCollarState? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT instrument_id, min_price, max_price, currency, reason, updated_at
                FROM ${names.instrumentPriceCollars}
                WHERE instrument_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return InstrumentPriceCollarState(
                        instrumentId = rs.getString("instrument_id"),
                        minPrice = rs.getString("min_price").orEmpty(),
                        maxPrice = rs.getString("max_price").orEmpty(),
                        currency = rs.getString("currency").orEmpty(),
                        reason = rs.getString("reason").orEmpty(),
                        updatedAt = rs.getString("updated_at").orEmpty()
                    )
                }
            }
        }
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? {
        val trimmed = trim()
        if (trimmed.isBlank()) return null
        return try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun connection() = dataSource.connection
}

class PostgresBoundaryRejectionLog(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : BoundaryRejectionLog {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryRejections(names.boundaryRejections)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.boundaryRejections} (
                      rejection_id TEXT NOT NULL PRIMARY KEY,
                      rejected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      guardrail_type TEXT NOT NULL,
                      scope_type TEXT NOT NULL DEFAULT '',
                      scope_id TEXT NOT NULL DEFAULT '',
                      status INTEGER NOT NULL,
                      code TEXT NOT NULL,
                      message TEXT NOT NULL,
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      command_type TEXT NOT NULL,
                      command_id TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      actor_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      bot_id TEXT NOT NULL,
                      run_id TEXT NOT NULL,
                      venue_session_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      quantity_units TEXT NOT NULL DEFAULT '',
                      limit_price TEXT NOT NULL DEFAULT '',
                      currency TEXT NOT NULL DEFAULT '',
                      payload_hash TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_boundary_rejections_guardrail_time
                      ON ${names.boundaryRejections}(guardrail_type, rejected_at DESC)
                    """.trimIndent()
                )
            }
        }
    }

    override fun recordRejection(
        guardrailType: String,
        scopeType: String,
        scopeId: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.boundaryRejections}(
                  rejection_id,
                  guardrail_type,
                  scope_type,
                  scope_id,
                  status,
                  code,
                  message,
                  client_id,
                  route,
                  command_type,
                  command_id,
                  idempotency_key,
                  correlation_id,
                  actor_id,
                  participant_id,
                  account_id,
                  bot_id,
                  run_id,
                  venue_session_id,
                  instrument_id,
                  order_id,
                  quantity_units,
                  limit_price,
                  currency,
                  payload_hash
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, guardrailType)
                ps.setString(3, scopeType)
                ps.setString(4, scopeId)
                ps.setInt(5, error.status)
                ps.setString(6, error.code)
                ps.setString(7, error.message)
                ps.setString(8, request.clientId)
                ps.setString(9, request.route)
                ps.setString(10, request.commandType)
                ps.setString(11, request.commandId)
                ps.setString(12, request.idempotencyKey)
                ps.setString(13, request.correlationId)
                ps.setString(14, request.actorId)
                ps.setString(15, request.participantId)
                ps.setString(16, request.accountId)
                ps.setString(17, request.botId)
                ps.setString(18, request.runId)
                ps.setString(19, request.venueSessionId)
                ps.setString(20, request.instrumentId)
                ps.setString(21, request.orderId)
                ps.setString(22, request.quantityUnits)
                ps.setString(23, request.limitPrice)
                ps.setString(24, request.currency)
                ps.setString(25, request.payloadHash)
                ps.executeUpdate()
            }
        }
    }

    private fun connection() = dataSource.connection
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

class AllowAllAuthHook : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? = null
}

class StaticTokenAuthHook(
    private val tokensByClientId: Map<String, String>
) : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? {
        val expected = tokensByClientId[clientId]
            ?: return BoundaryError(401, "UNAUTHORIZED", "unknown client id")
        val provided = token?.removePrefix("Bearer ")?.trim()
        if (provided.isNullOrBlank() || provided != expected) {
            return BoundaryError(401, "UNAUTHORIZED", "invalid token")
        }
        return null
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

class InMemoryIdempotencyStore : IdempotencyStore {
    private data class Entry(val result: IdempotencyResult, val expiresAtEpochSeconds: Long)

    private val results = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val retentionPolicy: IdempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy()

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        val entry = results["$clientId|$route|$idempotencyKey"] ?: return null
        if (entry.expiresAtEpochSeconds <= Instant.now().epochSecond) {
            results.remove("$clientId|$route|$idempotencyKey")
            return null
        }
        return entry.result
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass) {
        val expiresAt = Instant.now().epochSecond + retentionPolicy.durationSeconds(ttlClass)
        results.putIfAbsent("$clientId|$route|$idempotencyKey", Entry(result, expiresAt))
    }

    override fun cleanupExpired(now: Instant) {
        results.entries.removeIf { it.value.expiresAtEpochSeconds <= now.epochSecond }
    }
}

class PostgresIdempotencyStore(
    private val dataSource: DataSource,
    private val retentionPolicy: IdempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : IdempotencyStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryIdempotency(names.idempotencyRecords)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE SCHEMA IF NOT EXISTS ${names.schemaName}
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.idempotencyRecords} (
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      status INTEGER NOT NULL,
                      payload TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      expires_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (client_id, route, idempotency_key)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT status, payload
                FROM ${names.idempotencyRecords}
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                  AND expires_at > NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return IdempotencyResult(
                        status = rs.getInt("status"),
                        payload = rs.getString("payload")
                    )
                }
            }
        }
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass) {
        val ttlSeconds = retentionPolicy.durationSeconds(ttlClass)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.idempotencyRecords}(client_id, route, idempotency_key, status, payload, expires_at)
                VALUES (?, ?, ?, ?, ?, NOW() + (? * INTERVAL '1 second'))
                ON CONFLICT (client_id, route, idempotency_key) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.setInt(4, result.status)
                ps.setString(5, result.payload)
                ps.setLong(6, ttlSeconds)
                ps.executeUpdate()
            }
        }
    }

    override fun cleanupExpired(now: Instant) {
        connection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM ${names.idempotencyRecords} WHERE expires_at <= to_timestamp(?)"
            ).use { ps ->
                ps.setLong(1, now.epochSecond)
                ps.executeUpdate()
            }
        }
    }

    private fun connection() = dataSource.connection
}

interface RateLimitStore {
    fun increment(clientId: String, route: String, windowStartEpochSeconds: Long, windowSeconds: Long): Long
}

class InMemoryRateLimitStore(
    private val retainedWindows: Long = 2
) : RateLimitStore {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun increment(clientId: String, route: String, windowStartEpochSeconds: Long, windowSeconds: Long): Long {
        cleanupBefore(windowStartEpochSeconds - (windowSeconds * (retainedWindows - 1).coerceAtLeast(0)))
        val key = "$clientId|$route|$windowStartEpochSeconds"
        return counts.merge(key, 1L) { current, one -> current + one } ?: 1L
    }

    internal fun entryCount(): Int = counts.size

    private fun cleanupBefore(minWindowStartEpochSeconds: Long) {
        counts.keys.removeIf { key ->
            val windowStart = key.substringAfterLast('|').toLongOrNull() ?: return@removeIf true
            windowStart < minWindowStartEpochSeconds
        }
    }
}

data class RejectRatePolicy(
    val maxRejects: Int,
    val windowSeconds: Long,
    val blockSeconds: Long
)

class RejectRateAbuseProtectionHook(
    private val maxRejects: Int,
    private val windowSeconds: Long,
    private val blockSeconds: Long,
    private val trackedRejectCodes: Set<String>,
    private val trackedRoutes: Set<String>,
    private val routePolicies: Map<String, RejectRatePolicy> = emptyMap(),
    private val warningOnly: Boolean = false,
    private val clock: () -> Instant = { Instant.now() }
) : AbuseProtectionHook {
    private data class RejectState(
        val route: String,
        val windowStartEpochSeconds: Long,
        val rejectCount: Int,
        val blockedUntilEpochSeconds: Long,
        val warnedInWindow: Boolean
    )

    private val states = java.util.concurrent.ConcurrentHashMap<String, RejectState>()
    private val tripCount = java.util.concurrent.atomic.AtomicLong(0)
    private val blockedCount = java.util.concurrent.atomic.AtomicLong(0)
    private val releaseCount = java.util.concurrent.atomic.AtomicLong(0)

    override fun allow(clientId: String, route: String): BoundaryError? {
        if (!isConfigured()) return null
        if (!tracksRoute(route)) return null
        val now = clock().epochSecond
        val key = key(clientId, route)
        val policy = policyFor(route) ?: return null
        val state = states.computeIfPresent(key) { _, current ->
            if (current.blockedUntilEpochSeconds > 0 && now >= current.blockedUntilEpochSeconds) {
                releaseCount.incrementAndGet()
                val currentWindowStart = currentWindowStart(now, policy)
                if (current.windowStartEpochSeconds == currentWindowStart) {
                    current.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                } else {
                    null
                }
            } else if (current.blockedUntilEpochSeconds == 0L && stateWindowExpired(current, policy, now)) {
                null
            } else {
                current
            }
        }
        if (state != null && state.blockedUntilEpochSeconds > now) {
            blockedCount.incrementAndGet()
            val secondsRemaining = state.blockedUntilEpochSeconds - now
            return BoundaryError(429, "ABUSE_BLOCKED", "client temporarily blocked for abusive traffic ($secondsRemaining seconds remaining)")
        }
        return null
    }

    override fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?) {
        if (!tracksRoute(route)) return
        val policy = policyFor(route) ?: return
        if (responseStatus < 200 || responseStatus >= 300) return
        val normalizedCode = rejectCode?.uppercase()?.trim().orEmpty()
        if (normalizedCode.isBlank() || !trackedRejectCodes.contains(normalizedCode)) return

        val now = clock().epochSecond
        val currentWindowStart = currentWindowStart(now, policy)
        val key = key(clientId, route)
        states.compute(key) { _, current ->
            val base = when {
                current == null -> RejectState(route, currentWindowStart, 0, 0, false)
                current.windowStartEpochSeconds != currentWindowStart -> RejectState(route, currentWindowStart, 0, 0, false)
                current.blockedUntilEpochSeconds > 0 && now >= current.blockedUntilEpochSeconds -> current.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                else -> current
            }
            val updatedCount = base.rejectCount + 1
            if (updatedCount <= policy.maxRejects) {
                return@compute base.copy(rejectCount = updatedCount)
            }

            if (warningOnly) {
                if (!base.warnedInWindow) {
                    System.err.println(
                        "abuse_breaker_warning mode=reject-rate clientId=$clientId route=$route rejectCode=$normalizedCode rejects=$updatedCount windowSeconds=${policy.windowSeconds}"
                    )
                }
                return@compute base.copy(rejectCount = updatedCount, warnedInWindow = true)
            }

            if (base.blockedUntilEpochSeconds > now) {
                return@compute base
            }

            val blockedUntil = now + policy.blockSeconds
            val trips = tripCount.incrementAndGet()
            System.err.println(
                "abuse_breaker_trip mode=reject-rate clientId=$clientId route=$route rejectCode=$normalizedCode rejects=$updatedCount maxRejects=${policy.maxRejects} windowSeconds=${policy.windowSeconds} blockSeconds=${policy.blockSeconds} trips=$trips"
            )
            base.copy(rejectCount = updatedCount, blockedUntilEpochSeconds = blockedUntil, warnedInWindow = false)
        }
    }

    override fun stats(): AbuseProtectionStats {
        val now = clock().epochSecond
        cleanupExpiredStates(now)
        val activeBlockedClients = states.values.count { it.blockedUntilEpochSeconds > now }
        return AbuseProtectionStats(
            mode = "reject-rate",
            enabled = isConfigured(),
            warningOnly = warningOnly,
            maxRejects = maxRejects,
            windowSeconds = windowSeconds,
            blockSeconds = blockSeconds,
            trackedRejectCodes = trackedRejectCodes,
            trackedRoutes = trackedRoutes,
            routePolicyOverrides = routePolicies.mapValues { (_, policy) ->
                "${policy.maxRejects}/${policy.windowSeconds}/${policy.blockSeconds}"
            },
            trips = tripCount.get(),
            blocks = blockedCount.get(),
            releases = releaseCount.get(),
            activeBlockedClients = activeBlockedClients
        )
    }

    private fun isConfigured(): Boolean {
        return maxRejects > 0 && windowSeconds > 0 && blockSeconds > 0 && trackedRejectCodes.isNotEmpty()
    }

    private fun policyFor(route: String): RejectRatePolicy? {
        val defaultPolicy = if (isConfigured()) {
            RejectRatePolicy(maxRejects, windowSeconds, blockSeconds)
        } else {
            null
        }
        return routePolicies[route] ?: defaultPolicy
    }

    private fun tracksRoute(route: String): Boolean {
        if (trackedRoutes.isEmpty()) return true
        return trackedRoutes.contains(route)
    }

    internal fun trackedStateCount(): Int = states.size

    private fun cleanupExpiredStates(now: Long) {
        for (key in states.keys) {
            states.computeIfPresent(key) { _, state ->
                val policy = policyFor(state.route) ?: return@computeIfPresent null
                if (state.blockedUntilEpochSeconds > 0 && now >= state.blockedUntilEpochSeconds) {
                    releaseCount.incrementAndGet()
                    return@computeIfPresent if (stateWindowExpired(state, policy, now)) {
                        null
                    } else {
                        state.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                    }
                }
                if (state.blockedUntilEpochSeconds == 0L && stateWindowExpired(state, policy, now)) {
                    null
                } else {
                    state
                }
            }
        }
    }

    private fun stateWindowExpired(state: RejectState, policy: RejectRatePolicy, now: Long): Boolean {
        return now >= state.windowStartEpochSeconds + policy.windowSeconds
    }

    private fun currentWindowStart(now: Long, policy: RejectRatePolicy): Long {
        return now / policy.windowSeconds * policy.windowSeconds
    }

    private fun key(clientId: String, route: String): String {
        return "$clientId|$route"
    }
}

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

    fun toErrorJson(error: BoundaryError, correlationId: String): String {
        return """
            {
              "code":"${JsonFields.escape(error.code)}",
              "message":"${JsonFields.escape(error.message)}",
              "correlationId":"${JsonFields.escape(correlationId)}"
            }
        """.trimIndent()
    }

    fun clientId(headers: Headers): String? = headers.firstValue("X-Client-Id")
    fun idempotencyKey(headers: Headers): String? = headers.firstValue("Idempotency-Key")
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

fun defaultBoundaryHooks(): BoundaryHooks {
    val commandProcessingMode = CommandProcessingMode.fromEnv()
    val authMode = (System.getenv("EXTERNAL_API_AUTH_MODE") ?: "allow-all").lowercase()
    val authHook = when (authMode) {
        "static-token" -> StaticTokenAuthHook(parseStaticTokens(System.getenv("EXTERNAL_API_TOKENS")))
        else -> AllowAllAuthHook()
    }

    val rateMode = (System.getenv("EXTERNAL_API_RATE_LIMIT_MODE") ?: "allow-all").lowercase()
    val rateLimitHook = when (rateMode) {
        "fixed-window" -> {
            val max = System.getenv("EXTERNAL_API_RATE_LIMIT_MAX")?.toIntOrNull() ?: 120
            val window = System.getenv("EXTERNAL_API_RATE_LIMIT_WINDOW_SECONDS")?.toLongOrNull() ?: 60L
            FixedWindowRateLimitHook(InMemoryRateLimitStore(), max, window)
        }
        else -> AllowAllRateLimitHook()
    }

    val abuseMode = (System.getenv("EXTERNAL_API_ABUSE_BREAKER_MODE") ?: "off").lowercase()
    val abuseProtectionHook = when (abuseMode) {
        "reject-rate" -> {
            val enabled = envBool(System.getenv("EXTERNAL_API_ABUSE_BREAKER_ENABLED"), true)
            val rejectRateEnabled = envBool(System.getenv("EXTERNAL_API_ABUSE_BREAKER_REJECT_RATE_ENABLED"), true)
            if (!enabled || !rejectRateEnabled) {
                AllowAllAbuseProtectionHook()
            } else {
                val maxRejects = System.getenv("EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS")?.toIntOrNull() ?: 50
                val windowSeconds = System.getenv("EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS")?.toLongOrNull() ?: 30L
                val blockSeconds = System.getenv("EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS")?.toLongOrNull() ?: 60L
                val warningOnly = envBool(System.getenv("EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY"), false)
                val codes = parseRejectCodes(System.getenv("EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES"))
                val routes = parseTrackedRoutes(System.getenv("EXTERNAL_API_ABUSE_BREAKER_ROUTES"))
                val routePolicies = parseRoutePolicies(System.getenv("EXTERNAL_API_ABUSE_BREAKER_ROUTE_POLICIES"))
                RejectRateAbuseProtectionHook(
                    maxRejects = maxRejects,
                    windowSeconds = windowSeconds,
                    blockSeconds = blockSeconds,
                    trackedRejectCodes = codes,
                    trackedRoutes = routes,
                    routePolicies = routePolicies,
                    warningOnly = warningOnly
                )
            }
        }
        else -> AllowAllAbuseProtectionHook()
    }

    val accountRiskMode = (System.getenv("EXTERNAL_API_ACCOUNT_RISK_CHECK_MODE") ?: "allow-all").lowercase()
    val accountRiskCheck = when (accountRiskMode) {
        "static", "cached-static" -> StaticAccountRiskCheck(
            rejectedAccounts = parseCsvSet(System.getenv("EXTERNAL_API_ACCOUNT_RISK_REJECT_ACCOUNTS")),
            backpressuredAccounts = parseCsvSet(System.getenv("EXTERNAL_API_ACCOUNT_RISK_BACKPRESSURE_ACCOUNTS")),
            disabledBots = parseCsvSet(System.getenv("EXTERNAL_API_ACCOUNT_RISK_DISABLED_BOTS"))
        )
        "postgres", "cached-postgres" -> {
            val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
            val cacheTtlMillis = System.getenv("EXTERNAL_API_ACCOUNT_RISK_CACHE_TTL_MS")?.toLongOrNull() ?: 1_000L
            PostgresAccountRiskCheck(
                dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "account-risk"),
                cacheTtlMillis = cacheTtlMillis.coerceAtLeast(0L)
            )
        }
        else -> AllowAllAccountRiskCheck()
    }

    val circuitBreakerMode = (System.getenv("EXTERNAL_API_COMMAND_CIRCUIT_BREAKER_MODE") ?: "allow-all").lowercase()
    val commandCircuitBreakerCheck = when (circuitBreakerMode) {
        "postgres", "cached-postgres" -> {
            val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
            val cacheTtlMillis = System.getenv("EXTERNAL_API_COMMAND_CIRCUIT_BREAKER_CACHE_TTL_MS")?.toLongOrNull() ?: 1_000L
            PostgresCommandCircuitBreakerStore(
                dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "command-circuit-breaker"),
                cacheTtlMillis = cacheTtlMillis.coerceAtLeast(0L)
            )
        }
        else -> AllowAllCommandCircuitBreakerCheck()
    }

    val instrumentPriceCollarMode = (System.getenv("EXTERNAL_API_INSTRUMENT_PRICE_COLLAR_MODE") ?: "allow-all").lowercase()
    val instrumentPriceCollarCheck = when (instrumentPriceCollarMode) {
        "postgres", "cached-postgres" -> {
            val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
            val cacheTtlMillis = System.getenv("EXTERNAL_API_INSTRUMENT_PRICE_COLLAR_CACHE_TTL_MS")?.toLongOrNull() ?: 1_000L
            PostgresInstrumentPriceCollarStore(
                dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "instrument-price-collar"),
                cacheTtlMillis = cacheTtlMillis.coerceAtLeast(0L)
            )
        }
        else -> AllowAllInstrumentPriceCollarCheck()
    }

    val idempotencyMode = (System.getenv("EXTERNAL_API_IDEMPOTENCY_STORE") ?: "inmemory").lowercase()
    val retentionPolicy = DefaultIdempotencyRetentionPolicy()
    val idempotencyStore = if (idempotencyMode == "postgres") {
        val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
        val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
        val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
        PostgresIdempotencyStore(
            RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "idempotency"),
            retentionPolicy
        )
    } else {
        InMemoryIdempotencyStore()
    }

    val rejectionLogMode = (System.getenv("EXTERNAL_API_BOUNDARY_REJECTION_LOG") ?: "auto").lowercase()
    val boundaryRejectionLog = when {
        rejectionLogMode == "postgres" || (
            rejectionLogMode == "auto" &&
                listOf(accountRiskMode, circuitBreakerMode, instrumentPriceCollarMode).any { it == "postgres" || it == "cached-postgres" }
            ) -> {
            val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
            PostgresBoundaryRejectionLog(
                dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "boundary-rejection-log")
            )
        }
        else -> NoopBoundaryRejectionLog()
    }

    return BoundaryHooks(
        authHook = authHook,
        rateLimitHook = rateLimitHook,
        abuseProtectionHook = abuseProtectionHook,
        accountRiskCheck = accountRiskCheck,
        commandCircuitBreakerCheck = commandCircuitBreakerCheck,
        instrumentPriceCollarCheck = instrumentPriceCollarCheck,
        boundaryRejectionLog = boundaryRejectionLog,
        idempotencyStore = idempotencyStore,
        idempotencyRetentionPolicy = retentionPolicy,
        commandCaptureStore = defaultCommandCaptureStore(commandProcessingMode),
        commandProcessingMode = commandProcessingMode
    )
}

fun defaultAccountRiskControlStore(): AccountRiskControlStore {
    val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
    val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
    return PostgresAccountRiskCheck(
        dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "account-risk-admin"),
        cacheTtlMillis = 0L
    )
}

fun defaultCommandCircuitBreakerStore(): CommandCircuitBreakerStore {
    val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
    val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
    return PostgresCommandCircuitBreakerStore(
        dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "command-circuit-breaker-admin"),
        cacheTtlMillis = 0L
    )
}

fun defaultInstrumentPriceCollarStore(): InstrumentPriceCollarStore {
    val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
    val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
    return PostgresInstrumentPriceCollarStore(
        dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "instrument-price-collar-admin"),
        cacheTtlMillis = 0L
    )
}

private fun parseStaticTokens(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(",")
        .mapNotNull { pair ->
            val parts = pair.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val clientId = parts[0].trim()
            val token = parts[1].trim()
            if (clientId.isBlank() || token.isBlank()) return@mapNotNull null
            clientId to token
        }
        .toMap()
}

private fun parseCsvSet(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun parseRejectCodes(raw: String?): Set<String> {
    val fallback = setOf("INVALID_STATE", "NOT_FOUND", "REFERENCE_DATA_ERROR", "VALIDATION_ERROR")
    if (raw.isNullOrBlank()) return fallback
    val values = raw.split(",")
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .toSet()
    return if (values.isEmpty()) fallback else values
}

private fun parseTrackedRoutes(raw: String?): Set<String> {
    val fallback = setOf(
        "/api/v1/orders/submit",
        "/api/v1/orders/modify",
        "/api/v1/orders/cancel"
    )
    if (raw.isNullOrBlank()) return fallback
    val values = raw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    return if (values.isEmpty()) fallback else values
}

private fun parseRoutePolicies(raw: String?): Map<String, RejectRatePolicy> {
    if (raw.isNullOrBlank()) return emptyMap()
    val parsed = mutableMapOf<String, RejectRatePolicy>()
    for (entry in raw.split(",")) {
        val item = entry.trim()
        if (item.isBlank()) continue
        val parts = item.split(":", limit = 2)
        if (parts.size != 2) continue
        val route = parts[0].trim()
        val policyParts = parts[1].split("/", limit = 3)
        if (route.isBlank() || policyParts.size != 3) continue
        val maxRejects = policyParts[0].trim().toIntOrNull() ?: continue
        val windowSeconds = policyParts[1].trim().toLongOrNull() ?: continue
        val blockSeconds = policyParts[2].trim().toLongOrNull() ?: continue
        if (maxRejects <= 0 || windowSeconds <= 0 || blockSeconds <= 0) continue
        parsed[route] = RejectRatePolicy(maxRejects, windowSeconds, blockSeconds)
    }
    return parsed
}

private fun envBool(raw: String?, defaultValue: Boolean): Boolean {
    if (raw.isNullOrBlank()) return defaultValue
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}
