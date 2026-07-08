package com.reef.platform.application.admin

import com.reef.platform.api.AccountRiskControl
import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.application.arena.ArenaBotMetadata
import com.reef.platform.application.arena.ArenaBotVersionStatus
import com.reef.platform.application.arena.ArenaControlPlaneService
import com.reef.platform.application.arena.InMemoryArenaBotRegistryStore
import com.reef.platform.application.arena.RegisterArenaBotCommand
import com.reef.platform.application.arena.RegisterArenaBotVersionCommand
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminApplicationServiceTest {
    @Test
    fun upsertReferenceDataAndEmitAuditEvents() {
        val persistence = InMemoryRuntimePersistence()
        val service = AdminApplicationService(persistence)
        val actor = AdminActor(actorId = "admin-cli", correlationId = "corr-1", occurredAt = "2026-05-22T00:00:00Z")

        service.upsertInstrument(actor, UpsertInstrumentCommand("AAPL", "AAPL"))
        service.upsertParticipant(actor, UpsertParticipantCommand("participant-1", "Participant 1"))
        service.upsertAccount(actor, UpsertAccountCommand("account-1", "participant-1"))

        assertEquals(1, service.listInstruments().size)
        assertEquals(1, service.listParticipants().size)
        assertEquals(1, service.listAccounts().size)

        val events = service.traceEvents("admin:admin-cli")
        assertEquals(3, events.size)
        assertTrue(events.all { it.eventType.startsWith("Admin") })
    }

    @Test
    fun managesCalendarOverrideAndSimulationControls() {
        val persistence = InMemoryRuntimePersistence()
        val service = AdminApplicationService(persistence)
        val actor = AdminActor(actorId = "admin-cli", correlationId = "corr-3", occurredAt = "2026-05-22T00:00:00Z")

        service.upsertCalendarProfile(actor, CalendarProfile("us-default", "America/New_York", "T+1"))
        service.upsertOverrideReason(actor, OverrideReasonCode("MANUAL_REPAIR", "manual operational repair"))
        service.startSimulation(actor, "scenario-1")
        service.pauseSimulation(actor)
        service.stopSimulation(actor)

        assertEquals(1, service.listCalendarProfiles().size)
        assertEquals(1, service.listOverrideReasons().size)
        assertEquals("stopped", service.simulationState().status)
    }

    @Test
    fun persistsPostTradeProfileActivationInRuntimePersistence() {
        val persistence = InMemoryRuntimePersistence()
        val service = AdminApplicationService(persistence)
        val actor = AdminActor(actorId = "admin-cli", correlationId = "corr-profile", occurredAt = "2026-05-22T00:00:00Z")

        service.upsertPostTradeProfile(
            actor,
            PostTradeProfile(
                profileId = "custom-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality"
            )
        )
        service.activatePostTradeProfile(actor, "custom-instant-v1")

        val restartedService = AdminApplicationService(persistence)

        assertEquals("custom-instant-v1", restartedService.activePostTradeProfile().profileId)
        assertEquals(3, restartedService.listPostTradeProfiles().size)
    }

    @Test
    fun deniesReferenceWriteWhenActorHasNoPermission() {
        val persistence = InMemoryRuntimePersistence()
        val service = AdminApplicationService(persistence)
        val actor = AdminActor(actorId = "trader-1", correlationId = "corr-2", occurredAt = "2026-05-22T00:00:00Z")

        assertFailsWith<AuthorizationException> {
            service.upsertInstrument(actor, UpsertInstrumentCommand("MSFT", "MSFT"))
        }
    }

    @Test
    fun transitionsArenaBotVersionAndSyncsRiskControl() {
        val persistence = InMemoryRuntimePersistence()
        val arenaStore = seededArenaStore()
        val riskStore = RecordingAccountRiskControlStore()
        val service = AdminApplicationService(
            runtimePersistence = persistence,
            arenaRegistryStore = arenaStore,
            accountRiskControlStore = riskStore
        )
        val actor = AdminActor(actorId = "admin-cli", correlationId = "corr-arena", occurredAt = "2026-05-22T00:00:00Z")

        service.transitionArenaBotVersion(
            actor,
            ArenaBotVersionDecisionCommand(
                botId = "bot-1",
                versionId = "v1",
                status = ArenaBotVersionStatus.Submitted,
                reason = "submitted"
            )
        )
        service.transitionArenaBotVersion(
            actor,
            ArenaBotVersionDecisionCommand(
                botId = "bot-1",
                versionId = "v1",
                status = ArenaBotVersionStatus.ChecksPassed,
                reason = "checks passed"
            )
        )
        service.transitionArenaBotVersion(
            actor,
            ArenaBotVersionDecisionCommand(
                botId = "bot-1",
                versionId = "v1",
                status = ArenaBotVersionStatus.Approved,
                reason = "approved"
            )
        )
        val quarantined = service.transitionArenaBotVersion(
            actor,
            ArenaBotVersionDecisionCommand(
                botId = "bot-1",
                versionId = "v1",
                status = ArenaBotVersionStatus.Quarantined,
                reason = "scanner regression"
            )
        )

        assertEquals(ArenaBotVersionStatus.Quarantined, quarantined.status)
        assertEquals(AccountRiskDecision.DISABLED_BOT, riskStore.listControls().single().decision)
        assertEquals("AdminArenaBotVersionTransitioned", service.traceEvents("admin:admin-cli").last().eventType)
    }

    private fun seededArenaStore(): InMemoryArenaBotRegistryStore {
        val store = InMemoryArenaBotRegistryStore()
        val service = ArenaControlPlaneService(store) { java.time.Instant.parse("2026-05-22T00:00:00Z") }
        service.registerBot(
            RegisterArenaBotCommand(
                botId = "bot-1",
                fileName = "bot-1.ts",
                metadata = ArenaBotMetadata(
                    name = "Bot 1",
                    publisher = "Publisher",
                    email = "publisher@example.com"
                )
            )
        )
        service.registerVersion(
            RegisterArenaBotVersionCommand(
                botId = "bot-1",
                versionId = "v1",
                sourceHash = "sha256:source",
                artifactHash = "sha256:artifact",
                sdkVersion = "1.5.0",
                apiVersion = "v1",
                dependencyManifestHash = "sha256:deps"
            )
        )
        return store
    }
}

private class RecordingAccountRiskControlStore : AccountRiskControlStore {
    private val controls = linkedMapOf<String, AccountRiskControl>()

    override fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String,
        maxQuantityUnits: String,
        maxNotional: String,
        currency: String
    ) {
        controls["$scopeType|$scopeId"] = AccountRiskControl(
            scopeType = scopeType,
            scopeId = scopeId,
            decision = decision,
            reason = reason,
            maxQuantityUnits = maxQuantityUnits,
            maxNotional = maxNotional,
            currency = currency
        )
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()
}
