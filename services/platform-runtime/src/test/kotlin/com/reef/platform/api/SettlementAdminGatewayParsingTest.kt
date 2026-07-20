package com.reef.platform.api

import com.reef.platform.application.settlement.DefaultPostTradePolicyVersion
import com.reef.platform.application.settlement.DefaultPostTradeProfileId
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.SettlementFactBundle
import com.reef.platform.application.settlement.SettlementFactStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SettlementAdminGatewayParsingTest {
    @Test
    fun parsesAndReturnsEverySettlementFactTypeWithCanonicalDefaults() {
        val store = CapturingSettlementFactStore()
        val gateway = SettlementAdminGateway(
            settlementFactStore = store,
            settlementObligationMaterializer = null,
            postTradeProfileResolver = PostTradeProfileResolver(),
            scenarioRunPostTradeProfileLookup = { null },
            venueSessionPostTradeProfileLookup = { null },
            adminSessionAuth = testSettlementAdminSessionAuth()
        )

        val response = gateway.appendSettlementFactsResponse(allSettlementFactTypesBody())

        assertEquals(200, response.status, response.body)
        val facts = store.appended ?: error("settlement facts were not appended")
        assertEquals("run-all-facts", facts.scenarioRunId)
        assertEquals(DefaultPostTradeProfileId, facts.obligations.single().postTradeProfileId)
        assertEquals(DefaultPostTradePolicyVersion, facts.obligations.single().postTradePolicyVersion)
        assertEquals("ALLOCATION_PROPOSED", facts.allocations.single().state)
        assertEquals("SYSTEM", facts.affirmations.single().actorType)
        assertEquals("post-trade-auto-affirmer", facts.affirmations.single().actorId)
        assertEquals("CLEARING_REJECTED", facts.clearingRejections.single().state)
        assertEquals("DVP", facts.instructions.single().instructionType)
        assertEquals(1, facts.attempts.single().attemptNumber)
        assertEquals("SETTLED", facts.settlements.single().settlementState)
        assertEquals("USER", facts.operatorActions.single().actorType)
        assertEquals(18, factCount(facts))
        assertContains(response.body, "\"settlementAffirmationId\":\"affirmation-1\"")
        assertContains(response.body, "\"settlementClearingRejectionId\":\"rejection-1\"")
        assertContains(response.body, "\"settlementOperatorActionId\":\"operator-action-1\"")
    }

    @Test
    fun rejectsInvalidFactTimestampsAndPolicyVersionsBeforeCallingStore() {
        val store = CapturingSettlementFactStore()
        val gateway = SettlementAdminGateway(
            settlementFactStore = store,
            settlementObligationMaterializer = null,
            postTradeProfileResolver = PostTradeProfileResolver(),
            scenarioRunPostTradeProfileLookup = { null },
            venueSessionPostTradeProfileLookup = { null },
            adminSessionAuth = testSettlementAdminSessionAuth()
        )

        val invalidTime = gateway.appendSettlementFactsResponse(
            """{"scenarioRunId":"run-invalid-time","obligations":[{"occurredAt":"yesterday"}]}"""
        )
        val invalidPolicy = gateway.appendSettlementFactsResponse(
            """{"scenarioRunId":"run-invalid-policy","postTradePolicyVersion":0}"""
        )

        assertEquals(400, invalidTime.status)
        assertContains(invalidTime.body, "occurredAt must be RFC3339")
        assertEquals(400, invalidPolicy.status)
        assertContains(invalidPolicy.body, "postTradePolicyVersion must be a positive integer")
        assertEquals(null, store.appended)
    }
}

private class CapturingSettlementFactStore : SettlementFactStore {
    var appended: SettlementFactBundle? = null

    override fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle {
        appended = facts
        return facts
    }

    override fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle =
        appended?.takeIf { it.scenarioRunId == scenarioRunId } ?: SettlementFactBundle(scenarioRunId)
}

private fun factCount(facts: SettlementFactBundle): Int =
    facts.resourcePositions.size + facts.obligations.size + facts.allocations.size +
        facts.confirmations.size + facts.affirmations.size + facts.clearingSubmissions.size +
        facts.clearingAcceptances.size + facts.clearingRejections.size + facts.novations.size +
        facts.instructions.size + facts.attempts.size + facts.legOutcomes.size +
        facts.ledgerEntries.size + facts.settlements.size + facts.breaks.size +
        facts.repairs.size + facts.resolutions.size + facts.operatorActions.size

private fun testSettlementAdminSessionAuth() = AdminSessionAuth(
    adminAuthService = null,
    adminIdentityService = null,
    adminGitHubOAuthClient = null,
    adminSessionCookieName = "reef_admin_session",
    adminSessionCookieSecure = true,
    localDevAdminAuthBypass = false,
    internalHttpExposureMode = InternalHttpExposureMode.Disabled
)

private fun allSettlementFactTypesBody(): String = """
    {
      "scenarioRunId":"run-all-facts",
      "resourcePositions":[{"resourcePositionId":"position-1","participantId":"buyer","accountId":"buyer-account","assetType":"CASH","assetId":"USD","quantity":"100","occurredAt":"2026-07-20T00:00:00Z"}],
      "obligations":[{"settlementObligationId":"obligation-1","tradeId":"trade-1","buyerParticipantId":"buyer","sellerParticipantId":"seller","instrumentId":"AAPL","quantity":"10","cashAmount":"1000","currency":"USD","occurredAt":"2026-07-20T00:00:01Z"}],
      "allocations":[{"settlementAllocationId":"allocation-1","settlementObligationId":"obligation-1","tradeId":"trade-1","buyOrderId":"buy-1","sellOrderId":"sell-1","buyerAccountId":"buyer-account","sellerAccountId":"seller-account","quantity":"10","occurredAt":"2026-07-20T00:00:02Z"}],
      "confirmations":[{"settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obligation-1","tradeId":"trade-1","occurredAt":"2026-07-20T00:00:03Z"}],
      "affirmations":[{"settlementAffirmationId":"affirmation-1","settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obligation-1","tradeId":"trade-1","occurredAt":"2026-07-20T00:00:04Z"}],
      "clearingSubmissions":[{"settlementClearingSubmissionId":"submission-1","settlementObligationId":"obligation-1","settlementAffirmationId":"affirmation-1","occurredAt":"2026-07-20T00:00:05Z"}],
      "clearingAcceptances":[{"settlementClearingAcceptanceId":"acceptance-1","settlementClearingSubmissionId":"submission-1","settlementObligationId":"obligation-1","occurredAt":"2026-07-20T00:00:06Z"}],
      "clearingRejections":[{"settlementClearingRejectionId":"rejection-1","settlementClearingSubmissionId":"submission-1","settlementObligationId":"obligation-1","occurredAt":"2026-07-20T00:00:07Z"}],
      "novations":[{"settlementNovationId":"novation-1","settlementClearingAcceptanceId":"acceptance-1","settlementObligationId":"obligation-1","occurredAt":"2026-07-20T00:00:08Z"}],
      "instructions":[{"settlementInstructionId":"instruction-1","settlementObligationId":"obligation-1","occurredAt":"2026-07-20T00:00:09Z"}],
      "attempts":[{"settlementAttemptId":"attempt-1","settlementObligationId":"obligation-1","settlementInstructionId":"instruction-1","occurredAt":"2026-07-20T00:00:10Z"}],
      "legOutcomes":[{"settlementLegOutcomeId":"leg-1","settlementObligationId":"obligation-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","legType":"CASH","occurredAt":"2026-07-20T00:00:11Z"}],
      "ledgerEntries":[{"ledgerEntryId":"ledger-1","settlementObligationId":"obligation-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","participantId":"buyer","accountId":"buyer-account","assetType":"CASH","assetId":"USD","direction":"DEBIT","quantity":"1000","occurredAt":"2026-07-20T00:00:12Z"}],
      "settlements":[{"settlementId":"settlement-1","settlementObligationId":"obligation-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","occurredAt":"2026-07-20T00:00:13Z"}],
      "breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obligation-1","occurredAt":"2026-07-20T00:00:14Z"}],
      "repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obligation-1","actorId":"operator-1","occurredAt":"2026-07-20T00:00:15Z"}],
      "resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obligation-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","occurredAt":"2026-07-20T00:00:16Z"}],
      "operatorActions":[{"settlementOperatorActionId":"operator-action-1","action":"FORCE_SETTLE","targetId":"obligation-1","reasonNote":"manual recovery","actorId":"operator-1","occurredAt":"2026-07-20T00:00:17Z"}]
    }
""".trimIndent()
