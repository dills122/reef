package com.reef.platform.application.admin

import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminApplicationServiceTest {
    @Test
    fun upsertReferenceDataAndEmitAuditEvents() {
        val persistence = InMemoryRuntimePersistence()
        val service = AdminApplicationService(persistence)
        val actor = AdminActor(actorId = "ops-1", correlationId = "corr-1", occurredAt = "2026-05-22T00:00:00Z")

        service.upsertInstrument(actor, UpsertInstrumentCommand("AAPL", "AAPL"))
        service.upsertParticipant(actor, UpsertParticipantCommand("participant-1", "Participant 1"))
        service.upsertAccount(actor, UpsertAccountCommand("account-1", "participant-1"))

        assertEquals(1, service.listInstruments().size)
        assertEquals(1, service.listParticipants().size)
        assertEquals(1, service.listAccounts().size)

        val events = service.traceEvents("admin:ops-1")
        assertEquals(3, events.size)
        assertTrue(events.all { it.eventType.startsWith("Admin") })
    }
}
