package com.reef.platform.application.settlement

import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.infrastructure.persistence.RuntimePersistence

enum class PostTradeProfileSelectionSource {
    ScenarioRun,
    VenueSession,
    PlatformDefault,
    EnvironmentDefault,
    HardDefault
}

data class PostTradeProfileSelection(
    val profileId: String,
    val policyVersion: Int,
    val mode: String,
    val source: PostTradeProfileSelectionSource
)

class PostTradeProfileResolver(
    private val profiles: () -> List<PostTradeProfile> = { BuiltInPostTradeProfiles },
    private val activePlatformProfile: () -> PostTradeProfile? = { null },
    private val environmentProfileId: () -> String = { "" },
    private val environmentPolicyVersion: () -> Int = { DefaultPostTradePolicyVersion }
) {
    fun resolve(
        scenarioRunProfileId: String = "",
        venueSessionProfileId: String = ""
    ): PostTradeProfileSelection {
        val byId = (BuiltInPostTradeProfiles + profiles()).associateBy { it.profileId }
        profileSelection(scenarioRunProfileId, PostTradeProfileSelectionSource.ScenarioRun, byId)?.let { return it }
        profileSelection(venueSessionProfileId, PostTradeProfileSelectionSource.VenueSession, byId)?.let { return it }

        val platformProfile = activePlatformProfile()
        if (platformProfile != null) {
            return PostTradeProfileSelection(
                profileId = platformProfile.profileId,
                policyVersion = platformProfile.policyVersion,
                mode = platformProfile.mode,
                source = PostTradeProfileSelectionSource.PlatformDefault
            )
        }

        val envProfileId = environmentProfileId().trim()
        if (envProfileId.isNotBlank()) {
            val envProfile = byId[envProfileId]
            return PostTradeProfileSelection(
                profileId = envProfile?.profileId ?: envProfileId,
                policyVersion = environmentPolicyVersion().coerceAtLeast(1),
                mode = envProfile?.mode.orEmpty(),
                source = PostTradeProfileSelectionSource.EnvironmentDefault
            )
        }

        return PostTradeProfileSelection(
            profileId = DefaultPostTradeProfileId,
            policyVersion = DefaultPostTradePolicyVersion,
            mode = "ops-realistic",
            source = PostTradeProfileSelectionSource.HardDefault
        )
    }

    private fun profileSelection(
        profileId: String,
        source: PostTradeProfileSelectionSource,
        byId: Map<String, PostTradeProfile>
    ): PostTradeProfileSelection? {
        val normalized = profileId.trim()
        if (normalized.isBlank()) return null
        val profile = byId[normalized]
            ?: throw IllegalArgumentException("unknown post-trade profile '$normalized'")
        return PostTradeProfileSelection(
            profileId = profile.profileId,
            policyVersion = profile.policyVersion,
            mode = profile.mode,
            source = source
        )
    }

    companion object {
        fun fromPersistence(
            runtimePersistence: RuntimePersistence,
            environmentProfileId: () -> String = { "" },
            environmentPolicyVersion: () -> Int = { DefaultPostTradePolicyVersion }
        ): PostTradeProfileResolver {
            return PostTradeProfileResolver(
                profiles = { runtimePersistence.postTradeProfiles() },
                activePlatformProfile = {
                    try {
                        runtimePersistence.activePostTradeProfile()
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                },
                environmentProfileId = environmentProfileId,
                environmentPolicyVersion = environmentPolicyVersion
            )
        }

        fun envOnly(
            profileId: String = "",
            policyVersion: Int = DefaultPostTradePolicyVersion
        ): PostTradeProfileResolver {
            return PostTradeProfileResolver(
                environmentProfileId = { profileId },
                environmentPolicyVersion = { policyVersion }
            )
        }
    }
}

private val BuiltInPostTradeProfiles = listOf(
    PostTradeProfile(
        profileId = "ops-realistic-v1",
        mode = "ops-realistic",
        settlementCycle = "T+1",
        nettingMode = "batch-netting",
        ledgerPostingMode = "scheduled-finality",
        policyVersion = 1,
        active = true
    ),
    PostTradeProfile(
        profileId = "instant-post-trade-v1",
        mode = "instant-post-trade",
        settlementCycle = "T+0",
        nettingMode = "gross-or-microbatch",
        ledgerPostingMode = "near-instant-finality",
        policyVersion = 1,
        active = false
    )
)
