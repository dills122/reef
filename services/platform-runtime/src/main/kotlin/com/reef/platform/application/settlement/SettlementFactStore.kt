package com.reef.platform.application.settlement

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

const val SettlementObligationCreatedState = "OBLIGATION_CREATED"
const val SettlementAttemptStartedState = "ATTEMPT_STARTED"
const val SettlementBreakOpenedReason = "CASH_LEG_FAILED"
const val SettlementBreakOpenedState = "BROKEN"
const val SettlementRepairPostedAction = "POST_CASH_LEG_REPAIR"
const val SettlementRepairPostedActorType = "USER"
const val SettlementResolvedState = "RESOLVED"
const val DefaultPostTradeProfileId = "ops-realistic-v1"
const val DefaultPostTradePolicyVersion = 1

data class SettlementObligationCreatedFact(
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val instrumentId: String,
    val quantity: String,
    val cashAmount: String,
    val currency: String,
    val state: String = SettlementObligationCreatedState,
    val occurredAt: Instant
)

data class SettlementAttemptStartedFact(
    val settlementAttemptId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val attemptNumber: Int = 1,
    val state: String = SettlementAttemptStartedState,
    val occurredAt: Instant
)

data class SettlementBreakOpenedFact(
    val settlementBreakId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val reason: String = SettlementBreakOpenedReason,
    val state: String = SettlementBreakOpenedState,
    val occurredAt: Instant
)

data class SettlementRepairPostedFact(
    val settlementRepairId: String,
    val settlementBreakId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val repairAction: String = SettlementRepairPostedAction,
    val actorType: String = SettlementRepairPostedActorType,
    val actorId: String,
    val occurredAt: Instant
)

data class SettlementResolvedFact(
    val settlementResolutionId: String,
    val settlementObligationId: String,
    val settlementBreakId: String,
    val settlementRepairId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val settlementState: String = SettlementResolvedState,
    val exceptionState: String = SettlementResolvedState,
    val occurredAt: Instant
)

data class SettlementFactBundle(
    val scenarioRunId: String,
    val obligations: List<SettlementObligationCreatedFact> = emptyList(),
    val attempts: List<SettlementAttemptStartedFact> = emptyList(),
    val breaks: List<SettlementBreakOpenedFact> = emptyList(),
    val repairs: List<SettlementRepairPostedFact> = emptyList(),
    val resolutions: List<SettlementResolvedFact> = emptyList()
) {
    fun isEmpty(): Boolean =
        obligations.isEmpty() && attempts.isEmpty() && breaks.isEmpty() && repairs.isEmpty() && resolutions.isEmpty()
}

interface SettlementFactStore {
    fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle
    fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle
}

class InMemorySettlementFactStore : SettlementFactStore {
    private val obligations = ConcurrentHashMap<String, SettlementObligationCreatedFact>()
    private val attempts = ConcurrentHashMap<String, SettlementAttemptStartedFact>()
    private val breaks = ConcurrentHashMap<String, SettlementBreakOpenedFact>()
    private val repairs = ConcurrentHashMap<String, SettlementRepairPostedFact>()
    private val resolutions = ConcurrentHashMap<String, SettlementResolvedFact>()

    override fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle {
        if (facts.isEmpty()) return facts
        val existing = factsByScenarioRunId(facts.scenarioRunId)
        validateSettlementFacts(existing.merge(facts))
        facts.obligations.forEach { obligations.putIfAbsent(it.settlementObligationId, it) }
        facts.attempts.forEach { attempts.putIfAbsent(it.settlementAttemptId, it) }
        facts.breaks.forEach { breaks.putIfAbsent(it.settlementBreakId, it) }
        facts.repairs.forEach { repairs.putIfAbsent(it.settlementRepairId, it) }
        facts.resolutions.forEach { resolutions.putIfAbsent(it.settlementResolutionId, it) }
        return facts
    }

    override fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle {
        require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            obligations = obligations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            attempts = attempts.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            breaks = breaks.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            repairs = repairs.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            resolutions = resolutions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt }
        )
    }
}

data class PostgresSettlementSqlNames(
    private val schema: String = "settlement"
) {
    val schemaName = schemaOrDefault(schema)
    val obligations = qualify("obligations")
    val attempts = qualify("attempts")
    val breaks = qualify("breaks")
    val repairs = qualify("repairs")
    val resolutions = qualify("resolutions")

    private fun schemaOrDefault(schema: String): String {
        val candidate = schema.trim().ifBlank { "settlement" }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private fun qualify(name: String): String = "$schemaName.$name"

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

class PostgresSettlementFactStore(
    private val dataSource: DataSource,
    private val names: PostgresSettlementSqlNames = PostgresSettlementSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : SettlementFactStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.settlementFacts(
                        obligations = names.obligations,
                        attempts = names.attempts,
                        breaks = names.breaks,
                        repairs = names.repairs,
                        resolutions = names.resolutions
                    )
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.obligations} (
                      settlement_obligation_id TEXT PRIMARY KEY,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      trade_id TEXT NOT NULL,
                      buyer_participant_id TEXT NOT NULL,
                      seller_participant_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      quantity TEXT NOT NULL,
                      cash_amount TEXT NOT NULL,
                      currency TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'OBLIGATION_CREATED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.attempts} (
                      settlement_attempt_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
                      state TEXT NOT NULL CHECK (state = 'ATTEMPT_STARTED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.breaks} (
                      settlement_break_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      reason TEXT NOT NULL CHECK (reason = 'CASH_LEG_FAILED'),
                      state TEXT NOT NULL CHECK (state = 'BROKEN'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.repairs} (
                      settlement_repair_id TEXT PRIMARY KEY,
                      settlement_break_id TEXT NOT NULL,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      repair_action TEXT NOT NULL CHECK (repair_action = 'POST_CASH_LEG_REPAIR'),
                      actor_type TEXT NOT NULL CHECK (actor_type = 'USER'),
                      actor_id TEXT NOT NULL,
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.resolutions} (
                      settlement_resolution_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      settlement_break_id TEXT NOT NULL,
                      settlement_repair_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      settlement_state TEXT NOT NULL CHECK (settlement_state = 'RESOLVED'),
                      exception_state TEXT NOT NULL CHECK (exception_state = 'RESOLVED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                ensureSettlementEvidenceColumns(stmt)
                createIndexes(stmt = stmt)
            }
        }
    }

    override fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle {
        if (facts.isEmpty()) return facts
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val existing = factsByScenarioRunId(conn, facts.scenarioRunId)
                validateSettlementFacts(existing.merge(facts))
                insertObligations(conn, facts.obligations)
                insertAttempts(conn, facts.attempts)
                insertBreaks(conn, facts.breaks)
                insertRepairs(conn, facts.repairs)
                insertResolutions(conn, facts.resolutions)
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
        return facts
    }

    override fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle {
        require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
        connection().use { conn -> return factsByScenarioRunId(conn, scenarioRunId) }
    }

    private fun factsByScenarioRunId(conn: Connection, scenarioRunId: String): SettlementFactBundle {
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            obligations = queryObligations(conn, scenarioRunId),
            attempts = queryAttempts(conn, scenarioRunId),
            breaks = queryBreaks(conn, scenarioRunId),
            repairs = queryRepairs(conn, scenarioRunId),
            resolutions = queryResolutions(conn, scenarioRunId)
        )
    }

    private fun insertObligations(conn: Connection, facts: List<SettlementObligationCreatedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.obligations}(
              settlement_obligation_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, trade_id,
              buyer_participant_id, seller_participant_id, instrument_id, quantity, cash_amount,
              currency, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (settlement_obligation_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementObligationId)
                ps.setString(2, it.scenarioRunId)
                ps.setString(3, it.postTradeProfileId)
                ps.setInt(4, it.postTradePolicyVersion)
                ps.setString(5, it.correlationId)
                ps.setString(6, it.causationId)
                ps.setString(7, it.tradeId)
                ps.setString(8, it.buyerParticipantId)
                ps.setString(9, it.sellerParticipantId)
                ps.setString(10, it.instrumentId)
                ps.setString(11, it.quantity)
                ps.setString(12, it.cashAmount)
                ps.setString(13, it.currency)
                ps.setString(14, it.state)
                ps.setTimestamp(15, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertAttempts(conn: Connection, facts: List<SettlementAttemptStartedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.attempts}(
              settlement_attempt_id, settlement_obligation_id, scenario_run_id,
              post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, attempt_number, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (settlement_attempt_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementAttemptId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.scenarioRunId)
                ps.setString(4, it.postTradeProfileId)
                ps.setInt(5, it.postTradePolicyVersion)
                ps.setString(6, it.correlationId)
                ps.setString(7, it.causationId)
                ps.setInt(8, it.attemptNumber)
                ps.setString(9, it.state)
                ps.setTimestamp(10, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertBreaks(conn: Connection, facts: List<SettlementBreakOpenedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.breaks}(
              settlement_break_id, settlement_obligation_id, scenario_run_id, correlation_id,
              causation_id, post_trade_profile_id, post_trade_policy_version, reason, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (settlement_break_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementBreakId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.scenarioRunId)
                ps.setString(4, it.correlationId)
                ps.setString(5, it.causationId)
                ps.setString(6, it.postTradeProfileId)
                ps.setInt(7, it.postTradePolicyVersion)
                ps.setString(8, it.reason)
                ps.setString(9, it.state)
                ps.setTimestamp(10, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertRepairs(conn: Connection, facts: List<SettlementRepairPostedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.repairs}(
              settlement_repair_id, settlement_break_id, settlement_obligation_id, scenario_run_id,
              correlation_id, causation_id, post_trade_profile_id, post_trade_policy_version,
              repair_action, actor_type, actor_id, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (settlement_repair_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementRepairId)
                ps.setString(2, it.settlementBreakId)
                ps.setString(3, it.settlementObligationId)
                ps.setString(4, it.scenarioRunId)
                ps.setString(5, it.correlationId)
                ps.setString(6, it.causationId)
                ps.setString(7, it.postTradeProfileId)
                ps.setInt(8, it.postTradePolicyVersion)
                ps.setString(9, it.repairAction)
                ps.setString(10, it.actorType)
                ps.setString(11, it.actorId)
                ps.setTimestamp(12, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertResolutions(conn: Connection, facts: List<SettlementResolvedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.resolutions}(
              settlement_resolution_id, settlement_obligation_id, settlement_break_id, settlement_repair_id,
              scenario_run_id, correlation_id, causation_id, post_trade_profile_id, post_trade_policy_version,
              settlement_state, exception_state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (settlement_resolution_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementResolutionId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.settlementBreakId)
                ps.setString(4, it.settlementRepairId)
                ps.setString(5, it.scenarioRunId)
                ps.setString(6, it.correlationId)
                ps.setString(7, it.causationId)
                ps.setString(8, it.postTradeProfileId)
                ps.setInt(9, it.postTradePolicyVersion)
                ps.setString(10, it.settlementState)
                ps.setString(11, it.exceptionState)
                ps.setTimestamp(12, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun queryObligations(conn: Connection, scenarioRunId: String): List<SettlementObligationCreatedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_obligation_id, scenario_run_id, correlation_id, causation_id, trade_id,
                   buyer_participant_id, seller_participant_id, instrument_id, quantity, cash_amount,
                   currency, state, occurred_at, post_trade_profile_id, post_trade_policy_version
            FROM ${names.obligations}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_obligation_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementObligationCreatedFact>()
                while (rs.next()) records.add(rs.toObligation())
                return records
            }
        }
    }

    private fun queryAttempts(conn: Connection, scenarioRunId: String): List<SettlementAttemptStartedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_attempt_id, settlement_obligation_id, scenario_run_id,
                   post_trade_profile_id, post_trade_policy_version, correlation_id, causation_id,
                   attempt_number, state, occurred_at
            FROM ${names.attempts}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_attempt_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementAttemptStartedFact>()
                while (rs.next()) records.add(rs.toAttempt())
                return records
            }
        }
    }

    private fun queryBreaks(conn: Connection, scenarioRunId: String): List<SettlementBreakOpenedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_break_id, settlement_obligation_id, scenario_run_id, correlation_id,
                   causation_id, reason, state, occurred_at, post_trade_profile_id, post_trade_policy_version
            FROM ${names.breaks}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_break_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementBreakOpenedFact>()
                while (rs.next()) records.add(rs.toBreak())
                return records
            }
        }
    }

    private fun queryRepairs(conn: Connection, scenarioRunId: String): List<SettlementRepairPostedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_repair_id, settlement_break_id, settlement_obligation_id, scenario_run_id,
                   correlation_id, causation_id, repair_action, actor_type, actor_id, occurred_at,
                   post_trade_profile_id, post_trade_policy_version
            FROM ${names.repairs}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_repair_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementRepairPostedFact>()
                while (rs.next()) records.add(rs.toRepair())
                return records
            }
        }
    }

    private fun queryResolutions(conn: Connection, scenarioRunId: String): List<SettlementResolvedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_resolution_id, settlement_obligation_id, settlement_break_id, settlement_repair_id,
                   scenario_run_id, correlation_id, causation_id, settlement_state, exception_state, occurred_at,
                   post_trade_profile_id, post_trade_policy_version
            FROM ${names.resolutions}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_resolution_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementResolvedFact>()
                while (rs.next()) records.add(rs.toResolution())
                return records
            }
        }
    }

    private fun createIndexes(stmt: java.sql.Statement) {
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_obligations_run ON ${names.obligations}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_attempts_run ON ${names.attempts}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_breaks_run ON ${names.breaks}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_repairs_run ON ${names.repairs}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_resolutions_run ON ${names.resolutions}(scenario_run_id, occurred_at)")
    }

    private fun ensureSettlementEvidenceColumns(stmt: java.sql.Statement) {
        listOf(names.obligations, names.attempts, names.breaks, names.repairs, names.resolutions).forEach { table ->
            stmt.execute(
                "ALTER TABLE $table ADD COLUMN IF NOT EXISTS post_trade_profile_id " +
                    "TEXT NOT NULL DEFAULT 'ops-realistic-v1'"
            )
            stmt.execute(
                "ALTER TABLE $table ADD COLUMN IF NOT EXISTS post_trade_policy_version " +
                    "INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    private fun connection(): Connection = dataSource.connection
}

private fun SettlementFactBundle.merge(next: SettlementFactBundle): SettlementFactBundle {
    require(scenarioRunId == next.scenarioRunId) { "settlement fact scenarioRunId mismatch" }
    return copy(
        obligations = obligations.byId(next.obligations) { it.settlementObligationId },
        attempts = attempts.byId(next.attempts) { it.settlementAttemptId },
        breaks = breaks.byId(next.breaks) { it.settlementBreakId },
        repairs = repairs.byId(next.repairs) { it.settlementRepairId },
        resolutions = resolutions.byId(next.resolutions) { it.settlementResolutionId }
    )
}

private fun <T> List<T>.byId(next: List<T>, id: (T) -> String): List<T> {
    val byId = linkedMapOf<String, T>()
    (this + next).forEach {
        val existing = byId.putIfAbsent(id(it), it)
        require(existing == null || existing == it) { "settlement fact id reused with different payload" }
    }
    return byId.values.toList()
}

private fun validateSettlementFacts(facts: SettlementFactBundle) {
    require(facts.scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
    val obligations = facts.obligations.associateBy { it.settlementObligationId }
    val breaks = facts.breaks.associateBy { it.settlementBreakId }
    val repairs = facts.repairs.associateBy { it.settlementRepairId }
    val profileEvidence = (
        facts.obligations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.attempts.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.breaks.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.repairs.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.resolutions.map { it.postTradeProfileId to it.postTradePolicyVersion }
        ).toSet()
    require(profileEvidence.size <= 1) { "settlement facts must use one post-trade profile per scenarioRunId" }

    facts.obligations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.tradeId.isNotBlank()) { "tradeId is required" }
        require(it.buyerParticipantId.isNotBlank()) { "buyerParticipantId is required" }
        require(it.sellerParticipantId.isNotBlank()) { "sellerParticipantId is required" }
        require(it.instrumentId.isNotBlank()) { "instrumentId is required" }
        require(it.quantity.isNotBlank()) { "quantity is required" }
        require(it.cashAmount.isNotBlank()) { "cashAmount is required" }
        require(it.currency.isNotBlank()) { "currency is required" }
        require(it.state == SettlementObligationCreatedState) { "obligation state must be $SettlementObligationCreatedState" }
    }

    facts.attempts.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAttemptId.isNotBlank()) { "settlementAttemptId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "attempt must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "attempt post-trade profile must match obligation"
        }
        require(it.attemptNumber > 0) { "attemptNumber must be positive" }
        require(it.state == SettlementAttemptStartedState) { "attempt state must be $SettlementAttemptStartedState" }
    }

    facts.breaks.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "break must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "break post-trade profile must match obligation"
        }
        require(it.reason == SettlementBreakOpenedReason) { "break reason must be $SettlementBreakOpenedReason" }
        require(it.state == SettlementBreakOpenedState) { "break state must be $SettlementBreakOpenedState" }
    }

    facts.repairs.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val breakFact = breaks[it.settlementBreakId]
        require(breakFact != null) { "repair must reference existing break" }
        require(breakFact?.settlementObligationId == it.settlementObligationId) { "repair obligation must match break obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, breakFact)) {
            "repair post-trade profile must match break"
        }
        require(it.repairAction == SettlementRepairPostedAction) { "repair action must be $SettlementRepairPostedAction" }
        require(it.actorType == SettlementRepairPostedActorType) { "repair actorType must be $SettlementRepairPostedActorType" }
        require(it.actorId.isNotBlank()) { "actorId is required" }
    }

    facts.resolutions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val repair = repairs[it.settlementRepairId]
        require(repair != null) { "resolution must reference existing repair" }
        require(repair?.settlementBreakId == it.settlementBreakId) { "resolution break must match repair break" }
        require(repair?.settlementObligationId == it.settlementObligationId) { "resolution obligation must match repair obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, repair)) {
            "resolution post-trade profile must match repair"
        }
        require(it.settlementState == SettlementResolvedState) { "settlementState must be $SettlementResolvedState" }
        require(it.exceptionState == SettlementResolvedState) { "exceptionState must be $SettlementResolvedState" }
    }
}

private fun requirePostTradeProfileEvidence(postTradeProfileId: String, postTradePolicyVersion: Int) {
    require(postTradeProfileId.isNotBlank()) { "postTradeProfileId is required" }
    require(postTradePolicyVersion > 0) { "postTradePolicyVersion must be positive" }
}

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementObligationCreatedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementBreakOpenedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementRepairPostedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun requireCommon(
    factScenarioRunId: String,
    expectedScenarioRunId: String,
    correlationId: String,
    causationId: String
) {
    require(factScenarioRunId == expectedScenarioRunId) { "fact scenarioRunId must match bundle scenarioRunId" }
    require(correlationId.isNotBlank()) { "correlationId is required" }
    require(causationId.isNotBlank()) { "causationId is required" }
}

private fun ResultSet.toObligation(): SettlementObligationCreatedFact {
    return SettlementObligationCreatedFact(
        settlementObligationId = getString(1),
        scenarioRunId = getString(2),
        postTradeProfileId = getString(14),
        postTradePolicyVersion = getInt(15),
        correlationId = getString(3),
        causationId = getString(4),
        tradeId = getString(5),
        buyerParticipantId = getString(6),
        sellerParticipantId = getString(7),
        instrumentId = getString(8),
        quantity = getString(9),
        cashAmount = getString(10),
        currency = getString(11),
        state = getString(12),
        occurredAt = getTimestamp(13).toInstant()
    )
}

private fun ResultSet.toAttempt(): SettlementAttemptStartedFact {
    return SettlementAttemptStartedFact(
        settlementAttemptId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(4),
        postTradePolicyVersion = getInt(5),
        correlationId = getString(6),
        causationId = getString(7),
        attemptNumber = getInt(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

private fun ResultSet.toBreak(): SettlementBreakOpenedFact {
    return SettlementBreakOpenedFact(
        settlementBreakId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(9),
        postTradePolicyVersion = getInt(10),
        correlationId = getString(4),
        causationId = getString(5),
        reason = getString(6),
        state = getString(7),
        occurredAt = getTimestamp(8).toInstant()
    )
}

private fun ResultSet.toRepair(): SettlementRepairPostedFact {
    return SettlementRepairPostedFact(
        settlementRepairId = getString(1),
        settlementBreakId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(11),
        postTradePolicyVersion = getInt(12),
        correlationId = getString(5),
        causationId = getString(6),
        repairAction = getString(7),
        actorType = getString(8),
        actorId = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

private fun ResultSet.toResolution(): SettlementResolvedFact {
    return SettlementResolvedFact(
        settlementResolutionId = getString(1),
        settlementObligationId = getString(2),
        settlementBreakId = getString(3),
        settlementRepairId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(11),
        postTradePolicyVersion = getInt(12),
        correlationId = getString(6),
        causationId = getString(7),
        settlementState = getString(8),
        exceptionState = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}
