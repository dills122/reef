package com.reef.platform.application.settlement

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

interface SettlementFactStore {
    fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle
    fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle
}

data class PostgresSettlementSqlNames(
    private val schema: String = "settlement"
) {
    val schemaName = schemaOrDefault(schema)
    val resourcePositions = qualify("resource_positions")
    val obligations = qualify("obligations")
    val allocations = qualify("allocations")
    val confirmations = qualify("confirmations")
    val affirmations = qualify("affirmations")
    val clearingSubmissions = qualify("clearing_submissions")
    val clearingAcceptances = qualify("clearing_acceptances")
    val clearingRejections = qualify("clearing_rejections")
    val novations = qualify("novations")
    val instructions = qualify("instructions")
    val attempts = qualify("attempts")
    val legOutcomes = qualify("leg_outcomes")
    val ledgerEntries = qualify("ledger_entries")
    val settlements = qualify("settlements")
    val breaks = qualify("breaks")
    val repairs = qualify("repairs")
    val resolutions = qualify("resolutions")
    val operatorActions = qualify("operator_actions")

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
                        resourcePositions = names.resourcePositions,
                        obligations = names.obligations,
                        allocations = names.allocations,
                        confirmations = names.confirmations,
                        affirmations = names.affirmations,
                        clearingSubmissions = names.clearingSubmissions,
                        clearingAcceptances = names.clearingAcceptances,
                        clearingRejections = names.clearingRejections,
                        novations = names.novations,
                        instructions = names.instructions,
                        attempts = names.attempts,
                        legOutcomes = names.legOutcomes,
                        ledgerEntries = names.ledgerEntries,
                        settlements = names.settlements,
                        breaks = names.breaks,
                        repairs = names.repairs,
                        resolutions = names.resolutions,
                        operatorActions = names.operatorActions
                    )
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.resourcePositions} (
                      resource_position_id TEXT PRIMARY KEY,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
                      asset_id TEXT NOT NULL,
                      quantity TEXT NOT NULL,
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
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
                    CREATE TABLE IF NOT EXISTS ${names.allocations} (
                      settlement_allocation_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      trade_id TEXT NOT NULL,
                      buy_order_id TEXT NOT NULL,
                      sell_order_id TEXT NOT NULL,
                      buyer_account_id TEXT NOT NULL,
                      seller_account_id TEXT NOT NULL,
                      quantity TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'ALLOCATION_PROPOSED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.confirmations} (
                      settlement_confirmation_id TEXT PRIMARY KEY,
                      settlement_allocation_id TEXT NOT NULL,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      trade_id TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'CONFIRMATION_GENERATED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.affirmations} (
                      settlement_affirmation_id TEXT PRIMARY KEY,
                      settlement_confirmation_id TEXT NOT NULL,
                      settlement_allocation_id TEXT NOT NULL,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      trade_id TEXT NOT NULL,
                      actor_type TEXT NOT NULL CHECK (actor_type = 'SYSTEM'),
                      actor_id TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'AFFIRMATION_ACCEPTED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.clearingSubmissions} (
                      settlement_clearing_submission_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      settlement_affirmation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'CLEARING_SUBMITTED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.clearingAcceptances} (
                      settlement_clearing_acceptance_id TEXT PRIMARY KEY,
                      settlement_clearing_submission_id TEXT NOT NULL,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'CLEARING_ACCEPTED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.clearingRejections} (
                      settlement_clearing_rejection_id TEXT PRIMARY KEY,
                      settlement_clearing_submission_id TEXT NOT NULL,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      reason TEXT NOT NULL CHECK (reason = 'CLEARING_REJECT'),
                      state TEXT NOT NULL CHECK (state = 'CLEARING_REJECTED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.novations} (
                      settlement_novation_id TEXT PRIMARY KEY,
                      settlement_clearing_acceptance_id TEXT NOT NULL,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state = 'NOVATION_RECORDED'),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.instructions} (
                      settlement_instruction_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      instruction_type TEXT NOT NULL CHECK (instruction_type = 'DVP'),
                      state TEXT NOT NULL CHECK (state = 'INSTRUCTION_CREATED'),
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
                      settlement_instruction_id TEXT NOT NULL,
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
                    CREATE TABLE IF NOT EXISTS ${names.legOutcomes} (
                      settlement_leg_outcome_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      settlement_instruction_id TEXT NOT NULL,
                      settlement_attempt_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      leg_type TEXT NOT NULL CHECK (leg_type IN ('CASH', 'SECURITY')),
                      state TEXT NOT NULL CHECK (state IN ('LEG_SUCCEEDED', 'LEG_FAILED')),
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.ledgerEntries} (
                      ledger_entry_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      settlement_instruction_id TEXT NOT NULL,
                      settlement_attempt_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
                      asset_id TEXT NOT NULL,
                      direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
                      quantity TEXT NOT NULL,
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.settlements} (
                      settlement_id TEXT PRIMARY KEY,
                      settlement_obligation_id TEXT NOT NULL,
                      settlement_instruction_id TEXT NOT NULL,
                      settlement_attempt_id TEXT NOT NULL,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      settlement_state TEXT NOT NULL CHECK (settlement_state = 'SETTLED'),
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
                      reason TEXT NOT NULL CHECK (reason IN ('CASH_LEG_FAILED', 'SECURITY_LEG_FAILED')),
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
                      repair_action TEXT NOT NULL CHECK (repair_action IN ('POST_CASH_LEG_REPAIR', 'POST_SECURITY_LEG_REPAIR')),
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
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.operatorActions} (
                      settlement_operator_action_id TEXT PRIMARY KEY,
                      scenario_run_id TEXT NOT NULL,
                      post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
                      post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
                      correlation_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      action TEXT NOT NULL CHECK (action IN ('FORCE_SETTLE', 'REVERSE_LEDGER_ENTRY')),
                      target_id TEXT NOT NULL,
                      reason_note TEXT NOT NULL,
                      actor_type TEXT NOT NULL CHECK (actor_type = 'USER'),
                      actor_id TEXT NOT NULL,
                      occurred_at TIMESTAMPTZ NOT NULL,
                      inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                ensureSettlementEvidenceColumns(stmt)
                ensureSettlementAttemptInstructionColumn(stmt)
                createIndexes(stmt = stmt)
            }
        }
    }

    override fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle {
        if (facts.isEmpty()) return facts
        val databaseFacts = facts.withMicrosecondTimestamps()
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val existing = factsByScenarioRunId(conn, facts.scenarioRunId)
                validateSettlementFacts(existing.merge(databaseFacts))
                val uncertainInserts = listOf(
                    insertResourcePositions(conn, databaseFacts.resourcePositions),
                    insertObligations(conn, databaseFacts.obligations),
                    insertAllocations(conn, databaseFacts.allocations),
                    insertConfirmations(conn, databaseFacts.confirmations),
                    insertAffirmations(conn, databaseFacts.affirmations),
                    insertClearingSubmissions(conn, databaseFacts.clearingSubmissions),
                    insertClearingAcceptances(conn, databaseFacts.clearingAcceptances),
                    insertClearingRejections(conn, databaseFacts.clearingRejections),
                    insertNovations(conn, databaseFacts.novations),
                    insertInstructions(conn, databaseFacts.instructions),
                    insertAttempts(conn, databaseFacts.attempts),
                    insertLegOutcomes(conn, databaseFacts.legOutcomes),
                    insertLedgerEntries(conn, databaseFacts.ledgerEntries),
                    insertSettlements(conn, databaseFacts.settlements),
                    insertBreaks(conn, databaseFacts.breaks),
                    insertRepairs(conn, databaseFacts.repairs),
                    insertResolutions(conn, databaseFacts.resolutions),
                    insertOperatorActions(conn, databaseFacts.operatorActions)
                ).any { it }
                if (uncertainInserts) {
                    requirePersistedFacts(databaseFacts, factsByScenarioRunId(conn, facts.scenarioRunId))
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = previousAutoCommit
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
            resourcePositions = queryResourcePositions(conn, scenarioRunId),
            obligations = queryObligations(conn, scenarioRunId),
            allocations = queryAllocations(conn, scenarioRunId),
            confirmations = queryConfirmations(conn, scenarioRunId),
            affirmations = queryAffirmations(conn, scenarioRunId),
            clearingSubmissions = queryClearingSubmissions(conn, scenarioRunId),
            clearingAcceptances = queryClearingAcceptances(conn, scenarioRunId),
            clearingRejections = queryClearingRejections(conn, scenarioRunId),
            novations = queryNovations(conn, scenarioRunId),
            instructions = queryInstructions(conn, scenarioRunId),
            attempts = queryAttempts(conn, scenarioRunId),
            legOutcomes = queryLegOutcomes(conn, scenarioRunId),
            ledgerEntries = queryLedgerEntries(conn, scenarioRunId),
            settlements = querySettlements(conn, scenarioRunId),
            breaks = queryBreaks(conn, scenarioRunId),
            repairs = queryRepairs(conn, scenarioRunId),
            resolutions = queryResolutions(conn, scenarioRunId),
            operatorActions = queryOperatorActions(conn, scenarioRunId)
        )
    }

    private fun PreparedStatement.executeFactBatch(): Boolean {
        val counts = executeBatch()
        // INSERT ... SELECT keeps per-fact counts when pgjdbc batch rewriting is enabled.
        return counts.any { it != 1 }
    }

    private fun SettlementFactBundle.withMicrosecondTimestamps(): SettlementFactBundle {
        fun Instant.forPostgres(): Instant =
            Instant.ofEpochSecond(epochSecond, ((nano + 500) / 1_000 * 1_000).toLong())

        return copy(
            resourcePositions = resourcePositions.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            obligations = obligations.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            allocations = allocations.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            confirmations = confirmations.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            affirmations = affirmations.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            clearingSubmissions = clearingSubmissions.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            clearingAcceptances = clearingAcceptances.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            clearingRejections = clearingRejections.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            novations = novations.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            instructions = instructions.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            attempts = attempts.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            legOutcomes = legOutcomes.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            ledgerEntries = ledgerEntries.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            settlements = settlements.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            breaks = breaks.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            repairs = repairs.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            resolutions = resolutions.map { it.copy(occurredAt = it.occurredAt.forPostgres()) },
            operatorActions = operatorActions.map { it.copy(occurredAt = it.occurredAt.forPostgres()) }
        )
    }

    private fun requirePersistedFacts(requested: SettlementFactBundle, persisted: SettlementFactBundle) {
        fun <T : Any> verify(
            family: String,
            candidates: List<T>,
            records: List<T>,
            id: (T) -> String,
            occurredAt: (T) -> Instant,
            withOccurredAt: (T, Instant) -> T
        ) {
            val byId = records.associateBy(id)
            candidates.forEach { candidate ->
                val record = byId[id(candidate)]
                require(
                    record != null &&
                        Duration.between(occurredAt(candidate), occurredAt(record)).abs() <= Duration.ofNanos(1_000) &&
                        withOccurredAt(candidate, occurredAt(record)) == record
                ) { "Conflicting $family fact ${id(candidate)} in scenario run ${requested.scenarioRunId}" }
            }
        }

        verify("resource position", requested.resourcePositions, persisted.resourcePositions,
            { it.resourcePositionId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("obligation", requested.obligations, persisted.obligations,
            { it.settlementObligationId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("allocation", requested.allocations, persisted.allocations,
            { it.settlementAllocationId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("confirmation", requested.confirmations, persisted.confirmations,
            { it.settlementConfirmationId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("affirmation", requested.affirmations, persisted.affirmations,
            { it.settlementAffirmationId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("clearing submission", requested.clearingSubmissions, persisted.clearingSubmissions,
            { it.settlementClearingSubmissionId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("clearing acceptance", requested.clearingAcceptances, persisted.clearingAcceptances,
            { it.settlementClearingAcceptanceId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("clearing rejection", requested.clearingRejections, persisted.clearingRejections,
            { it.settlementClearingRejectionId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("novation", requested.novations, persisted.novations,
            { it.settlementNovationId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("instruction", requested.instructions, persisted.instructions,
            { it.settlementInstructionId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("attempt", requested.attempts, persisted.attempts,
            { it.settlementAttemptId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("leg outcome", requested.legOutcomes, persisted.legOutcomes,
            { it.settlementLegOutcomeId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("ledger entry", requested.ledgerEntries, persisted.ledgerEntries,
            { it.ledgerEntryId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("settlement", requested.settlements, persisted.settlements,
            { it.settlementId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("break", requested.breaks, persisted.breaks,
            { it.settlementBreakId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("repair", requested.repairs, persisted.repairs,
            { it.settlementRepairId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("resolution", requested.resolutions, persisted.resolutions,
            { it.settlementResolutionId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
        verify("operator action", requested.operatorActions, persisted.operatorActions,
            { it.settlementOperatorActionId }, { it.occurredAt }, { fact, at -> fact.copy(occurredAt = at) })
    }

    private fun insertResourcePositions(conn: Connection, facts: List<SettlementResourcePositionFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.resourcePositions}(
              resource_position_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, participant_id, account_id, asset_type, asset_id, quantity, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (resource_position_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.resourcePositionId)
                ps.setString(2, it.scenarioRunId)
                ps.setString(3, it.postTradeProfileId)
                ps.setInt(4, it.postTradePolicyVersion)
                ps.setString(5, it.correlationId)
                ps.setString(6, it.causationId)
                ps.setString(7, it.participantId)
                ps.setString(8, it.accountId)
                ps.setString(9, it.assetType)
                ps.setString(10, it.assetId)
                ps.setString(11, it.quantity)
                ps.setTimestamp(12, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertObligations(conn: Connection, facts: List<SettlementObligationCreatedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.obligations}(
              settlement_obligation_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, trade_id,
              buyer_participant_id, seller_participant_id, instrument_id, quantity, cash_amount,
              currency, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
            ps.executeFactBatch()
        }
    }

    private fun insertAllocations(conn: Connection, facts: List<SettlementAllocationProposedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.allocations}(
              settlement_allocation_id, settlement_obligation_id, scenario_run_id,
              post_trade_profile_id, post_trade_policy_version, correlation_id, causation_id,
              trade_id, buy_order_id, sell_order_id, buyer_account_id, seller_account_id,
              quantity, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_allocation_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementAllocationId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.scenarioRunId)
                ps.setString(4, it.postTradeProfileId)
                ps.setInt(5, it.postTradePolicyVersion)
                ps.setString(6, it.correlationId)
                ps.setString(7, it.causationId)
                ps.setString(8, it.tradeId)
                ps.setString(9, it.buyOrderId)
                ps.setString(10, it.sellOrderId)
                ps.setString(11, it.buyerAccountId)
                ps.setString(12, it.sellerAccountId)
                ps.setString(13, it.quantity)
                ps.setString(14, it.state)
                ps.setTimestamp(15, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertConfirmations(conn: Connection, facts: List<SettlementConfirmationGeneratedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.confirmations}(
              settlement_confirmation_id, settlement_allocation_id, settlement_obligation_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, trade_id, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_confirmation_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementConfirmationId)
                ps.setString(2, it.settlementAllocationId)
                ps.setString(3, it.settlementObligationId)
                ps.setString(4, it.scenarioRunId)
                ps.setString(5, it.postTradeProfileId)
                ps.setInt(6, it.postTradePolicyVersion)
                ps.setString(7, it.correlationId)
                ps.setString(8, it.causationId)
                ps.setString(9, it.tradeId)
                ps.setString(10, it.state)
                ps.setTimestamp(11, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertAffirmations(conn: Connection, facts: List<SettlementAffirmationAcceptedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.affirmations}(
              settlement_affirmation_id, settlement_confirmation_id, settlement_allocation_id,
              settlement_obligation_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, trade_id, actor_type, actor_id, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_affirmation_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementAffirmationId)
                ps.setString(2, it.settlementConfirmationId)
                ps.setString(3, it.settlementAllocationId)
                ps.setString(4, it.settlementObligationId)
                ps.setString(5, it.scenarioRunId)
                ps.setString(6, it.postTradeProfileId)
                ps.setInt(7, it.postTradePolicyVersion)
                ps.setString(8, it.correlationId)
                ps.setString(9, it.causationId)
                ps.setString(10, it.tradeId)
                ps.setString(11, it.actorType)
                ps.setString(12, it.actorId)
                ps.setString(13, it.state)
                ps.setTimestamp(14, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertClearingSubmissions(conn: Connection, facts: List<SettlementClearingSubmittedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.clearingSubmissions}(
              settlement_clearing_submission_id, settlement_obligation_id, settlement_affirmation_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_clearing_submission_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementClearingSubmissionId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.settlementAffirmationId)
                ps.setString(4, it.scenarioRunId)
                ps.setString(5, it.postTradeProfileId)
                ps.setInt(6, it.postTradePolicyVersion)
                ps.setString(7, it.correlationId)
                ps.setString(8, it.causationId)
                ps.setString(9, it.state)
                ps.setTimestamp(10, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertClearingAcceptances(conn: Connection, facts: List<SettlementClearingAcceptedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.clearingAcceptances}(
              settlement_clearing_acceptance_id, settlement_clearing_submission_id,
              settlement_obligation_id, scenario_run_id, post_trade_profile_id,
              post_trade_policy_version, correlation_id, causation_id, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_clearing_acceptance_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementClearingAcceptanceId)
                ps.setString(2, it.settlementClearingSubmissionId)
                ps.setString(3, it.settlementObligationId)
                ps.setString(4, it.scenarioRunId)
                ps.setString(5, it.postTradeProfileId)
                ps.setInt(6, it.postTradePolicyVersion)
                ps.setString(7, it.correlationId)
                ps.setString(8, it.causationId)
                ps.setString(9, it.state)
                ps.setTimestamp(10, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertClearingRejections(conn: Connection, facts: List<SettlementClearingRejectedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.clearingRejections}(
              settlement_clearing_rejection_id, settlement_clearing_submission_id,
              settlement_obligation_id, scenario_run_id, post_trade_profile_id,
              post_trade_policy_version, correlation_id, causation_id, reason, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_clearing_rejection_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementClearingRejectionId)
                ps.setString(2, it.settlementClearingSubmissionId)
                ps.setString(3, it.settlementObligationId)
                ps.setString(4, it.scenarioRunId)
                ps.setString(5, it.postTradeProfileId)
                ps.setInt(6, it.postTradePolicyVersion)
                ps.setString(7, it.correlationId)
                ps.setString(8, it.causationId)
                ps.setString(9, it.reason)
                ps.setString(10, it.state)
                ps.setTimestamp(11, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertNovations(conn: Connection, facts: List<SettlementNovationRecordedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.novations}(
              settlement_novation_id, settlement_clearing_acceptance_id, settlement_obligation_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_novation_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementNovationId)
                ps.setString(2, it.settlementClearingAcceptanceId)
                ps.setString(3, it.settlementObligationId)
                ps.setString(4, it.scenarioRunId)
                ps.setString(5, it.postTradeProfileId)
                ps.setInt(6, it.postTradePolicyVersion)
                ps.setString(7, it.correlationId)
                ps.setString(8, it.causationId)
                ps.setString(9, it.state)
                ps.setTimestamp(10, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertAttempts(conn: Connection, facts: List<SettlementAttemptStartedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.attempts}(
              settlement_attempt_id, settlement_obligation_id, scenario_run_id,
              settlement_instruction_id,
              post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, attempt_number, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_attempt_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementAttemptId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.scenarioRunId)
                ps.setString(4, it.settlementInstructionId)
                ps.setString(5, it.postTradeProfileId)
                ps.setInt(6, it.postTradePolicyVersion)
                ps.setString(7, it.correlationId)
                ps.setString(8, it.causationId)
                ps.setInt(9, it.attemptNumber)
                ps.setString(10, it.state)
                ps.setTimestamp(11, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertInstructions(conn: Connection, facts: List<SettlementInstructionCreatedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.instructions}(
              settlement_instruction_id, settlement_obligation_id, scenario_run_id,
              post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, instruction_type, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_instruction_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementInstructionId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.scenarioRunId)
                ps.setString(4, it.postTradeProfileId)
                ps.setInt(5, it.postTradePolicyVersion)
                ps.setString(6, it.correlationId)
                ps.setString(7, it.causationId)
                ps.setString(8, it.instructionType)
                ps.setString(9, it.state)
                ps.setTimestamp(10, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertLegOutcomes(conn: Connection, facts: List<SettlementLegOutcomeFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.legOutcomes}(
              settlement_leg_outcome_id, settlement_obligation_id, settlement_instruction_id,
              settlement_attempt_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, leg_type, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_leg_outcome_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementLegOutcomeId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.settlementInstructionId)
                ps.setString(4, it.settlementAttemptId)
                ps.setString(5, it.scenarioRunId)
                ps.setString(6, it.postTradeProfileId)
                ps.setInt(7, it.postTradePolicyVersion)
                ps.setString(8, it.correlationId)
                ps.setString(9, it.causationId)
                ps.setString(10, it.legType)
                ps.setString(11, it.state)
                ps.setTimestamp(12, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertLedgerEntries(conn: Connection, facts: List<SettlementLedgerEntryFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.ledgerEntries}(
              ledger_entry_id, settlement_obligation_id, settlement_instruction_id,
              settlement_attempt_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, participant_id, account_id, asset_type, asset_id,
              direction, quantity, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (ledger_entry_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.ledgerEntryId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.settlementInstructionId)
                ps.setString(4, it.settlementAttemptId)
                ps.setString(5, it.scenarioRunId)
                ps.setString(6, it.postTradeProfileId)
                ps.setInt(7, it.postTradePolicyVersion)
                ps.setString(8, it.correlationId)
                ps.setString(9, it.causationId)
                ps.setString(10, it.participantId)
                ps.setString(11, it.accountId)
                ps.setString(12, it.assetType)
                ps.setString(13, it.assetId)
                ps.setString(14, it.direction)
                ps.setString(15, it.quantity)
                ps.setTimestamp(16, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertSettlements(conn: Connection, facts: List<SettlementSettledFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.settlements}(
              settlement_id, settlement_obligation_id, settlement_instruction_id, settlement_attempt_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version, correlation_id,
              causation_id, settlement_state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementId)
                ps.setString(2, it.settlementObligationId)
                ps.setString(3, it.settlementInstructionId)
                ps.setString(4, it.settlementAttemptId)
                ps.setString(5, it.scenarioRunId)
                ps.setString(6, it.postTradeProfileId)
                ps.setInt(7, it.postTradePolicyVersion)
                ps.setString(8, it.correlationId)
                ps.setString(9, it.causationId)
                ps.setString(10, it.settlementState)
                ps.setTimestamp(11, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun insertBreaks(conn: Connection, facts: List<SettlementBreakOpenedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.breaks}(
              settlement_break_id, settlement_obligation_id, scenario_run_id, correlation_id,
              causation_id, post_trade_profile_id, post_trade_policy_version, reason, state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
            ps.executeFactBatch()
        }
    }

    private fun insertRepairs(conn: Connection, facts: List<SettlementRepairPostedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.repairs}(
              settlement_repair_id, settlement_break_id, settlement_obligation_id, scenario_run_id,
              correlation_id, causation_id, post_trade_profile_id, post_trade_policy_version,
              repair_action, actor_type, actor_id, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
            ps.executeFactBatch()
        }
    }

    private fun insertResolutions(conn: Connection, facts: List<SettlementResolvedFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.resolutions}(
              settlement_resolution_id, settlement_obligation_id, settlement_break_id, settlement_repair_id,
              scenario_run_id, correlation_id, causation_id, post_trade_profile_id, post_trade_policy_version,
              settlement_state, exception_state, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
            ps.executeFactBatch()
        }
    }

    private fun insertOperatorActions(conn: Connection, facts: List<SettlementOperatorActionFact>): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO ${names.operatorActions}(
              settlement_operator_action_id, scenario_run_id, post_trade_profile_id,
              post_trade_policy_version, correlation_id, causation_id, action, target_id,
              reason_note, actor_type, actor_id, occurred_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            ON CONFLICT (settlement_operator_action_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            facts.forEach {
                ps.setString(1, it.settlementOperatorActionId)
                ps.setString(2, it.scenarioRunId)
                ps.setString(3, it.postTradeProfileId)
                ps.setInt(4, it.postTradePolicyVersion)
                ps.setString(5, it.correlationId)
                ps.setString(6, it.causationId)
                ps.setString(7, it.action)
                ps.setString(8, it.targetId)
                ps.setString(9, it.reasonNote)
                ps.setString(10, it.actorType)
                ps.setString(11, it.actorId)
                ps.setTimestamp(12, Timestamp.from(it.occurredAt))
                ps.addBatch()
            }
            ps.executeFactBatch()
        }
    }

    private fun queryResourcePositions(conn: Connection, scenarioRunId: String): List<SettlementResourcePositionFact> {
        conn.prepareStatement(
            """
            SELECT resource_position_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, participant_id, account_id, asset_type, asset_id, quantity, occurred_at
            FROM ${names.resourcePositions}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, resource_position_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementResourcePositionFact>()
                while (rs.next()) records.add(rs.toResourcePosition())
                return records
            }
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

    private fun queryAllocations(conn: Connection, scenarioRunId: String): List<SettlementAllocationProposedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_allocation_id, settlement_obligation_id, scenario_run_id,
                   post_trade_profile_id, post_trade_policy_version, correlation_id, causation_id,
                   trade_id, buy_order_id, sell_order_id, buyer_account_id, seller_account_id,
                   quantity, state, occurred_at
            FROM ${names.allocations}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_allocation_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementAllocationProposedFact>()
                while (rs.next()) records.add(rs.toAllocation())
                return records
            }
        }
    }

    private fun queryConfirmations(conn: Connection, scenarioRunId: String): List<SettlementConfirmationGeneratedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_confirmation_id, settlement_allocation_id, settlement_obligation_id,
                   scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, trade_id, state, occurred_at
            FROM ${names.confirmations}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_confirmation_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementConfirmationGeneratedFact>()
                while (rs.next()) records.add(rs.toConfirmation())
                return records
            }
        }
    }

    private fun queryAffirmations(conn: Connection, scenarioRunId: String): List<SettlementAffirmationAcceptedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_affirmation_id, settlement_confirmation_id, settlement_allocation_id,
                   settlement_obligation_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, trade_id, actor_type, actor_id, state, occurred_at
            FROM ${names.affirmations}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_affirmation_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementAffirmationAcceptedFact>()
                while (rs.next()) records.add(rs.toAffirmation())
                return records
            }
        }
    }

    private fun queryClearingSubmissions(conn: Connection, scenarioRunId: String): List<SettlementClearingSubmittedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_clearing_submission_id, settlement_obligation_id, settlement_affirmation_id,
                   scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, state, occurred_at
            FROM ${names.clearingSubmissions}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_clearing_submission_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementClearingSubmittedFact>()
                while (rs.next()) records.add(rs.toClearingSubmission())
                return records
            }
        }
    }

    private fun queryClearingAcceptances(conn: Connection, scenarioRunId: String): List<SettlementClearingAcceptedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_clearing_acceptance_id, settlement_clearing_submission_id,
                   settlement_obligation_id, scenario_run_id, post_trade_profile_id,
                   post_trade_policy_version, correlation_id, causation_id, state, occurred_at
            FROM ${names.clearingAcceptances}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_clearing_acceptance_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementClearingAcceptedFact>()
                while (rs.next()) records.add(rs.toClearingAcceptance())
                return records
            }
        }
    }

    private fun queryClearingRejections(conn: Connection, scenarioRunId: String): List<SettlementClearingRejectedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_clearing_rejection_id, settlement_clearing_submission_id,
                   settlement_obligation_id, scenario_run_id, post_trade_profile_id,
                   post_trade_policy_version, correlation_id, causation_id, reason, state, occurred_at
            FROM ${names.clearingRejections}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_clearing_rejection_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementClearingRejectedFact>()
                while (rs.next()) records.add(rs.toClearingRejection())
                return records
            }
        }
    }

    private fun queryNovations(conn: Connection, scenarioRunId: String): List<SettlementNovationRecordedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_novation_id, settlement_clearing_acceptance_id, settlement_obligation_id,
                   scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, state, occurred_at
            FROM ${names.novations}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_novation_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementNovationRecordedFact>()
                while (rs.next()) records.add(rs.toNovation())
                return records
            }
        }
    }

    private fun queryAttempts(conn: Connection, scenarioRunId: String): List<SettlementAttemptStartedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_attempt_id, settlement_obligation_id, scenario_run_id,
                   settlement_instruction_id, post_trade_profile_id, post_trade_policy_version, correlation_id, causation_id,
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

    private fun queryInstructions(conn: Connection, scenarioRunId: String): List<SettlementInstructionCreatedFact> {
        conn.prepareStatement(
            """
            SELECT settlement_instruction_id, settlement_obligation_id, scenario_run_id,
                   post_trade_profile_id, post_trade_policy_version, correlation_id, causation_id,
                   instruction_type, state, occurred_at
            FROM ${names.instructions}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_instruction_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementInstructionCreatedFact>()
                while (rs.next()) records.add(rs.toInstruction())
                return records
            }
        }
    }

    private fun queryLegOutcomes(conn: Connection, scenarioRunId: String): List<SettlementLegOutcomeFact> {
        conn.prepareStatement(
            """
            SELECT settlement_leg_outcome_id, settlement_obligation_id, settlement_instruction_id,
                   settlement_attempt_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, leg_type, state, occurred_at
            FROM ${names.legOutcomes}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_leg_outcome_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementLegOutcomeFact>()
                while (rs.next()) records.add(rs.toLegOutcome())
                return records
            }
        }
    }

    private fun queryLedgerEntries(conn: Connection, scenarioRunId: String): List<SettlementLedgerEntryFact> {
        conn.prepareStatement(
            """
            SELECT ledger_entry_id, settlement_obligation_id, settlement_instruction_id,
                   settlement_attempt_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
                   correlation_id, causation_id, participant_id, account_id, asset_type, asset_id,
                   direction, quantity, occurred_at
            FROM ${names.ledgerEntries}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, ledger_entry_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementLedgerEntryFact>()
                while (rs.next()) records.add(rs.toLedgerEntry())
                return records
            }
        }
    }

    private fun querySettlements(conn: Connection, scenarioRunId: String): List<SettlementSettledFact> {
        conn.prepareStatement(
            """
            SELECT settlement_id, settlement_obligation_id, settlement_instruction_id, settlement_attempt_id,
                   scenario_run_id, post_trade_profile_id, post_trade_policy_version, correlation_id,
                   causation_id, settlement_state, occurred_at
            FROM ${names.settlements}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementSettledFact>()
                while (rs.next()) records.add(rs.toSettlement())
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

    private fun queryOperatorActions(conn: Connection, scenarioRunId: String): List<SettlementOperatorActionFact> {
        conn.prepareStatement(
            """
            SELECT settlement_operator_action_id, scenario_run_id, post_trade_profile_id,
                   post_trade_policy_version, correlation_id, causation_id, action, target_id,
                   reason_note, actor_type, actor_id, occurred_at
            FROM ${names.operatorActions}
            WHERE scenario_run_id = ?
            ORDER BY occurred_at, settlement_operator_action_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, scenarioRunId)
            ps.executeQuery().use { rs ->
                val records = mutableListOf<SettlementOperatorActionFact>()
                while (rs.next()) records.add(rs.toOperatorAction())
                return records
            }
        }
    }

    private fun createIndexes(stmt: java.sql.Statement) {
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_resource_positions_run ON ${names.resourcePositions}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_obligations_run ON ${names.obligations}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_allocations_run ON ${names.allocations}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_confirmations_run ON ${names.confirmations}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_affirmations_run ON ${names.affirmations}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_clearing_submissions_run ON ${names.clearingSubmissions}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_clearing_acceptances_run ON ${names.clearingAcceptances}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_clearing_rejections_run ON ${names.clearingRejections}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_novations_run ON ${names.novations}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_instructions_run ON ${names.instructions}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_attempts_run ON ${names.attempts}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_leg_outcomes_run ON ${names.legOutcomes}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_ledger_entries_run ON ${names.ledgerEntries}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_settlements_run ON ${names.settlements}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_breaks_run ON ${names.breaks}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_repairs_run ON ${names.repairs}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_resolutions_run ON ${names.resolutions}(scenario_run_id, occurred_at)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_operator_actions_run ON ${names.operatorActions}(scenario_run_id, occurred_at)")
    }

    private fun ensureSettlementEvidenceColumns(stmt: java.sql.Statement) {
        listOf(
            names.resourcePositions,
            names.obligations,
            names.allocations,
            names.confirmations,
            names.affirmations,
            names.clearingSubmissions,
            names.clearingAcceptances,
            names.clearingRejections,
            names.novations,
            names.instructions,
            names.attempts,
            names.legOutcomes,
            names.ledgerEntries,
            names.settlements,
            names.breaks,
            names.repairs,
            names.resolutions,
            names.operatorActions
        ).forEach { table ->
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

    private fun ensureSettlementAttemptInstructionColumn(stmt: java.sql.Statement) {
        stmt.execute(
            "ALTER TABLE ${names.attempts} ADD COLUMN IF NOT EXISTS settlement_instruction_id " +
                "TEXT NOT NULL DEFAULT ''"
        )
    }

    private fun connection(): Connection = dataSource.connection
}

internal fun SettlementFactBundle.merge(next: SettlementFactBundle): SettlementFactBundle {
    require(scenarioRunId == next.scenarioRunId) { "settlement fact scenarioRunId mismatch" }
    return copy(
        resourcePositions = resourcePositions.byId(next.resourcePositions) { it.resourcePositionId },
        obligations = obligations.byId(next.obligations) { it.settlementObligationId },
        allocations = allocations.byId(next.allocations) { it.settlementAllocationId },
        confirmations = confirmations.byId(next.confirmations) { it.settlementConfirmationId },
        affirmations = affirmations.byId(next.affirmations) { it.settlementAffirmationId },
        clearingSubmissions = clearingSubmissions.byId(next.clearingSubmissions) { it.settlementClearingSubmissionId },
        clearingAcceptances = clearingAcceptances.byId(next.clearingAcceptances) { it.settlementClearingAcceptanceId },
        clearingRejections = clearingRejections.byId(next.clearingRejections) { it.settlementClearingRejectionId },
        novations = novations.byId(next.novations) { it.settlementNovationId },
        instructions = instructions.byId(next.instructions) { it.settlementInstructionId },
        attempts = attempts.byId(next.attempts) { it.settlementAttemptId },
        legOutcomes = legOutcomes.byId(next.legOutcomes) { it.settlementLegOutcomeId },
        ledgerEntries = ledgerEntries.byId(next.ledgerEntries) { it.ledgerEntryId },
        settlements = settlements.byId(next.settlements) { it.settlementId },
        breaks = breaks.byId(next.breaks) { it.settlementBreakId },
        repairs = repairs.byId(next.repairs) { it.settlementRepairId },
        resolutions = resolutions.byId(next.resolutions) { it.settlementResolutionId },
        operatorActions = operatorActions.byId(next.operatorActions) { it.settlementOperatorActionId }
    )
}

internal fun <T> List<T>.byId(next: List<T>, id: (T) -> String): List<T> {
    val byId = linkedMapOf<String, T>()
    (this + next).forEach {
        val existing = byId.putIfAbsent(id(it), it)
        require(existing == null || existing == it) { "settlement fact id reused with different payload" }
    }
    return byId.values.toList()
}
