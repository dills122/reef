package com.reef.platform.application.settlement

import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostTradeProfileResolverTest {
    @Test
    fun resolvesScenarioRunBeforeVenueAndPlatformDefaults() {
        val persistence = seededPersistence(activeProfileId = "ops-realistic-v1")
        val resolver = PostTradeProfileResolver.fromPersistence(persistence)

        val selection = resolver.resolve(
            scenarioRunProfileId = "scenario-fast-v1",
            venueSessionProfileId = "venue-t1-v1"
        )

        assertEquals("scenario-fast-v1", selection.profileId)
        assertEquals(3, selection.policyVersion)
        assertEquals("instant-post-trade", selection.mode)
        assertEquals(PostTradeProfileSelectionSource.ScenarioRun, selection.source)
    }

    @Test
    fun resolvesVenueBeforePlatformDefault() {
        val persistence = seededPersistence(activeProfileId = "ops-realistic-v1")
        val resolver = PostTradeProfileResolver.fromPersistence(persistence)

        val selection = resolver.resolve(venueSessionProfileId = "venue-t1-v1")

        assertEquals("venue-t1-v1", selection.profileId)
        assertEquals(2, selection.policyVersion)
        assertEquals("ops-realistic", selection.mode)
        assertEquals(PostTradeProfileSelectionSource.VenueSession, selection.source)
    }

    @Test
    fun resolvesPersistedPlatformDefaultBeforeEnvironmentDefault() {
        val persistence = seededPersistence(activeProfileId = "instant-post-trade-v1")
        val resolver = PostTradeProfileResolver.fromPersistence(
            persistence,
            environmentProfileId = { "ops-realistic-v1" }
        )

        val selection = resolver.resolve()

        assertEquals("instant-post-trade-v1", selection.profileId)
        assertEquals(1, selection.policyVersion)
        assertEquals("instant-post-trade", selection.mode)
        assertEquals(PostTradeProfileSelectionSource.PlatformDefault, selection.source)
    }

    @Test
    fun resolvesEnvironmentDefaultBeforeHardDefault() {
        val resolver = PostTradeProfileResolver(
            environmentProfileId = { "env-profile-v1" },
            environmentPolicyVersion = { 7 }
        )

        val selection = resolver.resolve()

        assertEquals("env-profile-v1", selection.profileId)
        assertEquals(7, selection.policyVersion)
        assertEquals("", selection.mode)
        assertEquals(PostTradeProfileSelectionSource.EnvironmentDefault, selection.source)
    }

    @Test
    fun environmentDefaultKeepsKnownProfileMode() {
        val resolver = PostTradeProfileResolver(
            environmentProfileId = { "instant-post-trade-v1" },
            environmentPolicyVersion = { 5 }
        )

        val selection = resolver.resolve()

        assertEquals("instant-post-trade-v1", selection.profileId)
        assertEquals(5, selection.policyVersion)
        assertEquals("instant-post-trade", selection.mode)
        assertEquals(PostTradeProfileSelectionSource.EnvironmentDefault, selection.source)
    }

    @Test
    fun resolvesHardDefaultWhenNothingConfigured() {
        val selection = PostTradeProfileResolver().resolve()

        assertEquals(DefaultPostTradeProfileId, selection.profileId)
        assertEquals(DefaultPostTradePolicyVersion, selection.policyVersion)
        assertEquals("ops-realistic", selection.mode)
        assertEquals(PostTradeProfileSelectionSource.HardDefault, selection.source)
    }

    @Test
    fun rejectsUnknownExplicitOverride() {
        val persistence = seededPersistence(activeProfileId = "ops-realistic-v1")
        val resolver = PostTradeProfileResolver.fromPersistence(persistence)

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(scenarioRunProfileId = "missing-profile")
        }
    }

    private fun seededPersistence(activeProfileId: String): InMemoryRuntimePersistence {
        val persistence = InMemoryRuntimePersistence()
        profiles.forEach { profile -> persistence.savePostTradeProfile(profile.copy(active = profile.profileId == activeProfileId)) }
        return persistence
    }

    private val profiles = listOf(
        PostTradeProfile(
            profileId = "ops-realistic-v1",
            mode = "ops-realistic",
            settlementCycle = "T+1",
            nettingMode = "batch-netting",
            ledgerPostingMode = "scheduled-finality"
        ),
        PostTradeProfile(
            profileId = "instant-post-trade-v1",
            mode = "instant-post-trade",
            settlementCycle = "T+0",
            nettingMode = "gross-or-microbatch",
            ledgerPostingMode = "near-instant-finality"
        ),
        PostTradeProfile(
            profileId = "venue-t1-v1",
            mode = "ops-realistic",
            settlementCycle = "T+1",
            nettingMode = "batch-netting",
            ledgerPostingMode = "scheduled-finality",
            policyVersion = 2
        ),
        PostTradeProfile(
            profileId = "scenario-fast-v1",
            mode = "instant-post-trade",
            settlementCycle = "T+0",
            nettingMode = "gross-or-microbatch",
            ledgerPostingMode = "near-instant-finality",
            policyVersion = 3
        )
    )
}
