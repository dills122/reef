package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SettlementFactStoreTest {
    @Test
    fun appendsAndReadsP2SettlementChainByScenarioRunId() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2")

        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-p2")

        assertEquals(listOf("obl-1"), stored.obligations.map { it.settlementObligationId })
        assertEquals(listOf("instruction-1"), stored.instructions.map { it.settlementInstructionId })
        assertEquals(listOf("attempt-1"), stored.attempts.map { it.settlementAttemptId })
        assertEquals(listOf("break-1"), stored.breaks.map { it.settlementBreakId })
        assertEquals(listOf("repair-1"), stored.repairs.map { it.settlementRepairId })
        assertEquals(listOf("resolution-1"), stored.resolutions.map { it.settlementResolutionId })
        assertEquals(listOf(DefaultPostTradeProfileId), stored.obligations.map { it.postTradeProfileId })
        assertEquals(listOf(DefaultPostTradePolicyVersion), stored.obligations.map { it.postTradePolicyVersion })
    }

    @Test
    fun storesPostTradeProfileEvidenceAcrossSettlementChain() {
        val store = InMemorySettlementFactStore()
        val finality = finalityFacts("run-p2")
        val facts = p2Facts("run-p2")
            .copy(
                legOutcomes = finality.legOutcomes,
                ledgerEntries = finality.ledgerEntries,
                settlements = finality.settlements
            )
            .withProfile("instant-post-trade-v1", 3)

        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-p2")

        assertEquals(setOf("instant-post-trade-v1"), stored.profileIds())
        assertEquals(setOf(3), stored.policyVersions())
    }

    @Test
    fun appendsAndReadsInstantSettlementFinalityProof() {
        val store = InMemorySettlementFactStore()
        val facts = finalityFacts("run-finality")

        store.appendFacts(facts)
        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-finality")

        assertEquals(listOf("cash-leg-1", "security-leg-1"), stored.legOutcomes.map { it.settlementLegOutcomeId }.sorted())
        assertEquals(
            listOf(
                "ledger-buyer-cash-debit",
                "ledger-buyer-security-credit",
                "ledger-seller-cash-credit",
                "ledger-seller-security-debit"
            ),
            stored.ledgerEntries.map { it.ledgerEntryId }.sorted()
        )
        assertEquals(listOf("settlement-1"), stored.settlements.map { it.settlementId })
    }

    @Test
    fun appendsAndReadsClearingAndNovationFacts() {
        val store = InMemorySettlementFactStore()
        val facts = clearingLifecycleFacts("run-clearing")

        store.appendFacts(facts)
        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-clearing")

        assertEquals(listOf("clearing-submission-1"), stored.clearingSubmissions.map { it.settlementClearingSubmissionId })
        assertEquals(listOf("clearing-acceptance-1"), stored.clearingAcceptances.map { it.settlementClearingAcceptanceId })
        assertEquals(listOf("novation-1"), stored.novations.map { it.settlementNovationId })
        assertEquals("affirmation-1", stored.clearingSubmissions.single().causationId)
        assertEquals("clearing-submission-1", stored.clearingAcceptances.single().causationId)
        assertEquals("clearing-acceptance-1", stored.novations.single().causationId)
    }

    @Test
    fun appendsAndReadsClearingRejectedFact() {
        val store = InMemorySettlementFactStore()
        val facts = clearingLifecycleFacts("run-rejected").copy(
            clearingAcceptances = emptyList(),
            novations = emptyList(),
            clearingRejections = listOf(clearingRejected("run-rejected"))
        )

        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-rejected")

        assertEquals(listOf("clearing-rejection-1"), stored.clearingRejections.map { it.settlementClearingRejectionId })
        assertEquals(SettlementClearingRejectedReason, stored.clearingRejections.single().reason)
    }

    @Test
    fun rejectsClearingFactsWithoutRequiredParents() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-clearing",
                    obligations = listOf(obligation("run-clearing")),
                    clearingSubmissions = listOf(clearingSubmitted("run-clearing"))
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                clearingLifecycleFacts("run-clearing").copy(
                    clearingSubmissions = emptyList(),
                    clearingAcceptances = listOf(clearingAccepted("run-clearing")),
                    novations = emptyList()
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                clearingLifecycleFacts("run-clearing").copy(
                    clearingAcceptances = emptyList(),
                    novations = listOf(novation("run-clearing"))
                )
            )
        }
    }

    @Test
    fun rejectsInvalidClearingRejectionShape() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                clearingLifecycleFacts("run-rejected").copy(
                    clearingAcceptances = emptyList(),
                    novations = emptyList(),
                    clearingRejections = listOf(clearingRejected("run-rejected").copy(reason = "OTHER"))
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                clearingLifecycleFacts("run-rejected").copy(
                    clearingAcceptances = emptyList(),
                    novations = emptyList(),
                    clearingRejections = listOf(clearingRejected("run-rejected").copy(state = "OTHER"))
                )
            )
        }
    }

    @Test
    fun rejectsSettlementWithoutLegAndLedgerProof() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-finality",
                    obligations = listOf(obligation("run-finality")),
                    instructions = listOf(instructionCreated("run-finality")),
                    attempts = listOf(attemptStarted("run-finality")),
                    settlements = listOf(settled("run-finality"))
                )
            )
        }
    }

    @Test
    fun rejectsSettlementWhenLedgerProofDoesNotBalance() {
        val store = InMemorySettlementFactStore()
        val facts = finalityFacts("run-finality").copy(
            ledgerEntries = finalityFacts("run-finality").ledgerEntries.map {
                if (it.ledgerEntryId == "ledger-seller-cash-credit") it.copy(quantity = "14999.99") else it
            }
        )

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(facts)
        }
    }

    @Test
    fun rejectsMixedPostTradeProfileEvidenceInScenarioRun() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    breaks = listOf(breakOpened("run-p2").copy(postTradeProfileId = "instant-post-trade-v1"))
                )
            )
        }
    }

    @Test
    fun duplicateAppendKeepsFactsIdempotent() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2")

        store.appendFacts(facts)
        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-p2")

        assertEquals(1, stored.obligations.size)
        assertEquals(1, stored.instructions.size)
        assertEquals(1, stored.attempts.size)
        assertEquals(1, stored.breaks.size)
        assertEquals(1, stored.repairs.size)
        assertEquals(1, stored.resolutions.size)
    }

    @Test
    fun appendsAndReadsOperatorActionFacts() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2").copy(
            operatorActions = listOf(
                SettlementOperatorActionFact(
                    settlementOperatorActionId = "operator-action-1",
                    scenarioRunId = "run-p2",
                    correlationId = "corr-1",
                    causationId = "break-1",
                    action = SettlementOperatorActionForceSettle,
                    targetId = "break-1",
                    reasonNote = "operator override for scenario",
                    actorId = "ops-1",
                    occurredAt = Instant.parse("2026-01-01T00:00:04Z")
                )
            )
        )

        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-p2")

        assertEquals(listOf("operator-action-1"), stored.operatorActions.map { it.settlementOperatorActionId })
        assertEquals(listOf("FORCE_SETTLE"), stored.operatorActions.map { it.action })
        assertEquals(listOf("operator override for scenario"), stored.operatorActions.map { it.reasonNote })
    }

    @Test
    fun rejectsDuplicateFactIdWithDifferentPayload() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2")

        store.appendFacts(facts)

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2").copy(tradeId = "other-trade"))
                )
            )
        }
    }

    @Test
    fun rejectsBreakWithoutObligation() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    breaks = listOf(
                        SettlementBreakOpenedFact(
                            settlementBreakId = "break-1",
                            settlementObligationId = "missing",
                            scenarioRunId = "run-p2",
                            correlationId = "corr-1",
                            causationId = "trade-1",
                            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rejectsAttemptWithoutObligation() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    attempts = listOf(attemptStarted("run-p2").copy(settlementObligationId = "missing"))
                )
            )
        }
    }

    @Test
    fun rejectsInstructionWithoutObligation() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    instructions = listOf(instructionCreated("run-p2").copy(settlementObligationId = "missing"))
                )
            )
        }
    }

    @Test
    fun rejectsResolutionWithoutRepair() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    breaks = listOf(breakOpened("run-p2")),
                    resolutions = listOf(resolution("run-p2"))
                )
            )
        }
    }

    @Test
    fun rejectsRepairWithoutBreak() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    repairs = listOf(repair("run-p2"))
                )
            )
        }
    }

    @Test
    fun rejectsRepairWhoseObligationDoesNotMatchBreak() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2"), obligation("run-p2").copy(settlementObligationId = "obl-2", tradeId = "trade-2")),
                    breaks = listOf(breakOpened("run-p2")),
                    repairs = listOf(repair("run-p2").copy(settlementObligationId = "obl-2"))
                )
            )
        }
    }

    @Test
    fun rejectsResolutionWhoseBreakDoesNotMatchRepair() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    breaks = listOf(breakOpened("run-p2"), breakOpened("run-p2").copy(settlementBreakId = "break-2")),
                    repairs = listOf(repair("run-p2")),
                    resolutions = listOf(resolution("run-p2").copy(settlementBreakId = "break-2"))
                )
            )
        }
    }

    @Test
    fun rejectsResolutionWhoseObligationDoesNotMatchRepair() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2"), obligation("run-p2").copy(settlementObligationId = "obl-2", tradeId = "trade-2")),
                    breaks = listOf(breakOpened("run-p2")),
                    repairs = listOf(repair("run-p2")),
                    resolutions = listOf(resolution("run-p2").copy(settlementObligationId = "obl-2"))
                )
            )
        }
    }

    @Test
    fun rejectsObligationWithBlankRequiredFields() {
        val store = InMemorySettlementFactStore()
        val bases = listOf(
            obligation("run-p2").copy(tradeId = ""),
            obligation("run-p2").copy(buyerParticipantId = ""),
            obligation("run-p2").copy(sellerParticipantId = ""),
            obligation("run-p2").copy(instrumentId = ""),
            obligation("run-p2").copy(quantity = ""),
            obligation("run-p2").copy(cashAmount = ""),
            obligation("run-p2").copy(currency = ""),
            obligation("run-p2").copy(state = "OTHER")
        )
        bases.forEach { bad ->
            assertFailsWith<IllegalArgumentException>("expected failure for $bad") {
                store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(bad)))
            }
        }
    }

    @Test
    fun rejectsBreakWithWrongReasonOrState() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2"))))

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", breaks = listOf(breakOpened("run-p2").copy(reason = "OTHER")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", breaks = listOf(breakOpened("run-p2").copy(state = "OTHER")))
            )
        }
    }

    @Test
    fun rejectsAttemptWithWrongStateOrAttemptNumber() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                obligations = listOf(obligation("run-p2")),
                instructions = listOf(instructionCreated("run-p2"))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", attempts = listOf(attemptStarted("run-p2").copy(state = "OTHER")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", attempts = listOf(attemptStarted("run-p2").copy(attemptNumber = 0)))
            )
        }
    }

    @Test
    fun rejectsInstructionWithWrongTypeOrState() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2"))))

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", instructions = listOf(instructionCreated("run-p2").copy(instructionType = "FOP")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", instructions = listOf(instructionCreated("run-p2").copy(state = "OTHER")))
            )
        }
    }

    @Test
    fun rejectsRepairWithWrongActionActorTypeOrBlankActorId() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                obligations = listOf(obligation("run-p2")),
                breaks = listOf(breakOpened("run-p2"))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", repairs = listOf(repair("run-p2").copy(repairAction = "OTHER"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    repairs = listOf(repair("run-p2").copy(repairAction = SettlementRepairPostedActionSecurity))
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", repairs = listOf(repair("run-p2").copy(actorType = "OTHER"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", repairs = listOf(repair("run-p2").copy(actorId = ""))))
        }
    }

    @Test
    fun acceptsSecurityRepairForSecurityBreak() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                obligations = listOf(obligation("run-p2")),
                breaks = listOf(breakOpened("run-p2", reason = SettlementBreakOpenedReasonSecurity))
            )
        )

        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                repairs = listOf(repair("run-p2").copy(repairAction = SettlementRepairPostedActionSecurity))
            )
        )

        assertEquals(SettlementRepairPostedActionSecurity, store.factsByScenarioRunId("run-p2").repairs.single().repairAction)
    }

    @Test
    fun rejectsResolutionWithWrongSettlementOrExceptionState() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                obligations = listOf(obligation("run-p2")),
                breaks = listOf(breakOpened("run-p2")),
                repairs = listOf(repair("run-p2"))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", resolutions = listOf(resolution("run-p2").copy(settlementState = "OTHER"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", resolutions = listOf(resolution("run-p2").copy(exceptionState = "OTHER"))))
        }
    }

    @Test
    fun rejectsFactWithScenarioRunIdMismatch() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("other-run")))
            )
        }
    }

    @Test
    fun rejectsFactWithBlankCorrelationOrCausationId() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2").copy(correlationId = ""))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2").copy(causationId = ""))))
        }
    }

    @Test
    fun factsByScenarioRunIdRejectsBlankId() {
        val store = InMemorySettlementFactStore()
        assertFailsWith<IllegalArgumentException> {
            store.factsByScenarioRunId("")
        }
    }

    @Test
    fun appendFactsSkipsValidationForEmptyBundle() {
        val store = InMemorySettlementFactStore()
        val empty = SettlementFactBundle(scenarioRunId = "")
        assertEquals(empty, store.appendFacts(empty))
    }

    @Test
    fun defaultsPostgresSettlementNamesToSettlementSchema() {
        val names = PostgresSettlementSqlNames()

        assertEquals("settlement.resource_positions", names.resourcePositions)
        assertEquals("settlement.obligations", names.obligations)
        assertEquals("settlement.allocations", names.allocations)
        assertEquals("settlement.confirmations", names.confirmations)
        assertEquals("settlement.affirmations", names.affirmations)
        assertEquals("settlement.instructions", names.instructions)
        assertEquals("settlement.attempts", names.attempts)
        assertEquals("settlement.leg_outcomes", names.legOutcomes)
        assertEquals("settlement.ledger_entries", names.ledgerEntries)
        assertEquals("settlement.settlements", names.settlements)
        assertEquals("settlement.breaks", names.breaks)
        assertEquals("settlement.repairs", names.repairs)
        assertEquals("settlement.resolutions", names.resolutions)
    }

    @Test
    fun blankPostgresSettlementSchemaFallsBackToSettlement() {
        val names = PostgresSettlementSqlNames(schema = "")

        assertFalse(names.obligations.startsWith("."))
        assertFalse(names.obligations.endsWith("."))
        assertFalse(names.instructions.startsWith("."))
        assertFalse(names.instructions.endsWith("."))
        assertFalse(names.attempts.startsWith("."))
        assertFalse(names.attempts.endsWith("."))
    }

    @Test
    fun rejectsUnsafePostgresSettlementSchema() {
        assertFailsWith<IllegalArgumentException> {
            PostgresSettlementSqlNames(schema = "settlement;drop schema runtime")
        }
    }
}

private fun p2Facts(scenarioRunId: String): SettlementFactBundle {
    return SettlementFactBundle(
        scenarioRunId = scenarioRunId,
        obligations = listOf(obligation(scenarioRunId)),
        instructions = listOf(instructionCreated(scenarioRunId)),
        attempts = listOf(attemptStarted(scenarioRunId)),
        breaks = listOf(breakOpened(scenarioRunId)),
        repairs = listOf(repair(scenarioRunId)),
        resolutions = listOf(resolution(scenarioRunId))
    )
}

private fun finalityFacts(scenarioRunId: String): SettlementFactBundle {
    return SettlementFactBundle(
        scenarioRunId = scenarioRunId,
        obligations = listOf(obligation(scenarioRunId)),
        instructions = listOf(instructionCreated(scenarioRunId)),
        attempts = listOf(attemptStarted(scenarioRunId)),
        legOutcomes = listOf(
            SettlementLegOutcomeFact(
                settlementLegOutcomeId = "cash-leg-1",
                settlementObligationId = "obl-1",
                settlementInstructionId = "instruction-1",
                settlementAttemptId = "attempt-1",
                scenarioRunId = scenarioRunId,
                correlationId = "corr-1",
                causationId = "attempt-1",
                legType = SettlementLegTypeCash,
                occurredAt = Instant.parse("2026-01-01T00:00:01Z")
            ),
            SettlementLegOutcomeFact(
                settlementLegOutcomeId = "security-leg-1",
                settlementObligationId = "obl-1",
                settlementInstructionId = "instruction-1",
                settlementAttemptId = "attempt-1",
                scenarioRunId = scenarioRunId,
                correlationId = "corr-1",
                causationId = "attempt-1",
                legType = SettlementLegTypeSecurity,
                occurredAt = Instant.parse("2026-01-01T00:00:01Z")
            )
        ),
        ledgerEntries = listOf(
            ledgerEntry(scenarioRunId, "ledger-buyer-cash-debit", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeCash, "USD", SettlementLedgerDirectionDebit, "15000.00"),
            ledgerEntry(scenarioRunId, "ledger-seller-cash-credit", "seller-1", "account-seller-1", SettlementLedgerEntryTypeCash, "USD", SettlementLedgerDirectionCredit, "15000.00"),
            ledgerEntry(scenarioRunId, "ledger-seller-security-debit", "seller-1", "account-seller-1", SettlementLedgerEntryTypeSecurity, "AAPL", SettlementLedgerDirectionDebit, "100"),
            ledgerEntry(scenarioRunId, "ledger-buyer-security-credit", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeSecurity, "AAPL", SettlementLedgerDirectionCredit, "100")
        ),
        settlements = listOf(settled(scenarioRunId))
    )
}

private fun clearingLifecycleFacts(scenarioRunId: String): SettlementFactBundle {
    return SettlementFactBundle(
        scenarioRunId = scenarioRunId,
        obligations = listOf(obligation(scenarioRunId)),
        allocations = listOf(allocation(scenarioRunId)),
        confirmations = listOf(confirmation(scenarioRunId)),
        affirmations = listOf(affirmation(scenarioRunId)),
        clearingSubmissions = listOf(clearingSubmitted(scenarioRunId)),
        clearingAcceptances = listOf(clearingAccepted(scenarioRunId)),
        novations = listOf(novation(scenarioRunId))
    )
}

private fun ledgerEntry(
    scenarioRunId: String,
    ledgerEntryId: String,
    participantId: String,
    accountId: String,
    assetType: String,
    assetId: String,
    direction: String,
    quantity: String
): SettlementLedgerEntryFact {
    return SettlementLedgerEntryFact(
        ledgerEntryId = ledgerEntryId,
        settlementObligationId = "obl-1",
        settlementInstructionId = "instruction-1",
        settlementAttemptId = "attempt-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "attempt-1",
        participantId = participantId,
        accountId = accountId,
        assetType = assetType,
        assetId = assetId,
        direction = direction,
        quantity = quantity,
        occurredAt = Instant.parse("2026-01-01T00:00:01Z")
    )
}

private fun SettlementFactBundle.withProfile(profileId: String, policyVersion: Int): SettlementFactBundle {
    return copy(
        resourcePositions = resourcePositions.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        obligations = obligations.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        allocations = allocations.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        confirmations = confirmations.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        affirmations = affirmations.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        clearingSubmissions = clearingSubmissions.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        clearingAcceptances = clearingAcceptances.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        clearingRejections = clearingRejections.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        novations = novations.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        instructions = instructions.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        attempts = attempts.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        legOutcomes = legOutcomes.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        ledgerEntries = ledgerEntries.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        settlements = settlements.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        breaks = breaks.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        repairs = repairs.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        },
        resolutions = resolutions.map {
            it.copy(postTradeProfileId = profileId, postTradePolicyVersion = policyVersion)
        }
    )
}

private fun SettlementFactBundle.profileIds(): Set<String> {
    return (
        resourcePositions.map { it.postTradeProfileId } +
            obligations.map { it.postTradeProfileId } +
            allocations.map { it.postTradeProfileId } +
            confirmations.map { it.postTradeProfileId } +
            affirmations.map { it.postTradeProfileId } +
            clearingSubmissions.map { it.postTradeProfileId } +
            clearingAcceptances.map { it.postTradeProfileId } +
            clearingRejections.map { it.postTradeProfileId } +
            novations.map { it.postTradeProfileId } +
            instructions.map { it.postTradeProfileId } +
            attempts.map { it.postTradeProfileId } +
            legOutcomes.map { it.postTradeProfileId } +
            ledgerEntries.map { it.postTradeProfileId } +
            settlements.map { it.postTradeProfileId } +
            breaks.map { it.postTradeProfileId } +
            repairs.map { it.postTradeProfileId } +
            resolutions.map { it.postTradeProfileId }
        ).toSet()
}

private fun SettlementFactBundle.policyVersions(): Set<Int> {
    return (
        resourcePositions.map { it.postTradePolicyVersion } +
            obligations.map { it.postTradePolicyVersion } +
            allocations.map { it.postTradePolicyVersion } +
            confirmations.map { it.postTradePolicyVersion } +
            affirmations.map { it.postTradePolicyVersion } +
            clearingSubmissions.map { it.postTradePolicyVersion } +
            clearingAcceptances.map { it.postTradePolicyVersion } +
            clearingRejections.map { it.postTradePolicyVersion } +
            novations.map { it.postTradePolicyVersion } +
            instructions.map { it.postTradePolicyVersion } +
            attempts.map { it.postTradePolicyVersion } +
            legOutcomes.map { it.postTradePolicyVersion } +
            ledgerEntries.map { it.postTradePolicyVersion } +
            settlements.map { it.postTradePolicyVersion } +
            breaks.map { it.postTradePolicyVersion } +
            repairs.map { it.postTradePolicyVersion } +
            resolutions.map { it.postTradePolicyVersion }
        ).toSet()
}

private fun obligation(scenarioRunId: String): SettlementObligationCreatedFact {
    return SettlementObligationCreatedFact(
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "trade-1",
        tradeId = "trade-1",
        buyerParticipantId = "buyer-1",
        sellerParticipantId = "seller-1",
        instrumentId = "AAPL",
        quantity = "100",
        cashAmount = "15000.00",
        currency = "USD",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun allocation(scenarioRunId: String): SettlementAllocationProposedFact {
    return SettlementAllocationProposedFact(
        settlementAllocationId = "allocation-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "obl-1",
        tradeId = "trade-1",
        buyOrderId = "buy-order-1",
        sellOrderId = "sell-order-1",
        buyerAccountId = "account-buyer-1",
        sellerAccountId = "account-seller-1",
        quantity = "100",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun confirmation(scenarioRunId: String): SettlementConfirmationGeneratedFact {
    return SettlementConfirmationGeneratedFact(
        settlementConfirmationId = "confirmation-1",
        settlementAllocationId = "allocation-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "allocation-1",
        tradeId = "trade-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun affirmation(scenarioRunId: String): SettlementAffirmationAcceptedFact {
    return SettlementAffirmationAcceptedFact(
        settlementAffirmationId = "affirmation-1",
        settlementConfirmationId = "confirmation-1",
        settlementAllocationId = "allocation-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "confirmation-1",
        tradeId = "trade-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun clearingSubmitted(scenarioRunId: String): SettlementClearingSubmittedFact {
    return SettlementClearingSubmittedFact(
        settlementClearingSubmissionId = "clearing-submission-1",
        settlementObligationId = "obl-1",
        settlementAffirmationId = "affirmation-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "affirmation-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun clearingAccepted(scenarioRunId: String): SettlementClearingAcceptedFact {
    return SettlementClearingAcceptedFact(
        settlementClearingAcceptanceId = "clearing-acceptance-1",
        settlementClearingSubmissionId = "clearing-submission-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "clearing-submission-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun clearingRejected(scenarioRunId: String): SettlementClearingRejectedFact {
    return SettlementClearingRejectedFact(
        settlementClearingRejectionId = "clearing-rejection-1",
        settlementClearingSubmissionId = "clearing-submission-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "clearing-submission-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun novation(scenarioRunId: String): SettlementNovationRecordedFact {
    return SettlementNovationRecordedFact(
        settlementNovationId = "novation-1",
        settlementClearingAcceptanceId = "clearing-acceptance-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "clearing-acceptance-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun instructionCreated(scenarioRunId: String): SettlementInstructionCreatedFact {
    return SettlementInstructionCreatedFact(
        settlementInstructionId = "instruction-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "obl-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun attemptStarted(scenarioRunId: String): SettlementAttemptStartedFact {
    return SettlementAttemptStartedFact(
        settlementAttemptId = "attempt-1",
        settlementObligationId = "obl-1",
        settlementInstructionId = "instruction-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "instruction-1",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun breakOpened(
    scenarioRunId: String,
    reason: String = SettlementBreakOpenedReason
): SettlementBreakOpenedFact {
    return SettlementBreakOpenedFact(
        settlementBreakId = "break-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "trade-1",
        reason = reason,
        occurredAt = Instant.parse("2026-01-01T00:00:01Z")
    )
}

private fun repair(scenarioRunId: String): SettlementRepairPostedFact {
    return SettlementRepairPostedFact(
        settlementRepairId = "repair-1",
        settlementBreakId = "break-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "repair-command-1",
        actorId = "ops-user-1",
        occurredAt = Instant.parse("2026-01-01T00:00:02Z")
    )
}

private fun settled(scenarioRunId: String): SettlementSettledFact {
    return SettlementSettledFact(
        settlementId = "settlement-1",
        settlementObligationId = "obl-1",
        settlementInstructionId = "instruction-1",
        settlementAttemptId = "attempt-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "attempt-1",
        occurredAt = Instant.parse("2026-01-01T00:00:02Z")
    )
}

private fun resolution(scenarioRunId: String): SettlementResolvedFact {
    return SettlementResolvedFact(
        settlementResolutionId = "resolution-1",
        settlementObligationId = "obl-1",
        settlementBreakId = "break-1",
        settlementRepairId = "repair-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "repair-command-1",
        occurredAt = Instant.parse("2026-01-01T00:00:03Z")
    )
}
