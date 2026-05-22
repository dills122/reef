package com.reef.platform.application.admin

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
    fun deniesReferenceWriteWhenActorHasNoPermission() {
        val persistence = InMemoryRuntimePersistence()
        val service = AdminApplicationService(persistence)
        val actor = AdminActor(actorId = "trader-1", correlationId = "corr-2", occurredAt = "2026-05-22T00:00:00Z")

        assertFailsWith<AuthorizationException> {
            service.upsertInstrument(actor, UpsertInstrumentCommand("MSFT", "MSFT"))
        }
    }
}
