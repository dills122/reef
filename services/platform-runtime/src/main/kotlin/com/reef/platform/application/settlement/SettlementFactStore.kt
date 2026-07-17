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
const val SettlementAllocationProposedState = "ALLOCATION_PROPOSED"
const val SettlementConfirmationGeneratedState = "CONFIRMATION_GENERATED"
const val SettlementAffirmationAcceptedState = "AFFIRMATION_ACCEPTED"
const val SettlementAffirmationActorType = "SYSTEM"
const val SettlementAffirmationActorId = "post-trade-auto-affirmer"
const val SettlementClearingSubmittedState = "CLEARING_SUBMITTED"
const val SettlementClearingAcceptedState = "CLEARING_ACCEPTED"
const val SettlementClearingRejectedState = "CLEARING_REJECTED"
const val SettlementClearingRejectedReason = "CLEARING_REJECT"
const val SettlementNovationRecordedState = "NOVATION_RECORDED"
const val SettlementInstructionCreatedState = "INSTRUCTION_CREATED"
const val SettlementInstructionTypeDvp = "DVP"
const val SettlementAttemptStartedState = "ATTEMPT_STARTED"
const val SettlementLegTypeCash = "CASH"
const val SettlementLegTypeSecurity = "SECURITY"
const val SettlementLegSucceededState = "LEG_SUCCEEDED"
const val SettlementLegFailedState = "LEG_FAILED"
const val SettlementLedgerEntryTypeCash = "CASH"
const val SettlementLedgerEntryTypeSecurity = "SECURITY"
const val SettlementLedgerDirectionDebit = "DEBIT"
const val SettlementLedgerDirectionCredit = "CREDIT"
const val SettlementSettledState = "SETTLED"
const val SettlementBreakOpenedReason = "CASH_LEG_FAILED"
const val SettlementBreakOpenedReasonSecurity = "SECURITY_LEG_FAILED"
const val SettlementBreakOpenedState = "BROKEN"
const val SettlementRepairPostedActionCash = "POST_CASH_LEG_REPAIR"
const val SettlementRepairPostedActionSecurity = "POST_SECURITY_LEG_REPAIR"
const val SettlementRepairPostedAction = SettlementRepairPostedActionCash
const val SettlementRepairPostedActorType = "USER"
const val SettlementResolvedState = "RESOLVED"
const val SettlementOperatorActionForceSettle = "FORCE_SETTLE"
const val SettlementOperatorActionReverseLedgerEntry = "REVERSE_LEDGER_ENTRY"
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

data class SettlementAllocationProposedFact(
    val settlementAllocationId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val buyOrderId: String,
    val sellOrderId: String,
    val buyerAccountId: String,
    val sellerAccountId: String,
    val quantity: String,
    val state: String = SettlementAllocationProposedState,
    val occurredAt: Instant
)

data class SettlementConfirmationGeneratedFact(
    val settlementConfirmationId: String,
    val settlementAllocationId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val state: String = SettlementConfirmationGeneratedState,
    val occurredAt: Instant
)

data class SettlementAffirmationAcceptedFact(
    val settlementAffirmationId: String,
    val settlementConfirmationId: String,
    val settlementAllocationId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val actorType: String = SettlementAffirmationActorType,
    val actorId: String = SettlementAffirmationActorId,
    val state: String = SettlementAffirmationAcceptedState,
    val occurredAt: Instant
)

data class SettlementClearingSubmittedFact(
    val settlementClearingSubmissionId: String,
    val settlementObligationId: String,
    val settlementAffirmationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val state: String = SettlementClearingSubmittedState,
    val occurredAt: Instant
)

data class SettlementClearingAcceptedFact(
    val settlementClearingAcceptanceId: String,
    val settlementClearingSubmissionId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val state: String = SettlementClearingAcceptedState,
    val occurredAt: Instant
)

data class SettlementClearingRejectedFact(
    val settlementClearingRejectionId: String,
    val settlementClearingSubmissionId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val reason: String = SettlementClearingRejectedReason,
    val state: String = SettlementClearingRejectedState,
    val occurredAt: Instant
)

data class SettlementNovationRecordedFact(
    val settlementNovationId: String,
    val settlementClearingAcceptanceId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val state: String = SettlementNovationRecordedState,
    val occurredAt: Instant
)

data class SettlementInstructionCreatedFact(
    val settlementInstructionId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val instructionType: String = SettlementInstructionTypeDvp,
    val state: String = SettlementInstructionCreatedState,
    val occurredAt: Instant
)

data class SettlementAttemptStartedFact(
    val settlementAttemptId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val attemptNumber: Int = 1,
    val state: String = SettlementAttemptStartedState,
    val occurredAt: Instant
)

data class SettlementResourcePositionFact(
    val resourcePositionId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val participantId: String,
    val accountId: String,
    val assetType: String,
    val assetId: String,
    val quantity: String,
    val occurredAt: Instant
)

data class SettlementLegOutcomeFact(
    val settlementLegOutcomeId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val legType: String,
    val state: String = SettlementLegSucceededState,
    val occurredAt: Instant
)

data class SettlementLedgerEntryFact(
    val ledgerEntryId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val participantId: String,
    val accountId: String,
    val assetType: String,
    val assetId: String,
    val direction: String,
    val quantity: String,
    val occurredAt: Instant
)

data class SettlementSettledFact(
    val settlementId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val settlementState: String = SettlementSettledState,
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

data class SettlementOperatorActionFact(
    val settlementOperatorActionId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val action: String,
    val targetId: String,
    val reasonNote: String,
    val actorType: String = SettlementRepairPostedActorType,
    val actorId: String,
    val occurredAt: Instant
)

data class SettlementFactBundle(
    val scenarioRunId: String,
    val resourcePositions: List<SettlementResourcePositionFact> = emptyList(),
    val obligations: List<SettlementObligationCreatedFact> = emptyList(),
    val allocations: List<SettlementAllocationProposedFact> = emptyList(),
    val confirmations: List<SettlementConfirmationGeneratedFact> = emptyList(),
    val affirmations: List<SettlementAffirmationAcceptedFact> = emptyList(),
    val clearingSubmissions: List<SettlementClearingSubmittedFact> = emptyList(),
    val clearingAcceptances: List<SettlementClearingAcceptedFact> = emptyList(),
    val clearingRejections: List<SettlementClearingRejectedFact> = emptyList(),
    val novations: List<SettlementNovationRecordedFact> = emptyList(),
    val instructions: List<SettlementInstructionCreatedFact> = emptyList(),
    val attempts: List<SettlementAttemptStartedFact> = emptyList(),
    val legOutcomes: List<SettlementLegOutcomeFact> = emptyList(),
    val ledgerEntries: List<SettlementLedgerEntryFact> = emptyList(),
    val settlements: List<SettlementSettledFact> = emptyList(),
    val breaks: List<SettlementBreakOpenedFact> = emptyList(),
    val repairs: List<SettlementRepairPostedFact> = emptyList(),
    val resolutions: List<SettlementResolvedFact> = emptyList(),
    val operatorActions: List<SettlementOperatorActionFact> = emptyList()
) {
    fun isEmpty(): Boolean =
        resourcePositions.isEmpty() && obligations.isEmpty() && allocations.isEmpty() &&
            confirmations.isEmpty() && affirmations.isEmpty() && clearingSubmissions.isEmpty() &&
            clearingAcceptances.isEmpty() && clearingRejections.isEmpty() && novations.isEmpty() &&
            instructions.isEmpty() && attempts.isEmpty() &&
            legOutcomes.isEmpty() && ledgerEntries.isEmpty() && settlements.isEmpty() &&
            breaks.isEmpty() && repairs.isEmpty() && resolutions.isEmpty() && operatorActions.isEmpty()
}

interface SettlementFactStore {
    fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle
    fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle
}

class InMemorySettlementFactStore : SettlementFactStore {
    private val resourcePositions = ConcurrentHashMap<String, SettlementResourcePositionFact>()
    private val obligations = ConcurrentHashMap<String, SettlementObligationCreatedFact>()
    private val allocations = ConcurrentHashMap<String, SettlementAllocationProposedFact>()
    private val confirmations = ConcurrentHashMap<String, SettlementConfirmationGeneratedFact>()
    private val affirmations = ConcurrentHashMap<String, SettlementAffirmationAcceptedFact>()
    private val clearingSubmissions = ConcurrentHashMap<String, SettlementClearingSubmittedFact>()
    private val clearingAcceptances = ConcurrentHashMap<String, SettlementClearingAcceptedFact>()
    private val clearingRejections = ConcurrentHashMap<String, SettlementClearingRejectedFact>()
    private val novations = ConcurrentHashMap<String, SettlementNovationRecordedFact>()
    private val instructions = ConcurrentHashMap<String, SettlementInstructionCreatedFact>()
    private val attempts = ConcurrentHashMap<String, SettlementAttemptStartedFact>()
    private val legOutcomes = ConcurrentHashMap<String, SettlementLegOutcomeFact>()
    private val ledgerEntries = ConcurrentHashMap<String, SettlementLedgerEntryFact>()
    private val settlements = ConcurrentHashMap<String, SettlementSettledFact>()
    private val breaks = ConcurrentHashMap<String, SettlementBreakOpenedFact>()
    private val repairs = ConcurrentHashMap<String, SettlementRepairPostedFact>()
    private val resolutions = ConcurrentHashMap<String, SettlementResolvedFact>()
    private val operatorActions = ConcurrentHashMap<String, SettlementOperatorActionFact>()

    override fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle {
        if (facts.isEmpty()) return facts
        val existing = factsByScenarioRunId(facts.scenarioRunId)
        validateSettlementFacts(existing.merge(facts))
        facts.resourcePositions.forEach { resourcePositions.putIfAbsent(it.resourcePositionId, it) }
        facts.obligations.forEach { obligations.putIfAbsent(it.settlementObligationId, it) }
        facts.allocations.forEach { allocations.putIfAbsent(it.settlementAllocationId, it) }
        facts.confirmations.forEach { confirmations.putIfAbsent(it.settlementConfirmationId, it) }
        facts.affirmations.forEach { affirmations.putIfAbsent(it.settlementAffirmationId, it) }
        facts.clearingSubmissions.forEach { clearingSubmissions.putIfAbsent(it.settlementClearingSubmissionId, it) }
        facts.clearingAcceptances.forEach { clearingAcceptances.putIfAbsent(it.settlementClearingAcceptanceId, it) }
        facts.clearingRejections.forEach { clearingRejections.putIfAbsent(it.settlementClearingRejectionId, it) }
        facts.novations.forEach { novations.putIfAbsent(it.settlementNovationId, it) }
        facts.instructions.forEach { instructions.putIfAbsent(it.settlementInstructionId, it) }
        facts.attempts.forEach { attempts.putIfAbsent(it.settlementAttemptId, it) }
        facts.legOutcomes.forEach { legOutcomes.putIfAbsent(it.settlementLegOutcomeId, it) }
        facts.ledgerEntries.forEach { ledgerEntries.putIfAbsent(it.ledgerEntryId, it) }
        facts.settlements.forEach { settlements.putIfAbsent(it.settlementId, it) }
        facts.breaks.forEach { breaks.putIfAbsent(it.settlementBreakId, it) }
        facts.repairs.forEach { repairs.putIfAbsent(it.settlementRepairId, it) }
        facts.resolutions.forEach { resolutions.putIfAbsent(it.settlementResolutionId, it) }
        facts.operatorActions.forEach { operatorActions.putIfAbsent(it.settlementOperatorActionId, it) }
        return facts
    }

    override fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle {
        require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            resourcePositions = resourcePositions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            obligations = obligations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            allocations = allocations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            confirmations = confirmations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            affirmations = affirmations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            clearingSubmissions = clearingSubmissions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            clearingAcceptances = clearingAcceptances.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            clearingRejections = clearingRejections.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            novations = novations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            instructions = instructions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            attempts = attempts.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            legOutcomes = legOutcomes.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            ledgerEntries = ledgerEntries.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            settlements = settlements.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            breaks = breaks.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            repairs = repairs.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            resolutions = resolutions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            operatorActions = operatorActions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt }
        )
    }
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
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val existing = factsByScenarioRunId(conn, facts.scenarioRunId)
                validateSettlementFacts(existing.merge(facts))
                insertResourcePositions(conn, facts.resourcePositions)
                insertObligations(conn, facts.obligations)
                insertAllocations(conn, facts.allocations)
                insertConfirmations(conn, facts.confirmations)
                insertAffirmations(conn, facts.affirmations)
                insertClearingSubmissions(conn, facts.clearingSubmissions)
                insertClearingAcceptances(conn, facts.clearingAcceptances)
                insertClearingRejections(conn, facts.clearingRejections)
                insertNovations(conn, facts.novations)
                insertInstructions(conn, facts.instructions)
                insertAttempts(conn, facts.attempts)
                insertLegOutcomes(conn, facts.legOutcomes)
                insertLedgerEntries(conn, facts.ledgerEntries)
                insertSettlements(conn, facts.settlements)
                insertBreaks(conn, facts.breaks)
                insertRepairs(conn, facts.repairs)
                insertResolutions(conn, facts.resolutions)
                insertOperatorActions(conn, facts.operatorActions)
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

    private fun insertResourcePositions(conn: Connection, facts: List<SettlementResourcePositionFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.resourcePositions}(
              resource_position_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, participant_id, account_id, asset_type, asset_id, quantity, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
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

    private fun insertAllocations(conn: Connection, facts: List<SettlementAllocationProposedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.allocations}(
              settlement_allocation_id, settlement_obligation_id, scenario_run_id,
              post_trade_profile_id, post_trade_policy_version, correlation_id, causation_id,
              trade_id, buy_order_id, sell_order_id, buyer_account_id, seller_account_id,
              quantity, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertConfirmations(conn: Connection, facts: List<SettlementConfirmationGeneratedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.confirmations}(
              settlement_confirmation_id, settlement_allocation_id, settlement_obligation_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, trade_id, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertAffirmations(conn: Connection, facts: List<SettlementAffirmationAcceptedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.affirmations}(
              settlement_affirmation_id, settlement_confirmation_id, settlement_allocation_id,
              settlement_obligation_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, trade_id, actor_type, actor_id, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertClearingSubmissions(conn: Connection, facts: List<SettlementClearingSubmittedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.clearingSubmissions}(
              settlement_clearing_submission_id, settlement_obligation_id, settlement_affirmation_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertClearingAcceptances(conn: Connection, facts: List<SettlementClearingAcceptedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.clearingAcceptances}(
              settlement_clearing_acceptance_id, settlement_clearing_submission_id,
              settlement_obligation_id, scenario_run_id, post_trade_profile_id,
              post_trade_policy_version, correlation_id, causation_id, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertClearingRejections(conn: Connection, facts: List<SettlementClearingRejectedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.clearingRejections}(
              settlement_clearing_rejection_id, settlement_clearing_submission_id,
              settlement_obligation_id, scenario_run_id, post_trade_profile_id,
              post_trade_policy_version, correlation_id, causation_id, reason, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertNovations(conn: Connection, facts: List<SettlementNovationRecordedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.novations}(
              settlement_novation_id, settlement_clearing_acceptance_id, settlement_obligation_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertAttempts(conn: Connection, facts: List<SettlementAttemptStartedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.attempts}(
              settlement_attempt_id, settlement_obligation_id, scenario_run_id,
              settlement_instruction_id,
              post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, attempt_number, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertInstructions(conn: Connection, facts: List<SettlementInstructionCreatedFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.instructions}(
              settlement_instruction_id, settlement_obligation_id, scenario_run_id,
              post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, instruction_type, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertLegOutcomes(conn: Connection, facts: List<SettlementLegOutcomeFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.legOutcomes}(
              settlement_leg_outcome_id, settlement_obligation_id, settlement_instruction_id,
              settlement_attempt_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, leg_type, state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertLedgerEntries(conn: Connection, facts: List<SettlementLedgerEntryFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.ledgerEntries}(
              ledger_entry_id, settlement_obligation_id, settlement_instruction_id,
              settlement_attempt_id, scenario_run_id, post_trade_profile_id, post_trade_policy_version,
              correlation_id, causation_id, participant_id, account_id, asset_type, asset_id,
              direction, quantity, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
        }
    }

    private fun insertSettlements(conn: Connection, facts: List<SettlementSettledFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.settlements}(
              settlement_id, settlement_obligation_id, settlement_instruction_id, settlement_attempt_id,
              scenario_run_id, post_trade_profile_id, post_trade_policy_version, correlation_id,
              causation_id, settlement_state, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    private fun insertOperatorActions(conn: Connection, facts: List<SettlementOperatorActionFact>) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.operatorActions}(
              settlement_operator_action_id, scenario_run_id, post_trade_profile_id,
              post_trade_policy_version, correlation_id, causation_id, action, target_id,
              reason_note, actor_type, actor_id, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.executeBatch()
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

private fun SettlementFactBundle.merge(next: SettlementFactBundle): SettlementFactBundle {
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
    val allocations = facts.allocations.associateBy { it.settlementAllocationId }
    val confirmations = facts.confirmations.associateBy { it.settlementConfirmationId }
    val affirmations = facts.affirmations.associateBy { it.settlementAffirmationId }
    val clearingSubmissions = facts.clearingSubmissions.associateBy { it.settlementClearingSubmissionId }
    val clearingAcceptances = facts.clearingAcceptances.associateBy { it.settlementClearingAcceptanceId }
    val instructions = facts.instructions.associateBy { it.settlementInstructionId }
    val attempts = facts.attempts.associateBy { it.settlementAttemptId }
    val legOutcomesByAttempt = facts.legOutcomes.groupBy { it.settlementAttemptId }
    val ledgerEntriesByAttempt = facts.ledgerEntries.groupBy { it.settlementAttemptId }
    val breaks = facts.breaks.associateBy { it.settlementBreakId }
    val repairs = facts.repairs.associateBy { it.settlementRepairId }
    val profileEvidence = (
        facts.resourcePositions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
        facts.obligations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.allocations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.confirmations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.affirmations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.clearingSubmissions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.clearingAcceptances.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.clearingRejections.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.novations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.instructions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.attempts.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.legOutcomes.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.ledgerEntries.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.settlements.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.breaks.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.repairs.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.resolutions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.operatorActions.map { it.postTradeProfileId to it.postTradePolicyVersion }
        ).toSet()
    require(profileEvidence.size <= 1) { "settlement facts must use one post-trade profile per scenarioRunId" }

    facts.resourcePositions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.resourcePositionId.isNotBlank()) { "resourcePositionId is required" }
        require(it.participantId.isNotBlank()) { "resource position participantId is required" }
        require(it.accountId.isNotBlank()) { "resource position accountId is required" }
        require(it.assetType in setOf(SettlementLedgerEntryTypeCash, SettlementLedgerEntryTypeSecurity)) {
            "resource position assetType must be CASH or SECURITY"
        }
        require(it.assetId.isNotBlank()) { "resource position assetId is required" }
        require(it.quantity.isNotBlank()) { "resource position quantity is required" }
        it.quantity.toSettlementQuantity()
    }

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

    facts.allocations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAllocationId.isNotBlank()) { "settlementAllocationId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "allocation must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "allocation post-trade profile must match obligation"
        }
        require(it.tradeId.isNotBlank()) { "allocation tradeId is required" }
        require(obligation.tradeId == it.tradeId) { "allocation tradeId must match obligation tradeId" }
        require(it.buyOrderId.isNotBlank()) { "allocation buyOrderId is required" }
        require(it.sellOrderId.isNotBlank()) { "allocation sellOrderId is required" }
        require(it.buyerAccountId.isNotBlank()) { "allocation buyerAccountId is required" }
        require(it.sellerAccountId.isNotBlank()) { "allocation sellerAccountId is required" }
        require(it.quantity.isNotBlank()) { "allocation quantity is required" }
        it.quantity.toSettlementQuantity()
        require(it.state == SettlementAllocationProposedState) { "allocation state must be $SettlementAllocationProposedState" }
    }

    facts.confirmations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementConfirmationId.isNotBlank()) { "settlementConfirmationId is required" }
        val allocation = allocations[it.settlementAllocationId]
        require(allocation != null) { "confirmation must reference existing allocation" }
        require(allocation.settlementObligationId == it.settlementObligationId) {
            "confirmation obligation must match allocation obligation"
        }
        require(allocation.tradeId == it.tradeId) { "confirmation tradeId must match allocation tradeId" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, allocation)) {
            "confirmation post-trade profile must match allocation"
        }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "confirmation must reference existing obligation" }
        require(it.state == SettlementConfirmationGeneratedState) {
            "confirmation state must be $SettlementConfirmationGeneratedState"
        }
    }

    facts.affirmations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAffirmationId.isNotBlank()) { "settlementAffirmationId is required" }
        val confirmation = confirmations[it.settlementConfirmationId]
        require(confirmation != null) { "affirmation must reference existing confirmation" }
        require(confirmation.settlementAllocationId == it.settlementAllocationId) {
            "affirmation allocation must match confirmation allocation"
        }
        require(confirmation.settlementObligationId == it.settlementObligationId) {
            "affirmation obligation must match confirmation obligation"
        }
        require(confirmation.tradeId == it.tradeId) { "affirmation tradeId must match confirmation tradeId" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, confirmation)) {
            "affirmation post-trade profile must match confirmation"
        }
        require(it.actorType == SettlementAffirmationActorType) { "affirmation actorType must be $SettlementAffirmationActorType" }
        require(it.actorId.isNotBlank()) { "affirmation actorId is required" }
        require(it.state == SettlementAffirmationAcceptedState) {
            "affirmation state must be $SettlementAffirmationAcceptedState"
        }
    }

    facts.clearingSubmissions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementClearingSubmissionId.isNotBlank()) { "settlementClearingSubmissionId is required" }
        val affirmation = affirmations[it.settlementAffirmationId]
        require(affirmation != null) { "clearing submission must reference existing affirmation" }
        require(affirmation.settlementObligationId == it.settlementObligationId) {
            "clearing submission obligation must match affirmation obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, affirmation)) {
            "clearing submission post-trade profile must match affirmation"
        }
        require(obligations[it.settlementObligationId] != null) { "clearing submission must reference existing obligation" }
        require(it.state == SettlementClearingSubmittedState) {
            "clearing submission state must be $SettlementClearingSubmittedState"
        }
    }

    facts.clearingAcceptances.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementClearingAcceptanceId.isNotBlank()) { "settlementClearingAcceptanceId is required" }
        val submission = clearingSubmissions[it.settlementClearingSubmissionId]
        require(submission != null) { "clearing acceptance must reference existing clearing submission" }
        require(submission.settlementObligationId == it.settlementObligationId) {
            "clearing acceptance obligation must match clearing submission obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, submission)) {
            "clearing acceptance post-trade profile must match clearing submission"
        }
        require(obligations[it.settlementObligationId] != null) { "clearing acceptance must reference existing obligation" }
        require(it.state == SettlementClearingAcceptedState) {
            "clearing acceptance state must be $SettlementClearingAcceptedState"
        }
    }

    facts.clearingRejections.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementClearingRejectionId.isNotBlank()) { "settlementClearingRejectionId is required" }
        val submission = clearingSubmissions[it.settlementClearingSubmissionId]
        require(submission != null) { "clearing rejection must reference existing clearing submission" }
        require(submission.settlementObligationId == it.settlementObligationId) {
            "clearing rejection obligation must match clearing submission obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, submission)) {
            "clearing rejection post-trade profile must match clearing submission"
        }
        require(obligations[it.settlementObligationId] != null) { "clearing rejection must reference existing obligation" }
        require(it.reason == SettlementClearingRejectedReason) {
            "clearing rejection reason must be $SettlementClearingRejectedReason"
        }
        require(it.state == SettlementClearingRejectedState) {
            "clearing rejection state must be $SettlementClearingRejectedState"
        }
    }

    facts.novations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementNovationId.isNotBlank()) { "settlementNovationId is required" }
        val acceptance = clearingAcceptances[it.settlementClearingAcceptanceId]
        require(acceptance != null) { "novation must reference existing clearing acceptance" }
        require(acceptance.settlementObligationId == it.settlementObligationId) {
            "novation obligation must match clearing acceptance obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, acceptance)) {
            "novation post-trade profile must match clearing acceptance"
        }
        require(obligations[it.settlementObligationId] != null) { "novation must reference existing obligation" }
        require(it.state == SettlementNovationRecordedState) { "novation state must be $SettlementNovationRecordedState" }
    }

    facts.attempts.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAttemptId.isNotBlank()) { "settlementAttemptId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "attempt must reference existing obligation" }
        val instruction = instructions[it.settlementInstructionId]
        require(instruction != null) { "attempt must reference existing instruction" }
        require(instruction.settlementObligationId == it.settlementObligationId) { "attempt obligation must match instruction obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, instruction)) {
            "attempt post-trade profile must match instruction"
        }
        require(it.attemptNumber > 0) { "attemptNumber must be positive" }
        require(it.state == SettlementAttemptStartedState) { "attempt state must be $SettlementAttemptStartedState" }
    }

    facts.instructions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementInstructionId.isNotBlank()) { "settlementInstructionId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "instruction must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "instruction post-trade profile must match obligation"
        }
        require(it.instructionType == SettlementInstructionTypeDvp) { "instructionType must be $SettlementInstructionTypeDvp" }
        require(it.state == SettlementInstructionCreatedState) { "instruction state must be $SettlementInstructionCreatedState" }
    }

    facts.legOutcomes.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementLegOutcomeId.isNotBlank()) { "settlementLegOutcomeId is required" }
        val attempt = attempts[it.settlementAttemptId]
        require(attempt != null) { "leg outcome must reference existing attempt" }
        require(attempt.settlementObligationId == it.settlementObligationId) { "leg outcome obligation must match attempt obligation" }
        require(attempt.settlementInstructionId == it.settlementInstructionId) { "leg outcome instruction must match attempt instruction" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, attempt)) {
            "leg outcome post-trade profile must match attempt"
        }
        require(it.legType in setOf(SettlementLegTypeCash, SettlementLegTypeSecurity)) { "legType must be CASH or SECURITY" }
        require(it.state in setOf(SettlementLegSucceededState, SettlementLegFailedState)) {
            "leg outcome state must be $SettlementLegSucceededState or $SettlementLegFailedState"
        }
    }

    facts.ledgerEntries.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.ledgerEntryId.isNotBlank()) { "ledgerEntryId is required" }
        val attempt = attempts[it.settlementAttemptId]
        require(attempt != null) { "ledger entry must reference existing attempt" }
        require(attempt.settlementObligationId == it.settlementObligationId) { "ledger entry obligation must match attempt obligation" }
        require(attempt.settlementInstructionId == it.settlementInstructionId) { "ledger entry instruction must match attempt instruction" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, attempt)) {
            "ledger entry post-trade profile must match attempt"
        }
        require(it.participantId.isNotBlank()) { "ledger entry participantId is required" }
        require(it.accountId.isNotBlank()) { "ledger entry accountId is required" }
        require(it.assetType in setOf(SettlementLedgerEntryTypeCash, SettlementLedgerEntryTypeSecurity)) {
            "ledger entry assetType must be CASH or SECURITY"
        }
        require(it.assetId.isNotBlank()) { "ledger entry assetId is required" }
        require(it.direction in setOf(SettlementLedgerDirectionDebit, SettlementLedgerDirectionCredit)) {
            "ledger entry direction must be DEBIT or CREDIT"
        }
        require(it.quantity.isNotBlank()) { "ledger entry quantity is required" }
        it.quantity.toSettlementQuantity()
    }

    facts.settlements.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementId.isNotBlank()) { "settlementId is required" }
        val attempt = attempts[it.settlementAttemptId]
        require(attempt != null) { "settlement must reference existing attempt" }
        require(attempt.settlementObligationId == it.settlementObligationId) { "settlement obligation must match attempt obligation" }
        require(attempt.settlementInstructionId == it.settlementInstructionId) { "settlement instruction must match attempt instruction" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, attempt)) {
            "settlement post-trade profile must match attempt"
        }
        val legTypes = legOutcomesByAttempt[it.settlementAttemptId].orEmpty()
            .filter { leg -> leg.state == SettlementLegSucceededState }
            .map { leg -> leg.legType }
            .toSet()
        require(legTypes.containsAll(setOf(SettlementLegTypeCash, SettlementLegTypeSecurity))) {
            "settlement requires cash and security leg outcomes"
        }
        val ledgerProof = ledgerEntriesByAttempt[it.settlementAttemptId].orEmpty()
        require(ledgerProof.count { entry -> entry.assetType == SettlementLedgerEntryTypeCash } == 2) {
            "settlement requires two cash ledger entries"
        }
        require(ledgerProof.count { entry -> entry.assetType == SettlementLedgerEntryTypeSecurity } == 2) {
            "settlement requires two security ledger entries"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeCash && entry.direction == SettlementLedgerDirectionDebit }) {
            "settlement requires cash debit ledger entry"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeCash && entry.direction == SettlementLedgerDirectionCredit }) {
            "settlement requires cash credit ledger entry"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeSecurity && entry.direction == SettlementLedgerDirectionDebit }) {
            "settlement requires security debit ledger entry"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeSecurity && entry.direction == SettlementLedgerDirectionCredit }) {
            "settlement requires security credit ledger entry"
        }
        val cashDebit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeCash, SettlementLedgerDirectionDebit)
        val cashCredit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeCash, SettlementLedgerDirectionCredit)
        require(cashDebit.compareTo(cashCredit) == 0) { "settlement cash ledger entries must balance" }
        val securityDebit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionDebit)
        val securityCredit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionCredit)
        require(securityDebit.compareTo(securityCredit) == 0) { "settlement security ledger entries must balance" }
        require(it.settlementState == SettlementSettledState) { "settlementState must be $SettlementSettledState" }
    }

    facts.breaks.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "break must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "break post-trade profile must match obligation"
        }
        require(it.reason in setOf(SettlementBreakOpenedReason, SettlementBreakOpenedReasonSecurity)) {
            "break reason must be $SettlementBreakOpenedReason or $SettlementBreakOpenedReasonSecurity"
        }
        require(it.state == SettlementBreakOpenedState) { "break state must be $SettlementBreakOpenedState" }
    }

    facts.repairs.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val breakFact = breaks[it.settlementBreakId]
        require(breakFact != null) { "repair must reference existing break" }
        require(breakFact.settlementObligationId == it.settlementObligationId) { "repair obligation must match break obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, breakFact)) {
            "repair post-trade profile must match break"
        }
        require(it.repairAction in setOf(SettlementRepairPostedActionCash, SettlementRepairPostedActionSecurity)) {
            "repair action must be $SettlementRepairPostedActionCash or $SettlementRepairPostedActionSecurity"
        }
        require(
            when (breakFact.reason) {
                SettlementBreakOpenedReason -> it.repairAction == SettlementRepairPostedActionCash
                SettlementBreakOpenedReasonSecurity -> it.repairAction == SettlementRepairPostedActionSecurity
                else -> false
            }
        ) { "repair action must match break reason" }
        require(it.actorType == SettlementRepairPostedActorType) { "repair actorType must be $SettlementRepairPostedActorType" }
        require(it.actorId.isNotBlank()) { "actorId is required" }
    }

    facts.resolutions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val repair = repairs[it.settlementRepairId]
        require(repair != null) { "resolution must reference existing repair" }
        require(repair.settlementBreakId == it.settlementBreakId) { "resolution break must match repair break" }
        require(repair.settlementObligationId == it.settlementObligationId) { "resolution obligation must match repair obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, repair)) {
            "resolution post-trade profile must match repair"
        }
        require(it.settlementState == SettlementResolvedState) { "settlementState must be $SettlementResolvedState" }
        require(it.exceptionState == SettlementResolvedState) { "exceptionState must be $SettlementResolvedState" }
    }

    facts.operatorActions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementOperatorActionId.isNotBlank()) { "settlementOperatorActionId is required" }
        require(it.action in setOf(SettlementOperatorActionForceSettle, SettlementOperatorActionReverseLedgerEntry)) {
            "operator action must be $SettlementOperatorActionForceSettle or $SettlementOperatorActionReverseLedgerEntry"
        }
        require(it.targetId.isNotBlank()) { "operator action targetId is required" }
        require(it.reasonNote.isNotBlank()) { "operator action reasonNote is required" }
        require(it.actorType == SettlementRepairPostedActorType) { "operator action actorType must be $SettlementRepairPostedActorType" }
        require(it.actorId.isNotBlank()) { "operator action actorId is required" }
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
    parent: SettlementAllocationProposedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementConfirmationGeneratedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementAffirmationAcceptedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementClearingSubmittedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementClearingAcceptedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementInstructionCreatedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementAttemptStartedFact?
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

private fun ResultSet.toResourcePosition(): SettlementResourcePositionFact {
    return SettlementResourcePositionFact(
        resourcePositionId = getString(1),
        scenarioRunId = getString(2),
        postTradeProfileId = getString(3),
        postTradePolicyVersion = getInt(4),
        correlationId = getString(5),
        causationId = getString(6),
        participantId = getString(7),
        accountId = getString(8),
        assetType = getString(9),
        assetId = getString(10),
        quantity = getString(11),
        occurredAt = getTimestamp(12).toInstant()
    )
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

private fun ResultSet.toAllocation(): SettlementAllocationProposedFact {
    return SettlementAllocationProposedFact(
        settlementAllocationId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(4),
        postTradePolicyVersion = getInt(5),
        correlationId = getString(6),
        causationId = getString(7),
        tradeId = getString(8),
        buyOrderId = getString(9),
        sellOrderId = getString(10),
        buyerAccountId = getString(11),
        sellerAccountId = getString(12),
        quantity = getString(13),
        state = getString(14),
        occurredAt = getTimestamp(15).toInstant()
    )
}

private fun ResultSet.toConfirmation(): SettlementConfirmationGeneratedFact {
    return SettlementConfirmationGeneratedFact(
        settlementConfirmationId = getString(1),
        settlementAllocationId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        tradeId = getString(9),
        state = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

private fun ResultSet.toAffirmation(): SettlementAffirmationAcceptedFact {
    return SettlementAffirmationAcceptedFact(
        settlementAffirmationId = getString(1),
        settlementConfirmationId = getString(2),
        settlementAllocationId = getString(3),
        settlementObligationId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        tradeId = getString(10),
        actorType = getString(11),
        actorId = getString(12),
        state = getString(13),
        occurredAt = getTimestamp(14).toInstant()
    )
}

private fun ResultSet.toClearingSubmission(): SettlementClearingSubmittedFact {
    return SettlementClearingSubmittedFact(
        settlementClearingSubmissionId = getString(1),
        settlementObligationId = getString(2),
        settlementAffirmationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

private fun ResultSet.toClearingAcceptance(): SettlementClearingAcceptedFact {
    return SettlementClearingAcceptedFact(
        settlementClearingAcceptanceId = getString(1),
        settlementClearingSubmissionId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

private fun ResultSet.toClearingRejection(): SettlementClearingRejectedFact {
    return SettlementClearingRejectedFact(
        settlementClearingRejectionId = getString(1),
        settlementClearingSubmissionId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        reason = getString(9),
        state = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

private fun ResultSet.toNovation(): SettlementNovationRecordedFact {
    return SettlementNovationRecordedFact(
        settlementNovationId = getString(1),
        settlementClearingAcceptanceId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

private fun ResultSet.toAttempt(): SettlementAttemptStartedFact {
    return SettlementAttemptStartedFact(
        settlementAttemptId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        settlementInstructionId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        attemptNumber = getInt(9),
        state = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

private fun ResultSet.toInstruction(): SettlementInstructionCreatedFact {
    return SettlementInstructionCreatedFact(
        settlementInstructionId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(4),
        postTradePolicyVersion = getInt(5),
        correlationId = getString(6),
        causationId = getString(7),
        instructionType = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

private fun ResultSet.toLegOutcome(): SettlementLegOutcomeFact {
    return SettlementLegOutcomeFact(
        settlementLegOutcomeId = getString(1),
        settlementObligationId = getString(2),
        settlementInstructionId = getString(3),
        settlementAttemptId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        legType = getString(10),
        state = getString(11),
        occurredAt = getTimestamp(12).toInstant()
    )
}

private fun ResultSet.toLedgerEntry(): SettlementLedgerEntryFact {
    return SettlementLedgerEntryFact(
        ledgerEntryId = getString(1),
        settlementObligationId = getString(2),
        settlementInstructionId = getString(3),
        settlementAttemptId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        participantId = getString(10),
        accountId = getString(11),
        assetType = getString(12),
        assetId = getString(13),
        direction = getString(14),
        quantity = getString(15),
        occurredAt = getTimestamp(16).toInstant()
    )
}

private fun ResultSet.toSettlement(): SettlementSettledFact {
    return SettlementSettledFact(
        settlementId = getString(1),
        settlementObligationId = getString(2),
        settlementInstructionId = getString(3),
        settlementAttemptId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        settlementState = getString(10),
        occurredAt = getTimestamp(11).toInstant()
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

private fun ResultSet.toOperatorAction(): SettlementOperatorActionFact {
    return SettlementOperatorActionFact(
        settlementOperatorActionId = getString(1),
        scenarioRunId = getString(2),
        postTradeProfileId = getString(3),
        postTradePolicyVersion = getInt(4),
        correlationId = getString(5),
        causationId = getString(6),
        action = getString(7),
        targetId = getString(8),
        reasonNote = getString(9),
        actorType = getString(10),
        actorId = getString(11),
        occurredAt = getTimestamp(12).toInstant()
    )
}
