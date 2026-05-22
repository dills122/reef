package com.reef.platform.application.admin

import com.reef.platform.domain.Account
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.PostgresRuntimePersistence
import com.reef.platform.infrastructure.persistence.RuntimePersistence
import java.time.Instant
import java.util.UUID

data class AdminActor(
    val actorId: String,
    val correlationId: String = "",
    val occurredAt: String = Instant.now().toString()
)

data class UpsertInstrumentCommand(
    val instrumentId: String,
    val symbol: String
)

data class UpsertParticipantCommand(
    val participantId: String,
    val name: String
)

data class UpsertAccountCommand(
    val accountId: String,
    val participantId: String
)

class AdminApplicationService(
    private val runtimePersistence: RuntimePersistence = defaultRuntimePersistence()
) {
    private val eventProducer = "platform-runtime-admin"
    private val eventSchemaVersion = "v1"

    fun upsertInstrument(actor: AdminActor, command: UpsertInstrumentCommand) {
        runtimePersistence.saveInstrument(Instrument(command.instrumentId, command.symbol))
        emitAudit(actor, "AdminInstrumentUpserted", command.instrumentId, "symbol=${command.symbol}")
    }

    fun upsertParticipant(actor: AdminActor, command: UpsertParticipantCommand) {
        runtimePersistence.saveParticipant(Participant(command.participantId, command.name))
        emitAudit(actor, "AdminParticipantUpserted", command.participantId, "name=${command.name}")
    }

    fun upsertAccount(actor: AdminActor, command: UpsertAccountCommand) {
        runtimePersistence.saveAccount(Account(command.accountId, command.participantId))
        emitAudit(actor, "AdminAccountUpserted", command.accountId, "participantId=${command.participantId}")
    }

    fun listInstruments(): List<Instrument> = runtimePersistence.instruments()

    fun listParticipants(): List<Participant> = runtimePersistence.participants()

    fun listAccounts(): List<Account> = runtimePersistence.accounts()

    fun recentEvents(limit: Int): List<RuntimeEvent> = runtimePersistence.recentEvents(limit)

    fun traceEvents(traceId: String): List<RuntimeEvent> = runtimePersistence.eventsForTrace(traceId)

    private fun emitAudit(actor: AdminActor, eventType: String, targetId: String, detail: String) {
        runtimePersistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-admin-${UUID.randomUUID()}",
                eventType = eventType,
                orderId = targetId,
                traceId = "admin:${actor.actorId}",
                causationId = detail,
                correlationId = actor.correlationId,
                producer = eventProducer,
                schemaVersion = eventSchemaVersion,
                occurredAt = actor.occurredAt
            )
        )
    }
}

private fun defaultRuntimePersistence(): RuntimePersistence {
    val persistence = System.getenv("RUNTIME_PERSISTENCE") ?: "inmemory"
    if (persistence != "postgres") {
        return InMemoryRuntimePersistence()
    }

    val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val user = System.getenv("RUNTIME_POSTGRES_USER") ?: "reef"
    val password = System.getenv("RUNTIME_POSTGRES_PASSWORD") ?: "reef"
    return PostgresRuntimePersistence(jdbcUrl, user, password)
}
