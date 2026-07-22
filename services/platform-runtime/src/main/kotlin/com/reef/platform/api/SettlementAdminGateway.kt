package com.reef.platform.api

import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.SettlementAffirmationAcceptedFact
import com.reef.platform.application.settlement.SettlementAllocationProposedFact
import com.reef.platform.application.settlement.SettlementAttemptStartedFact
import com.reef.platform.application.settlement.SettlementBreakOpenedFact
import com.reef.platform.application.settlement.SettlementBreakOpenedReason
import com.reef.platform.application.settlement.SettlementBreakOpenedReasonSecurity
import com.reef.platform.application.settlement.SettlementClearingAcceptedFact
import com.reef.platform.application.settlement.SettlementClearingRejectedFact
import com.reef.platform.application.settlement.SettlementClearingRejectedReason
import com.reef.platform.application.settlement.SettlementClearingSubmittedFact
import com.reef.platform.application.settlement.SettlementConfirmationGeneratedFact
import com.reef.platform.application.settlement.SettlementExceptionProjection
import com.reef.platform.application.settlement.SettlementExceptionQueueView
import com.reef.platform.application.settlement.SettlementFactBundle
import com.reef.platform.application.settlement.SettlementFactStore
import com.reef.platform.application.settlement.SettlementInstructionCreatedFact
import com.reef.platform.application.settlement.SettlementLedgerDirectionCredit
import com.reef.platform.application.settlement.SettlementLedgerDirectionDebit
import com.reef.platform.application.settlement.SettlementLedgerEntryFact
import com.reef.platform.application.settlement.SettlementLedgerEntryTypeCash
import com.reef.platform.application.settlement.SettlementLedgerEntryTypeSecurity
import com.reef.platform.application.settlement.SettlementLedgerProjection
import com.reef.platform.application.settlement.SettlementLedgerProjectionView
import com.reef.platform.application.settlement.SettlementLegOutcomeFact
import com.reef.platform.application.settlement.SettlementNovationRecordedFact
import com.reef.platform.application.settlement.SettlementObligationCreatedFact
import com.reef.platform.application.settlement.SettlementObligationProjection
import com.reef.platform.application.settlement.SettlementObligationView
import com.reef.platform.application.settlement.SettlementOperatorActionFact
import com.reef.platform.application.settlement.SettlementOperatorActionForceSettle
import com.reef.platform.application.settlement.SettlementOperatorActionReverseLedgerEntry
import com.reef.platform.application.settlement.SettlementRepairPostedActionCash
import com.reef.platform.application.settlement.SettlementRepairPostedActionSecurity
import com.reef.platform.application.settlement.SettlementRepairPostedFact
import com.reef.platform.application.settlement.SettlementResolvedFact
import com.reef.platform.application.settlement.SettlementResourcePositionFact
import com.reef.platform.application.settlement.SettlementScenarioProofProjection
import com.reef.platform.application.settlement.SettlementScenarioProofView
import com.reef.platform.application.settlement.SettlementScoreProjection
import com.reef.platform.application.settlement.SettlementScoreProjectionOptions
import com.reef.platform.application.settlement.SettlementScoreProjectionView
import com.reef.platform.application.settlement.SettlementSettledFact
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.sun.net.httpserver.HttpExchange
import java.time.Instant

/**
 * Owns settlement fact ingestion, repair/force-settle/ledger-reversal admin actions,
 * and the scenario-run query surface (facts/obligations/ledger/proof/score) that reads
 * projections off those facts. See docs/steering/kotlin.md API-layer guidance.
 */
internal class SettlementAdminGateway(
    private val settlementFactStore: SettlementFactStore?,
    private val settlementObligationMaterializer: TradeSettlementObligationMaterializer?,
    private val postTradeProfileResolver: PostTradeProfileResolver,
    private val scenarioRunPostTradeProfileLookup: (String) -> String?,
    private val venueSessionPostTradeProfileLookup: (String) -> String?,
    private val adminSessionAuth: AdminSessionAuth
) {
    fun appendSettlementFactsResponse(body: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val facts = parseSettlementFactBundle(json)
            store.appendFacts(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "facts" to settlementFactBundleJson(facts))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement facts")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "settlement fact append failed")))
        }
    }

    fun postCashSettlementRepairResponse(body: String): PlatformHotPathResponse {
        return postSettlementRepairResponse(
            body = body,
            repairKind = "cash",
            expectedBreakReason = SettlementBreakOpenedReason,
            repairAction = SettlementRepairPostedActionCash,
            assetType = SettlementLedgerEntryTypeCash,
            defaultParticipantId = { it.buyerParticipantId },
            defaultAssetId = { it.currency },
            defaultQuantity = { it.cashAmount }
        )
    }

    fun postSecuritySettlementRepairResponse(body: String): PlatformHotPathResponse {
        return postSettlementRepairResponse(
            body = body,
            repairKind = "security",
            expectedBreakReason = SettlementBreakOpenedReasonSecurity,
            repairAction = SettlementRepairPostedActionSecurity,
            assetType = SettlementLedgerEntryTypeSecurity,
            defaultParticipantId = { it.sellerParticipantId },
            defaultAssetId = { it.instrumentId },
            defaultQuantity = { it.quantity }
        )
    }

    private fun postSettlementRepairResponse(
        body: String,
        repairKind: String,
        expectedBreakReason: String,
        repairAction: String,
        assetType: String,
        defaultParticipantId: (SettlementObligationCreatedFact) -> String,
        defaultAssetId: (SettlementObligationCreatedFact) -> String,
        defaultQuantity: (SettlementObligationCreatedFact) -> String
    ): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") }
            require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
            val settlementBreakId = json.string("settlementBreakId")
            require(settlementBreakId.isNotBlank()) { "settlementBreakId is required" }
            val accountId = json.string("accountId")
            require(accountId.isNotBlank()) { "accountId is required" }
            val existing = store.factsByScenarioRunId(scenarioRunId)
            val breakFact = existing.breaks.firstOrNull { it.settlementBreakId == settlementBreakId }
                ?: throw IllegalArgumentException("settlementBreakId not found")
            require(breakFact.reason == expectedBreakReason) { "settlementBreakId is not a $repairKind break" }
            val obligation = existing.obligations.firstOrNull {
                it.settlementObligationId == breakFact.settlementObligationId
            } ?: throw IllegalArgumentException("settlement obligation not found")
            val principal = adminSessionAuth.currentPrincipal()
            val occurredAtRaw = json.string("occurredAt")
            require(occurredAtRaw.isNotBlank()) { "occurredAt is required" }
            val occurredAt = instantFrom(occurredAtRaw, "occurredAt")
            val actorId = json.string("actorId").ifBlank { principal.actorId }
            require(actorId.isNotBlank()) { "actorId is required" }
            val participantId = json.string("participantId").ifBlank { defaultParticipantId(obligation) }
            val assetId = json.string("assetId").ifBlank { defaultAssetId(obligation) }
            val quantity = json.string("quantity").ifBlank { defaultQuantity(obligation) }
            require(quantity.isNotBlank()) { "quantity is required" }
            val repairId = json.string("settlementRepairId").ifBlank { "repair-$settlementBreakId-$repairKind" }
            val resourcePositionId = json.string("resourcePositionId").ifBlank {
                "resource-$settlementBreakId-$repairKind-repair"
            }
            val correlationId = json.string("correlationId").ifBlank { principal.correlationId }
            val causationId = json.string("causationId").ifBlank { settlementBreakId }
            val facts = SettlementFactBundle(
                scenarioRunId = scenarioRunId,
                resourcePositions = listOf(
                    SettlementResourcePositionFact(
                        resourcePositionId = resourcePositionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = causationId,
                        participantId = participantId,
                        accountId = accountId,
                        assetType = assetType,
                        assetId = assetId,
                        quantity = quantity,
                        occurredAt = occurredAt
                    )
                ),
                repairs = listOf(
                    SettlementRepairPostedFact(
                        settlementRepairId = repairId,
                        settlementBreakId = settlementBreakId,
                        settlementObligationId = obligation.settlementObligationId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = causationId,
                        repairAction = repairAction,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                )
            )
            store.appendFacts(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "facts" to settlementFactBundleJson(facts))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement repair")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "settlement repair failed")))
        }
    }

    fun forceSettleResponse(body: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        val materializer = settlementObligationMaterializer
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement materializer unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") }
            require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
            val settlementBreakId = json.string("settlementBreakId")
            require(settlementBreakId.isNotBlank()) { "settlementBreakId is required" }
            val accountId = json.string("accountId")
            require(accountId.isNotBlank()) { "accountId is required" }
            val reasonNote = json.string("reasonNote")
            require(reasonNote.isNotBlank()) { "reasonNote is required" }
            val occurredAtRaw = json.string("occurredAt")
            require(occurredAtRaw.isNotBlank()) { "occurredAt is required" }
            val occurredAt = instantFrom(occurredAtRaw, "occurredAt")
            val existing = store.factsByScenarioRunId(scenarioRunId)
            val breakFact = existing.breaks.firstOrNull { it.settlementBreakId == settlementBreakId }
                ?: throw IllegalArgumentException("settlementBreakId not found")
            val obligation = existing.obligations.firstOrNull { it.settlementObligationId == breakFact.settlementObligationId }
                ?: throw IllegalArgumentException("settlement obligation not found")
            val principal = adminSessionAuth.currentPrincipal()
            val actorId = json.string("actorId").ifBlank { principal.actorId }
            require(actorId.isNotBlank()) { "actorId is required" }
            val isCashBreak = breakFact.reason == SettlementBreakOpenedReason
            val repairKind = if (isCashBreak) "cash" else "security"
            val resourceAssetType = if (isCashBreak) SettlementLedgerEntryTypeCash else SettlementLedgerEntryTypeSecurity
            val resourceParticipantId = json.string("participantId").ifBlank {
                if (isCashBreak) obligation.buyerParticipantId else obligation.sellerParticipantId
            }
            val resourceAssetId = json.string("assetId").ifBlank {
                if (isCashBreak) obligation.currency else obligation.instrumentId
            }
            val resourceQuantity = json.string("quantity").ifBlank {
                if (isCashBreak) obligation.cashAmount else obligation.quantity
            }
            val correlationId = json.string("correlationId").ifBlank { principal.correlationId }
            val operatorActionId = json.string("settlementOperatorActionId").ifBlank {
                "operator-force-settle-$settlementBreakId"
            }
            val repairId = json.string("settlementRepairId").ifBlank { "repair-$settlementBreakId-force-settle" }
            val resourcePositionId = json.string("resourcePositionId").ifBlank {
                "resource-$settlementBreakId-force-settle"
            }
            val auditFacts = SettlementFactBundle(
                scenarioRunId = scenarioRunId,
                operatorActions = listOf(
                    SettlementOperatorActionFact(
                        settlementOperatorActionId = operatorActionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = settlementBreakId,
                        action = SettlementOperatorActionForceSettle,
                        targetId = settlementBreakId,
                        reasonNote = reasonNote,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                ),
                resourcePositions = listOf(
                    SettlementResourcePositionFact(
                        resourcePositionId = resourcePositionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        participantId = resourceParticipantId,
                        accountId = accountId,
                        assetType = resourceAssetType,
                        assetId = resourceAssetId,
                        quantity = resourceQuantity,
                        occurredAt = occurredAt
                    )
                ),
                repairs = listOf(
                    SettlementRepairPostedFact(
                        settlementRepairId = repairId,
                        settlementBreakId = settlementBreakId,
                        settlementObligationId = obligation.settlementObligationId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        repairAction = if (isCashBreak) SettlementRepairPostedActionCash else SettlementRepairPostedActionSecurity,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                )
            )
            store.appendFacts(auditFacts)
            val result = materializer.materialize(scenarioRunId = scenarioRunId, venueSessionId = json.string("venueSessionId"))
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "repairKind" to repairKind,
                    "facts" to settlementFactBundleJson(auditFacts),
                    "materialization" to mapOf(
                        "scenarioRunId" to result.scenarioRunId,
                        "materializedAllocations" to result.materializedAllocations,
                        "materializedConfirmations" to result.materializedConfirmations,
                        "materializedAffirmations" to result.materializedAffirmations,
                        "materializedClearingSubmissions" to result.materializedClearingSubmissions,
                        "materializedClearingAcceptances" to result.materializedClearingAcceptances,
                        "materializedClearingRejections" to result.materializedClearingRejections,
                        "materializedNovations" to result.materializedNovations,
                        "materializedAttempts" to result.materializedAttempts,
                        "materializedLedgerEntries" to result.materializedLedgerEntries,
                        "materializedSettlements" to result.materializedSettlements,
                        "materializedResolutions" to result.materializedResolutions
                    )
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid force settle")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "force settle failed")))
        }
    }

    fun reverseSettlementLedgerEntryResponse(body: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") }
            require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
            val targetLedgerEntryId = json.string("ledgerEntryId").ifBlank { json.string("targetLedgerEntryId") }
            require(targetLedgerEntryId.isNotBlank()) { "ledgerEntryId is required" }
            val reasonNote = json.string("reasonNote")
            require(reasonNote.isNotBlank()) { "reasonNote is required" }
            val occurredAtRaw = json.string("occurredAt")
            require(occurredAtRaw.isNotBlank()) { "occurredAt is required" }
            val occurredAt = instantFrom(occurredAtRaw, "occurredAt")
            val existing = store.factsByScenarioRunId(scenarioRunId)
            val target = existing.ledgerEntries.firstOrNull { it.ledgerEntryId == targetLedgerEntryId }
                ?: throw IllegalArgumentException("ledgerEntryId not found")
            val obligation = existing.obligations.firstOrNull { it.settlementObligationId == target.settlementObligationId }
                ?: throw IllegalArgumentException("settlement obligation not found")
            val nextAttemptNumber = existing.attempts
                .filter { it.settlementObligationId == target.settlementObligationId }
                .maxOfOrNull { it.attemptNumber }
                ?.plus(1)
                ?: 1
            val principal = adminSessionAuth.currentPrincipal()
            val actorId = json.string("actorId").ifBlank { principal.actorId }
            require(actorId.isNotBlank()) { "actorId is required" }
            val correlationId = json.string("correlationId").ifBlank { principal.correlationId }
            val operatorActionId = json.string("settlementOperatorActionId").ifBlank {
                "operator-reverse-ledger-$targetLedgerEntryId"
            }
            val instructionId = json.string("settlementInstructionId").ifBlank {
                "settlement-instruction-reversal-$targetLedgerEntryId"
            }
            val attemptId = json.string("settlementAttemptId").ifBlank {
                "settlement-attempt-reversal-$targetLedgerEntryId"
            }
            val reversalLedgerEntryId = json.string("reversalLedgerEntryId").ifBlank {
                "settlement-ledger-reversal-$targetLedgerEntryId"
            }
            val facts = SettlementFactBundle(
                scenarioRunId = scenarioRunId,
                operatorActions = listOf(
                    SettlementOperatorActionFact(
                        settlementOperatorActionId = operatorActionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = targetLedgerEntryId,
                        action = SettlementOperatorActionReverseLedgerEntry,
                        targetId = targetLedgerEntryId,
                        reasonNote = reasonNote,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                ),
                instructions = listOf(
                    SettlementInstructionCreatedFact(
                        settlementInstructionId = instructionId,
                        settlementObligationId = target.settlementObligationId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        occurredAt = occurredAt
                    )
                ),
                attempts = listOf(
                    SettlementAttemptStartedFact(
                        settlementAttemptId = attemptId,
                        settlementObligationId = target.settlementObligationId,
                        settlementInstructionId = instructionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = instructionId,
                        attemptNumber = nextAttemptNumber,
                        occurredAt = occurredAt
                    )
                ),
                ledgerEntries = listOf(
                    SettlementLedgerEntryFact(
                        ledgerEntryId = reversalLedgerEntryId,
                        settlementObligationId = target.settlementObligationId,
                        settlementInstructionId = instructionId,
                        settlementAttemptId = attemptId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        participantId = target.participantId,
                        accountId = target.accountId,
                        assetType = target.assetType,
                        assetId = target.assetId,
                        direction = oppositeLedgerDirection(target.direction),
                        quantity = target.quantity,
                        occurredAt = occurredAt
                    )
                )
            )
            require(obligation.postTradeProfileId == target.postTradeProfileId) { "ledger entry profile must match obligation" }
            store.appendFacts(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "facts" to settlementFactBundleJson(facts))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid ledger reversal")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "ledger reversal failed")))
        }
    }

    private fun oppositeLedgerDirection(direction: String): String {
        return when (direction) {
            SettlementLedgerDirectionDebit -> SettlementLedgerDirectionCredit
            SettlementLedgerDirectionCredit -> SettlementLedgerDirectionDebit
            else -> throw IllegalArgumentException("ledger entry direction must be DEBIT or CREDIT")
        }
    }

    fun materializeSettlementObligationsResponse(body: String): PlatformHotPathResponse {
        val materializer = settlementObligationMaterializer
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement materializer unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val result = materializer.materialize(
                scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") },
                venueSessionId = json.string("venueSessionId")
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "scenarioRunId" to result.scenarioRunId,
                    "scannedTrades" to result.scannedTrades,
                    "materializedObligations" to result.materializedObligations,
                    "materializedAllocations" to result.materializedAllocations,
                    "materializedConfirmations" to result.materializedConfirmations,
                    "materializedAffirmations" to result.materializedAffirmations,
                    "materializedClearingSubmissions" to result.materializedClearingSubmissions,
                    "materializedClearingAcceptances" to result.materializedClearingAcceptances,
                    "materializedClearingRejections" to result.materializedClearingRejections,
                    "materializedNovations" to result.materializedNovations,
                    "materializedInstructions" to result.materializedInstructions,
                    "materializedAttempts" to result.materializedAttempts,
                    "materializedLegOutcomes" to result.materializedLegOutcomes,
                    "materializedLedgerEntries" to result.materializedLedgerEntries,
                    "materializedSettlements" to result.materializedSettlements,
                    "materializedBreaks" to result.materializedBreaks,
                    "materializedResolutions" to result.materializedResolutions,
                    "skippedTrades" to result.skippedTrades
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement materialization request")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "settlement materialization failed")))
        }
    }

    fun settlementFactsResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            PlatformHotPathResponse(200, settlementFactBundleBody(store.factsByScenarioRunId(scenarioRunId)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement fact query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement fact query failed")))
        }
    }

    fun settlementObligationsResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val facts = store.factsByScenarioRunId(scenarioRunId)
            val obligations = SettlementObligationProjection.project(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "scenarioRunId" to scenarioRunId,
                    "obligationsCount" to obligations.size,
                    "obligations" to obligations.map { settlementObligationViewJson(it) }
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement obligation query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement obligation query failed")))
        }
    }

    fun settlementLedgerResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementLedgerProjection.project(store.factsByScenarioRunId(scenarioRunId))
            PlatformHotPathResponse(200, settlementLedgerProjectionBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement ledger query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement ledger query failed")))
        }
    }

    fun settlementExceptionsResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementExceptionProjection.project(store.factsByScenarioRunId(scenarioRunId))
            PlatformHotPathResponse(200, settlementExceptionQueueBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement exception query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement exception query failed")))
        }
    }

    fun settlementProofResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementScenarioProofProjection.project(store.factsByScenarioRunId(scenarioRunId))
            PlatformHotPathResponse(200, settlementScenarioProofBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement proof query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement proof query failed")))
        }
    }

    fun settlementScoreResponse(exchange: HttpExchange, scenarioRunId: String): PlatformHotPathResponse =
        settlementScoreResponse(
            scenarioRunId = scenarioRunId,
            asOfRaw = exchange.queryValue("asOf"),
            agedFailAfterSecondsRaw = exchange.queryValue("agedFailAfterSeconds")
        )

    fun settlementScoreResponse(scenarioRunId: String, query: String?): PlatformHotPathResponse =
        settlementScoreResponse(
            scenarioRunId = scenarioRunId,
            asOfRaw = queryValue(query, "asOf"),
            agedFailAfterSecondsRaw = queryValue(query, "agedFailAfterSeconds")
        )

    private fun settlementScoreResponse(
        scenarioRunId: String,
        asOfRaw: String,
        agedFailAfterSecondsRaw: String
    ): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementScoreProjection.project(
                facts = store.factsByScenarioRunId(scenarioRunId),
                options = SettlementScoreProjectionOptions(
                    asOf = asOfRaw.takeIf { it.isNotBlank() }?.let { instantFrom(it, "asOf") },
                    agedFailAfterSeconds = agedFailAfterSecondsRaw
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            val parsed = it.toLongOrNull()
                            require(parsed != null && parsed >= 0L) { "agedFailAfterSeconds must be a non-negative integer" }
                            parsed
                        }
                        ?: com.reef.platform.application.settlement.SettlementScoreDefaultAgedFailAfterSeconds
                )
            )
            PlatformHotPathResponse(200, settlementScoreProjectionBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement score query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement score query failed")))
        }
    }

    private fun parseSettlementFactBundle(json: JsonDocument): SettlementFactBundle {
        val scenarioRunId = json.string("scenarioRunId")
        val venueSessionId = json.string("venueSessionId")
        val scenarioRunProfileId = json.string("postTradeProfileId").ifBlank {
            scenarioRunId.takeIf { it.isNotBlank() }
                ?.let { scenarioRunPostTradeProfileLookup(it) }
                .orEmpty()
        }
        val selection = postTradeProfileResolver.resolve(
            scenarioRunProfileId = scenarioRunProfileId,
            venueSessionProfileId = venueSessionId.takeIf { it.isNotBlank() }
                ?.let { venueSessionPostTradeProfileLookup(it) }
                .orEmpty()
        )
        val postTradeProfileId = selection.profileId
        val postTradePolicyVersion = positiveIntOrDefault(
            json = json,
            key = "postTradePolicyVersion",
            defaultValue = selection.policyVersion
        )
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            resourcePositions = json.objectDocuments("resourcePositions").map {
                resourcePositionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            obligations = json.objectDocuments("obligations").map {
                obligationFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            allocations = json.objectDocuments("allocations").map {
                allocationFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            confirmations = json.objectDocuments("confirmations").map {
                confirmationFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            affirmations = json.objectDocuments("affirmations").map {
                affirmationFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            clearingSubmissions = json.objectDocuments("clearingSubmissions").map {
                clearingSubmissionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            clearingAcceptances = json.objectDocuments("clearingAcceptances").map {
                clearingAcceptanceFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            clearingRejections = json.objectDocuments("clearingRejections").map {
                clearingRejectionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            novations = json.objectDocuments("novations").map {
                novationFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            instructions = json.objectDocuments("instructions").map {
                instructionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            attempts = json.objectDocuments("attempts").map {
                attemptFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            legOutcomes = json.objectDocuments("legOutcomes").map {
                legOutcomeFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            ledgerEntries = json.objectDocuments("ledgerEntries").map {
                ledgerEntryFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            settlements = json.objectDocuments("settlements").map {
                settlementFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            breaks = json.objectDocuments("breaks").map {
                breakFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            repairs = json.objectDocuments("repairs").map {
                repairFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            resolutions = json.objectDocuments("resolutions").map {
                resolutionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            operatorActions = json.objectDocuments("operatorActions").map {
                operatorActionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            }
        )
    }

    private fun resourcePositionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementResourcePositionFact {
        return SettlementResourcePositionFact(
            resourcePositionId = json.string("resourcePositionId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            participantId = json.string("participantId"),
            accountId = json.string("accountId"),
            assetType = json.string("assetType"),
            assetId = json.string("assetId"),
            quantity = json.string("quantity"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun obligationFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementObligationCreatedFact {
        return SettlementObligationCreatedFact(
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            tradeId = json.string("tradeId"),
            buyerParticipantId = json.string("buyerParticipantId"),
            sellerParticipantId = json.string("sellerParticipantId"),
            instrumentId = json.string("instrumentId"),
            quantity = json.string("quantity"),
            cashAmount = json.string("cashAmount"),
            currency = json.string("currency"),
            state = json.string("state").ifBlank { "OBLIGATION_CREATED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun allocationFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementAllocationProposedFact {
        return SettlementAllocationProposedFact(
            settlementAllocationId = json.string("settlementAllocationId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            tradeId = json.string("tradeId"),
            buyOrderId = json.string("buyOrderId"),
            sellOrderId = json.string("sellOrderId"),
            buyerAccountId = json.string("buyerAccountId"),
            sellerAccountId = json.string("sellerAccountId"),
            quantity = json.string("quantity"),
            state = json.string("state").ifBlank { "ALLOCATION_PROPOSED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun confirmationFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementConfirmationGeneratedFact {
        return SettlementConfirmationGeneratedFact(
            settlementConfirmationId = json.string("settlementConfirmationId"),
            settlementAllocationId = json.string("settlementAllocationId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            tradeId = json.string("tradeId"),
            state = json.string("state").ifBlank { "CONFIRMATION_GENERATED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun affirmationFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementAffirmationAcceptedFact {
        return SettlementAffirmationAcceptedFact(
            settlementAffirmationId = json.string("settlementAffirmationId"),
            settlementConfirmationId = json.string("settlementConfirmationId"),
            settlementAllocationId = json.string("settlementAllocationId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            tradeId = json.string("tradeId"),
            actorType = json.string("actorType").ifBlank { "SYSTEM" },
            actorId = json.string("actorId").ifBlank { "post-trade-auto-affirmer" },
            state = json.string("state").ifBlank { "AFFIRMATION_ACCEPTED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun clearingSubmissionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementClearingSubmittedFact {
        return SettlementClearingSubmittedFact(
            settlementClearingSubmissionId = json.string("settlementClearingSubmissionId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementAffirmationId = json.string("settlementAffirmationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            state = json.string("state").ifBlank { "CLEARING_SUBMITTED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun clearingAcceptanceFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementClearingAcceptedFact {
        return SettlementClearingAcceptedFact(
            settlementClearingAcceptanceId = json.string("settlementClearingAcceptanceId"),
            settlementClearingSubmissionId = json.string("settlementClearingSubmissionId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            state = json.string("state").ifBlank { "CLEARING_ACCEPTED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun clearingRejectionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementClearingRejectedFact {
        return SettlementClearingRejectedFact(
            settlementClearingRejectionId = json.string("settlementClearingRejectionId"),
            settlementClearingSubmissionId = json.string("settlementClearingSubmissionId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            reason = json.string("reason").ifBlank { SettlementClearingRejectedReason },
            state = json.string("state").ifBlank { "CLEARING_REJECTED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun novationFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementNovationRecordedFact {
        return SettlementNovationRecordedFact(
            settlementNovationId = json.string("settlementNovationId"),
            settlementClearingAcceptanceId = json.string("settlementClearingAcceptanceId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            state = json.string("state").ifBlank { "NOVATION_RECORDED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun attemptFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementAttemptStartedFact {
        return SettlementAttemptStartedFact(
            settlementAttemptId = json.string("settlementAttemptId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            attemptNumber = positiveIntOrDefault(json, "attemptNumber", 1),
            state = json.string("state").ifBlank { "ATTEMPT_STARTED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun instructionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementInstructionCreatedFact {
        return SettlementInstructionCreatedFact(
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            instructionType = json.string("instructionType").ifBlank { "DVP" },
            state = json.string("state").ifBlank { "INSTRUCTION_CREATED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun legOutcomeFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementLegOutcomeFact {
        return SettlementLegOutcomeFact(
            settlementLegOutcomeId = json.string("settlementLegOutcomeId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementAttemptId = json.string("settlementAttemptId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            legType = json.string("legType"),
            state = json.string("state").ifBlank { "LEG_SUCCEEDED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun ledgerEntryFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementLedgerEntryFact {
        return SettlementLedgerEntryFact(
            ledgerEntryId = json.string("ledgerEntryId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementAttemptId = json.string("settlementAttemptId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            participantId = json.string("participantId"),
            accountId = json.string("accountId"),
            assetType = json.string("assetType"),
            assetId = json.string("assetId"),
            direction = json.string("direction"),
            quantity = json.string("quantity"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun settlementFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementSettledFact {
        return SettlementSettledFact(
            settlementId = json.string("settlementId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementAttemptId = json.string("settlementAttemptId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            settlementState = json.string("settlementState").ifBlank { "SETTLED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun breakFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementBreakOpenedFact {
        return SettlementBreakOpenedFact(
            settlementBreakId = json.string("settlementBreakId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            reason = json.string("reason").ifBlank { "CASH_LEG_FAILED" },
            state = json.string("state").ifBlank { "BROKEN" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun repairFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementRepairPostedFact {
        return SettlementRepairPostedFact(
            settlementRepairId = json.string("settlementRepairId"),
            settlementBreakId = json.string("settlementBreakId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            repairAction = json.string("repairAction").ifBlank { SettlementRepairPostedActionCash },
            actorType = json.string("actorType").ifBlank { "USER" },
            actorId = json.string("actorId"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun resolutionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementResolvedFact {
        return SettlementResolvedFact(
            settlementResolutionId = json.string("settlementResolutionId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementBreakId = json.string("settlementBreakId"),
            settlementRepairId = json.string("settlementRepairId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            settlementState = json.string("settlementState").ifBlank { "RESOLVED" },
            exceptionState = json.string("exceptionState").ifBlank { "RESOLVED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun operatorActionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementOperatorActionFact {
        return SettlementOperatorActionFact(
            settlementOperatorActionId = json.string("settlementOperatorActionId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            action = json.string("action"),
            targetId = json.string("targetId"),
            reasonNote = json.string("reasonNote"),
            actorType = json.string("actorType").ifBlank { "USER" },
            actorId = json.string("actorId"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun positiveIntOrDefault(json: JsonDocument, key: String, defaultValue: Int): Int {
        val raw = json.string(key)
        if (raw.isBlank()) return defaultValue
        val parsed = raw.toIntOrNull()
        require(parsed != null && parsed > 0) { "$key must be a positive integer" }
        return parsed
    }

    private fun requiredInstant(json: JsonDocument, key: String): Instant {
        return instantFrom(json.string(key), key)
    }

    private fun instantFrom(value: String, key: String): Instant {
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("$key must be RFC3339")
        }
    }

    private fun settlementFactBundleJson(facts: SettlementFactBundle): Map<String, Any?> {
        return mapOf(
            "scenarioRunId" to facts.scenarioRunId,
            "resourcePositions" to facts.resourcePositions.map {
                mapOf(
                    "resourcePositionId" to it.resourcePositionId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "participantId" to it.participantId,
                    "accountId" to it.accountId,
                    "assetType" to it.assetType,
                    "assetId" to it.assetId,
                    "quantity" to it.quantity,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "obligations" to facts.obligations.map {
                mapOf(
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "tradeId" to it.tradeId,
                    "buyerParticipantId" to it.buyerParticipantId,
                    "sellerParticipantId" to it.sellerParticipantId,
                    "instrumentId" to it.instrumentId,
                    "quantity" to it.quantity,
                    "cashAmount" to it.cashAmount,
                    "currency" to it.currency,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "allocations" to facts.allocations.map {
                mapOf(
                    "settlementAllocationId" to it.settlementAllocationId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "tradeId" to it.tradeId,
                    "buyOrderId" to it.buyOrderId,
                    "sellOrderId" to it.sellOrderId,
                    "buyerAccountId" to it.buyerAccountId,
                    "sellerAccountId" to it.sellerAccountId,
                    "quantity" to it.quantity,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "confirmations" to facts.confirmations.map {
                mapOf(
                    "settlementConfirmationId" to it.settlementConfirmationId,
                    "settlementAllocationId" to it.settlementAllocationId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "tradeId" to it.tradeId,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "affirmations" to facts.affirmations.map {
                mapOf(
                    "settlementAffirmationId" to it.settlementAffirmationId,
                    "settlementConfirmationId" to it.settlementConfirmationId,
                    "settlementAllocationId" to it.settlementAllocationId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "tradeId" to it.tradeId,
                    "actorType" to it.actorType,
                    "actorId" to it.actorId,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "clearingSubmissions" to facts.clearingSubmissions.map {
                mapOf(
                    "settlementClearingSubmissionId" to it.settlementClearingSubmissionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementAffirmationId" to it.settlementAffirmationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "clearingAcceptances" to facts.clearingAcceptances.map {
                mapOf(
                    "settlementClearingAcceptanceId" to it.settlementClearingAcceptanceId,
                    "settlementClearingSubmissionId" to it.settlementClearingSubmissionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "clearingRejections" to facts.clearingRejections.map {
                mapOf(
                    "settlementClearingRejectionId" to it.settlementClearingRejectionId,
                    "settlementClearingSubmissionId" to it.settlementClearingSubmissionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "reason" to it.reason,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "novations" to facts.novations.map {
                mapOf(
                    "settlementNovationId" to it.settlementNovationId,
                    "settlementClearingAcceptanceId" to it.settlementClearingAcceptanceId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "instructions" to facts.instructions.map {
                mapOf(
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "instructionType" to it.instructionType,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "attempts" to facts.attempts.map {
                mapOf(
                    "settlementAttemptId" to it.settlementAttemptId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "attemptNumber" to it.attemptNumber,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "legOutcomes" to facts.legOutcomes.map {
                mapOf(
                    "settlementLegOutcomeId" to it.settlementLegOutcomeId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementAttemptId" to it.settlementAttemptId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "legType" to it.legType,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "ledgerEntries" to facts.ledgerEntries.map {
                mapOf(
                    "ledgerEntryId" to it.ledgerEntryId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementAttemptId" to it.settlementAttemptId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "participantId" to it.participantId,
                    "accountId" to it.accountId,
                    "assetType" to it.assetType,
                    "assetId" to it.assetId,
                    "direction" to it.direction,
                    "quantity" to it.quantity,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "settlements" to facts.settlements.map {
                mapOf(
                    "settlementId" to it.settlementId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementAttemptId" to it.settlementAttemptId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "settlementState" to it.settlementState,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "breaks" to facts.breaks.map {
                mapOf(
                    "settlementBreakId" to it.settlementBreakId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "reason" to it.reason,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "repairs" to facts.repairs.map {
                mapOf(
                    "settlementRepairId" to it.settlementRepairId,
                    "settlementBreakId" to it.settlementBreakId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "repairAction" to it.repairAction,
                    "actorType" to it.actorType,
                    "actorId" to it.actorId,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "resolutions" to facts.resolutions.map {
                mapOf(
                    "settlementResolutionId" to it.settlementResolutionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementBreakId" to it.settlementBreakId,
                    "settlementRepairId" to it.settlementRepairId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "settlementState" to it.settlementState,
                    "exceptionState" to it.exceptionState,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "operatorActions" to facts.operatorActions.map {
                mapOf(
                    "settlementOperatorActionId" to it.settlementOperatorActionId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "action" to it.action,
                    "targetId" to it.targetId,
                    "reasonNote" to it.reasonNote,
                    "actorType" to it.actorType,
                    "actorId" to it.actorId,
                    "occurredAt" to it.occurredAt.toString()
                )
            }
        )
    }

    private fun settlementFactBundleBody(facts: SettlementFactBundle): String {
        return jsonObjectBody(settlementFactBundleJson(facts))
    }

    private fun settlementLedgerProjectionBody(projection: SettlementLedgerProjectionView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to projection.scenarioRunId,
                "balancesCount" to projection.balances.size,
                "settlementProofsCount" to projection.settlementProofs.size,
                "balances" to projection.balances.map {
                    mapOf(
                        "scenarioRunId" to it.scenarioRunId,
                        "participantId" to it.participantId,
                        "accountId" to it.accountId,
                        "assetType" to it.assetType,
                        "assetId" to it.assetId,
                        "openingQuantity" to it.openingQuantity,
                        "debitQuantity" to it.debitQuantity,
                        "creditQuantity" to it.creditQuantity,
                        "netQuantity" to it.netQuantity,
                        "availableQuantity" to it.availableQuantity,
                        "ledgerEntryCount" to it.ledgerEntryCount,
                        "updatedAt" to it.updatedAt.toString()
                    )
                },
                "settlementProofs" to projection.settlementProofs.map {
                    mapOf(
                        "settlementId" to it.settlementId,
                        "settlementObligationId" to it.settlementObligationId,
                        "settlementInstructionId" to it.settlementInstructionId,
                        "settlementAttemptId" to it.settlementAttemptId,
                        "scenarioRunId" to it.scenarioRunId,
                        "postTradeProfileId" to it.postTradeProfileId,
                        "postTradePolicyVersion" to it.postTradePolicyVersion,
                        "settlementState" to it.settlementState,
                        "proofState" to it.proofState,
                        "cashDebitQuantity" to it.cashDebitQuantity,
                        "cashCreditQuantity" to it.cashCreditQuantity,
                        "securityDebitQuantity" to it.securityDebitQuantity,
                        "securityCreditQuantity" to it.securityCreditQuantity,
                        "cashBalanced" to it.cashBalanced,
                        "securityBalanced" to it.securityBalanced,
                        "legOutcomeCount" to it.legOutcomeCount,
                        "ledgerEntryCount" to it.ledgerEntryCount,
                        "updatedAt" to it.updatedAt.toString()
                    )
                }
            )
        )
    }

    private fun settlementExceptionQueueBody(projection: SettlementExceptionQueueView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to projection.scenarioRunId,
                "exceptionsCount" to projection.exceptionsCount,
                "openCount" to projection.openCount,
                "repairPostedCount" to projection.repairPostedCount,
                "resolvedCount" to projection.resolvedCount,
                "clearingRejectedCount" to projection.clearingRejectedCount,
                "settlementBreakCount" to projection.settlementBreakCount,
                "exceptions" to projection.exceptions.map {
                    mapOf(
                        "settlementExceptionId" to it.settlementExceptionId,
                        "exceptionType" to it.exceptionType,
                        "exceptionState" to it.exceptionState,
                        "state" to it.exceptionState,
                        "severity" to it.severity,
                        "ownerRole" to it.ownerRole,
                        "actionRequired" to it.actionRequired,
                        "reason" to it.reason,
                        "settlementObligationId" to it.settlementObligationId,
                        "tradeId" to it.tradeId,
                        "buyerParticipantId" to it.buyerParticipantId,
                        "sellerParticipantId" to it.sellerParticipantId,
                        "instrumentId" to it.instrumentId,
                        "quantity" to it.quantity,
                        "cashAmount" to it.cashAmount,
                        "currency" to it.currency,
                        "settlementClearingSubmissionId" to it.settlementClearingSubmissionId,
                        "settlementClearingRejectionId" to it.settlementClearingRejectionId,
                        "settlementBreakId" to it.settlementBreakId,
                        "settlementRepairId" to it.settlementRepairId,
                        "settlementResolutionId" to it.settlementResolutionId,
                        "repairAction" to it.repairAction,
                        "actorId" to it.actorId,
                        "correlationId" to it.correlationId,
                        "postTradeProfileId" to it.postTradeProfileId,
                        "postTradePolicyVersion" to it.postTradePolicyVersion,
                        "openedAt" to it.openedAt.toString(),
                        "lastUpdatedAt" to it.lastUpdatedAt.toString(),
                        "resolvedAt" to it.resolvedAt?.toString(),
                        "occurredAt" to it.occurredAt.toString(),
                        "updatedAt" to it.updatedAt.toString()
                    )
                }
            )
        )
    }

    private fun settlementScenarioProofBody(proof: SettlementScenarioProofView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to proof.scenarioRunId,
                "proofStatus" to proof.proofStatus,
                "checksumAlgorithm" to proof.checksumAlgorithm,
                "checksum" to proof.checksum,
                "factsCount" to proof.factsCount,
                "obligationsCount" to proof.obligationsCount,
                "allocationsCount" to proof.allocationsCount,
                "confirmationsCount" to proof.confirmationsCount,
                "affirmationsCount" to proof.affirmationsCount,
                "clearingSubmissionsCount" to proof.clearingSubmissionsCount,
                "clearingAcceptancesCount" to proof.clearingAcceptancesCount,
                "clearingRejectionsCount" to proof.clearingRejectionsCount,
                "novationsCount" to proof.novationsCount,
                "instructionsCount" to proof.instructionsCount,
                "attemptsCount" to proof.attemptsCount,
                "legOutcomesCount" to proof.legOutcomesCount,
                "ledgerEntriesCount" to proof.ledgerEntriesCount,
                "settlementsCount" to proof.settlementsCount,
                "breaksCount" to proof.breaksCount,
                "repairsCount" to proof.repairsCount,
                "resolutionsCount" to proof.resolutionsCount,
                "operatorActionsCount" to proof.operatorActionsCount,
                "profilePolicies" to proof.profilePolicies.map {
                    mapOf(
                        "postTradeProfileId" to it.postTradeProfileId,
                        "postTradePolicyVersion" to it.postTradePolicyVersion,
                        "factCount" to it.factCount
                    )
                },
                "causationGapsCount" to proof.causationGaps.size,
                "causationGaps" to proof.causationGaps.map {
                    mapOf(
                        "factType" to it.factType,
                        "factId" to it.factId,
                        "missingReferenceType" to it.missingReferenceType,
                        "missingReferenceId" to it.missingReferenceId
                    )
                },
                "obligations" to proof.obligations.map {
                    mapOf(
                        "settlementObligationId" to it.settlementObligationId,
                        "tradeId" to it.tradeId,
                        "buyerParticipantId" to it.buyerParticipantId,
                        "sellerParticipantId" to it.sellerParticipantId,
                        "instrumentId" to it.instrumentId,
                        "quantity" to it.quantity,
                        "cashAmount" to it.cashAmount,
                        "currency" to it.currency,
                        "settlementAllocationIds" to it.settlementAllocationIds,
                        "settlementConfirmationIds" to it.settlementConfirmationIds,
                        "settlementAffirmationIds" to it.settlementAffirmationIds,
                        "settlementClearingSubmissionIds" to it.settlementClearingSubmissionIds,
                        "settlementClearingAcceptanceIds" to it.settlementClearingAcceptanceIds,
                        "settlementClearingRejectionIds" to it.settlementClearingRejectionIds,
                        "settlementNovationIds" to it.settlementNovationIds,
                        "settlementInstructionIds" to it.settlementInstructionIds,
                        "settlementAttemptIds" to it.settlementAttemptIds,
                        "ledgerEntryIds" to it.ledgerEntryIds,
                        "settlementIds" to it.settlementIds,
                        "settlementBreakIds" to it.settlementBreakIds,
                        "settlementRepairIds" to it.settlementRepairIds,
                        "settlementResolutionIds" to it.settlementResolutionIds
                    )
                },
                "balances" to proof.balances.map {
                    mapOf(
                        "participantId" to it.participantId,
                        "accountId" to it.accountId,
                        "assetType" to it.assetType,
                        "assetId" to it.assetId,
                        "availableQuantity" to it.availableQuantity,
                        "ledgerEntryCount" to it.ledgerEntryCount
                    )
                },
                "settlementProofs" to proof.settlementProofs.map {
                    mapOf(
                        "settlementId" to it.settlementId,
                        "settlementObligationId" to it.settlementObligationId,
                        "settlementAttemptId" to it.settlementAttemptId,
                        "cashBalanced" to it.cashBalanced,
                        "securityBalanced" to it.securityBalanced,
                        "ledgerEntryCount" to it.ledgerEntryCount
                    )
                },
                "updatedAt" to proof.updatedAt.toString()
            )
        )
    }

    private fun settlementScoreProjectionBody(projection: SettlementScoreProjectionView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to projection.scenarioRunId,
                "asOf" to projection.asOf.toString(),
                "agedFailAfterSeconds" to projection.agedFailAfterSeconds,
                "participantsCount" to projection.participants.size,
                "participants" to projection.participants.map {
                    mapOf(
                        "scenarioRunId" to it.scenarioRunId,
                        "participantId" to it.participantId,
                        "cashBalances" to it.cashBalances.map { balance ->
                            mapOf("assetId" to balance.assetId, "availableQuantity" to balance.availableQuantity)
                        },
                        "securityBalances" to it.securityBalances.map { balance ->
                            mapOf("assetId" to balance.assetId, "availableQuantity" to balance.availableQuantity)
                        },
                        "pendingValue" to it.pendingValue,
                        "haircutAdjustedPendingValue" to it.haircutAdjustedPendingValue,
                        "blockedUnsettledValue" to it.blockedUnsettledValue,
                        "scorePenaltyPoints" to it.scorePenaltyPoints,
                        "settledObligationCount" to it.settledObligationCount,
                        "pendingObligationCount" to it.pendingObligationCount,
                        "failedObligationCount" to it.failedObligationCount,
                        "agedFailCount" to it.agedFailCount,
                        "openBreakCount" to it.openBreakCount,
                        "repairPendingCount" to it.repairPendingCount,
                        "updatedAt" to it.updatedAt.toString()
                    )
                },
                "updatedAt" to projection.updatedAt.toString()
            )
        )
    }

    private fun settlementObligationViewJson(view: SettlementObligationView): Map<String, Any?> {
        return mapOf(
            "settlementObligationId" to view.settlementObligationId,
            "scenarioRunId" to view.scenarioRunId,
            "postTradeProfileId" to view.postTradeProfileId,
            "postTradePolicyVersion" to view.postTradePolicyVersion,
            "tradeId" to view.tradeId,
            "buyerParticipantId" to view.buyerParticipantId,
            "sellerParticipantId" to view.sellerParticipantId,
            "instrumentId" to view.instrumentId,
            "quantity" to view.quantity,
            "cashAmount" to view.cashAmount,
            "currency" to view.currency,
            "obligationState" to view.obligationState,
            "settlementState" to view.settlementState,
            "exceptionState" to view.exceptionState,
            "clearingState" to view.clearingState,
            "settlementClearingSubmissionId" to view.settlementClearingSubmissionId,
            "settlementClearingAcceptanceId" to view.settlementClearingAcceptanceId,
            "settlementClearingRejectionId" to view.settlementClearingRejectionId,
            "settlementNovationId" to view.settlementNovationId,
            "settlementInstructionId" to view.settlementInstructionId,
            "settlementAttemptId" to view.settlementAttemptId,
            "settlementAttemptNumber" to view.settlementAttemptNumber,
            "settlementId" to view.settlementId,
            "cashLegState" to view.cashLegState,
            "securityLegState" to view.securityLegState,
            "ledgerEntryCount" to view.ledgerEntryCount,
            "settlementBreakId" to view.settlementBreakId,
            "settlementRepairId" to view.settlementRepairId,
            "settlementResolutionId" to view.settlementResolutionId,
            "occurredAt" to view.occurredAt.toString(),
            "updatedAt" to view.updatedAt.toString()
        )
    }

    private fun jsonObjectBody(fields: Map<String, Any?>): String {
        return JsonCodec.writeObject(*fields.map { it.key to it.value }.toTypedArray())
    }
}
