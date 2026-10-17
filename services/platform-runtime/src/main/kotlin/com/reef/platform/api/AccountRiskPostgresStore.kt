package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

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
                "account_risk_audit_failed clientId=${request.clientId} route=${request.route} commandId=${request.commandId} decision=${result.decision} message=${JsonCodec.escapeString(message)}"
            )
        }
    }

    private fun connection() = dataSource.connection
}
