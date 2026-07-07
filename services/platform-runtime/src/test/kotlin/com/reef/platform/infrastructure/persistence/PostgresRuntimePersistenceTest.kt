package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresRuntimePersistenceTest {
    @Test
    fun storesAndQueriesAcceptedArtifacts() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "runtime-persistence-test")
        val persistence = PostgresRuntimePersistence(dataSource)

        val suffix = UUID.randomUUID().toString()
        val commandId = "cmd-$suffix"
        val instrumentId = "INSTR-$suffix"
        val participantId = "participant-$suffix"
        val accountId = "account-$suffix"
        val orderId = "ord-$suffix"
        val traceId = "trace-$suffix"

        persistence.saveSubmitResult(commandId, SubmitOrderResult())
        persistence.saveInstrument(Instrument(instrumentId, instrumentId))
        persistence.saveParticipant(Participant(participantId, "Participant $suffix"))
        persistence.saveAccount(Account(accountId, participantId))

        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = orderId,
                engineOrderId = "eng-$orderId",
                instrumentId = instrumentId,
                participantId = participantId,
                accountId = accountId,
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-07T00:00:00Z"
            )
        )
        persistence.saveExecutions(
            listOf(
                ExecutionCreated(
                    eventId = "evt-exec-$suffix",
                    executionId = "exec-$suffix",
                    orderId = orderId,
                    instrumentId = instrumentId,
                    quantityUnits = "100",
                    executionPrice = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-07-07T00:00:00Z"
                )
            )
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-$suffix",
                    tradeId = "trade-$suffix",
                    executionId = "exec-$suffix",
                    buyOrderId = orderId,
                    sellOrderId = "ord-counterparty-$suffix",
                    instrumentId = instrumentId,
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-07-07T00:00:00Z"
                )
            )
        )
        persistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-order-accepted-$suffix",
                eventType = "OrderAccepted",
                orderId = orderId,
                traceId = traceId,
                causationId = commandId,
                correlationId = "corr-$suffix",
                actorId = "trader-$suffix",
                producer = "platform-runtime",
                schemaVersion = "v1",
                payloadJson = """{"source":"postgres-runtime-persistence-test"}""",
                occurredAt = "2026-07-07T00:00:00Z"
            )
        )

        assertNotNull(persistence.submitResult(commandId))
        assertEquals(true, persistence.hasInstrument(instrumentId))
        assertEquals(true, persistence.hasParticipant(participantId))
        assertEquals(true, persistence.hasAccount(accountId))
        assertEquals(false, persistence.hasInstrument("does-not-exist-$suffix"))

        val validation = persistence.validateReferenceData(instrumentId, participantId, accountId)
        assertEquals(true, validation.instrumentExists)
        assertEquals(true, validation.participantExists)
        assertEquals(true, validation.accountExists)
        assertEquals(true, validation.accountBelongsToParticipant)

        assertNotNull(persistence.acceptedOrder(orderId))
        assertEquals(1, persistence.executionsForOrder(orderId).size)
        assertEquals(1, persistence.tradesForOrder(orderId).size)
        assertEquals(1, persistence.eventsForOrder(orderId).size)
        assertEquals(1, persistence.eventsForTrace(traceId).size)

        val recentTrades = persistence.recentTrades(50)
        assertTrue(recentTrades.any { it.tradeId == "trade-$suffix" })

        val recentEvents = persistence.recentEvents(50)
        assertTrue(recentEvents.any { it.eventId == "evt-order-accepted-$suffix" })

        val event = persistence.eventsForTrace(traceId).first()
        assertEquals("trader-$suffix", event.actorId)
        assertTrue(event.payloadJson.contains("postgres-runtime-persistence-test"))
    }

    @Test
    fun acceptedOrderReturnsNullForUnknownOrder() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "runtime-persistence-test")
        val persistence = PostgresRuntimePersistence(dataSource)

        assertNull(persistence.acceptedOrder("order-that-does-not-exist-${UUID.randomUUID()}"))
        assertEquals(false, persistence.hasAccount("account-that-does-not-exist-${UUID.randomUUID()}"))
    }

    @Test
    fun validateReferenceDataReportsMissingEntities() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "runtime-persistence-test")
        val persistence = PostgresRuntimePersistence(dataSource)

        val suffix = UUID.randomUUID().toString()
        val validation = persistence.validateReferenceData(
            "missing-instrument-$suffix",
            "missing-participant-$suffix",
            "missing-account-$suffix"
        )
        assertEquals(false, validation.instrumentExists)
        assertEquals(false, validation.participantExists)
        assertEquals(false, validation.accountExists)
    }

    @Test
    fun orderLifecycleStateRebuildsFromAcceptedAndFilledOrders() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "runtime-persistence-test")
        val persistence = PostgresRuntimePersistence(dataSource)

        val suffix = UUID.randomUUID().toString()
        val instrumentId = "INSTR-$suffix"
        val orderId = "ord-$suffix"

        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = orderId,
                engineOrderId = "eng-$orderId",
                instrumentId = instrumentId,
                participantId = "participant-$suffix",
                accountId = "account-$suffix",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-07T00:00:00Z"
            )
        )

        persistence.rebuildOrderLifecycleState()
        val state = persistence.orderLifecycleState(orderId)
        assertNotNull(state)
        assertEquals(orderId, state.orderId)
    }
}
