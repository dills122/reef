package com.reef.arena.controlplane.arena

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArenaPolicyResolutionTest {
    private val verifier = ArenaRosterPolicyVerifier()

    @Test
    fun canonicalizesAndVerifiesResolvedRosterPolicies() {
        val actorCatalog = verifier.canonicalArtifact(
            ArenaResolvedPolicyKind.ActorProfileCatalog,
            "arena-actor-profiles",
            "2026-07-12",
            """{"version":"2026-07-12","profiles":[{"version":"v1","scoreEffect":"eligible-for-score","profileId":"competitor-standard","params":{"aggression":0.5},"difficultyBucket":"ranked-standard","description":"Default competitor","allowedParamKeys":["aggression"],"actorClass":"competitor"}],"catalogId":"arena-actor-profiles","schemaVersion":"reef.arena.actorProfiles.v1"}"""
        )
        val economicPolicy = verifier.canonicalArtifact(
            ArenaResolvedPolicyKind.EconomicPolicy,
            "preview-zero-fee",
            "preview-zero-fee-v1",
            """{"version":"preview-zero-fee-v1","schemaVersion":"reef.arena.economicPolicy.v1","sinks":[],"sources":[],"rebates":{"makerBps":"0","fundingSource":"none"},"reconciliation":{"tolerance":"0.01","requireBalancedTransfers":true,"competitionLedger":true,"houseLedger":true},"policyId":"preview-zero-fee","houseLedger":{"marketMakerStartingCash":"10000000.00","npcStartingCash":"10000000.00","subsidyBudget":"0.00"},"fees":{"makerBps":"0","takerBps":"0","cancelFee":"0.00","borrowBps":"0","liquidationPenaltyBps":"0"},"currency":"USD","competitionLedger":{"startingCashPerCompetitor":"1000000.00","allowNegativeCash":false}}"""
        )
        val resolved = ArenaRosterResolvedPolicies(actorCatalog, economicPolicy)
        val snapshot = ArenaRosterPolicySnapshot(
            "mode-v1", "scenario-v1", "sha256:seeds",
            actorCatalog.version, actorCatalog.contentHash,
            "risk-v1", "sha256:risk", "score-v1", "sha256:score",
            economicPolicy.version, economicPolicy.contentHash
        )

        assertTrue(actorCatalog.canonicalContent.startsWith("{\"catalogId\""))
        assertTrue(actorCatalog.contentHash.matches(Regex("^sha256:[a-f0-9]{64}$")))
        assertEquals(Unit, verifier.verify(snapshot, resolved))
        assertFailsWith<IllegalArgumentException> {
            verifier.verify(snapshot.copy(actorProfileHash = "sha256:unverified"), resolved)
        }
        assertFailsWith<IllegalArgumentException> {
            verifier.verify(
                snapshot,
                resolved.copy(economicPolicy = economicPolicy.copy(canonicalContent = economicPolicy.canonicalContent + " "))
            )
        }
    }

    @Test
    fun rejectsUnknownTopLevelFieldsAndIdentityMismatches() {
        assertFailsWith<IllegalArgumentException> {
            verifier.canonicalArtifact(
                ArenaResolvedPolicyKind.ActorProfileCatalog,
                "arena-actor-profiles",
                "v1",
                """{"schemaVersion":"reef.arena.actorProfiles.v1","catalogId":"arena-actor-profiles","version":"v1","profiles":[],"hiddenOverride":true}"""
            )
        }
        assertFailsWith<IllegalArgumentException> {
            verifier.canonicalArtifact(
                ArenaResolvedPolicyKind.EconomicPolicy,
                "preview-zero-fee",
                "v2",
                """{"schemaVersion":"reef.arena.economicPolicy.v1","policyId":"preview-zero-fee","version":"v1","currency":"USD","competitionLedger":{},"houseLedger":{},"fees":{},"rebates":{},"sources":[],"sinks":[],"reconciliation":{}}"""
            )
        }
    }
}
