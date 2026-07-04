package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoopRuntimePersistenceTest {
    @Test
    fun keepsReferenceDataForBenchmarkValidation() {
        val persistence = NoopRuntimePersistence()

        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
        persistence.saveRole(RoleDefinition("order_trader", listOf("order.submit")))
        persistence.saveActorRoleBinding(ActorRoleBinding("actor-1", "order_trader"))

        val validation = persistence.validateReferenceData("AAPL", "participant-1", "account-1")
        assertTrue(validation.instrumentExists)
        assertTrue(validation.participantExists)
        assertTrue(validation.accountExists)
        assertTrue(validation.accountBelongsToParticipant)
        assertEquals(listOf("order_trader"), persistence.actorRoleBindings("actor-1").map { it.roleId })
    }

    @Test
    fun dropsSubmitOutcomesAndLifecycleFacts() {
        val persistence = NoopRuntimePersistence()
        val result = SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-1",
                orderId = "ord-1",
                engineOrderId = "eng-ord-1",
                occurredAt = "2026-07-03T00:00:00Z"
            )
        )

        persistence.persistSubmitOutcome(
            commandId = "cmd-1",
            result = result,
            acceptedOrder = PersistedOrder(
                orderId = "ord-1",
                engineOrderId = "eng-ord-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150000000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-03T00:00:00Z"
            ),
            lifecycleEvents = listOf(
                RuntimeEvent(
                    eventId = "evt-1",
                    eventType = "OrderAccepted",
                    orderId = "ord-1",
                    traceId = "trace-1",
                    causationId = "cmd-1",
                    correlationId = "corr-1",
                    producer = "test",
                    schemaVersion = "v1",
                    occurredAt = "2026-07-03T00:00:00Z"
                )
            )
        )

        assertNull(persistence.submitResult("cmd-1"))
        assertNull(persistence.acceptedOrder("ord-1"))
        assertTrue(persistence.acceptedOrders().isEmpty())
        assertTrue(persistence.eventsForTrace("trace-1").isEmpty())
        assertTrue(persistence.trades().isEmpty())
    }

    @Test
    fun reportsNoProjectionLag() {
        val status = NoopRuntimePersistence().projectionStatus("noop-projection")

        assertEquals("noop-projection", status.projectionName)
        assertEquals(0, status.projectedCount)
        assertEquals(0, status.lag)
        assertTrue(status.watermarks.isEmpty())
    }

    @Test
    fun detectsReferenceMismatch() {
        val persistence = NoopRuntimePersistence()
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "other-participant"))

        val validation = persistence.validateReferenceData("AAPL", "participant-1", "account-1")
        assertTrue(validation.instrumentExists)
        assertTrue(validation.participantExists)
        assertTrue(validation.accountExists)
        assertFalse(validation.accountBelongsToParticipant)
    }
}
