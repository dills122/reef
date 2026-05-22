package com.reef.platform.application.admin

import com.reef.platform.domain.Account
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
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

class AuthorizationException(message: String) : RuntimeException(message)

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

    init {
        runtimePersistence.saveRole(
            RoleDefinition(
                roleId = "system_admin",
                permissions = listOf(Permission.SUPERUSER)
            )
        )
        runtimePersistence.saveActorRoleBinding(
            ActorRoleBinding(
                actorId = System.getenv("ADMIN_ACTOR_ID") ?: "admin-cli",
                roleId = "system_admin"
            )
        )
    }

    fun upsertInstrument(actor: AdminActor, command: UpsertInstrumentCommand) {
        requirePermission(actor, Permission.REFERENCE_WRITE)
        runtimePersistence.saveInstrument(Instrument(command.instrumentId, command.symbol))
        emitAudit(actor, "AdminInstrumentUpserted", command.instrumentId, "symbol=${command.symbol}")
    }

    fun upsertParticipant(actor: AdminActor, command: UpsertParticipantCommand) {
        requirePermission(actor, Permission.REFERENCE_WRITE)
        runtimePersistence.saveParticipant(Participant(command.participantId, command.name))
        emitAudit(actor, "AdminParticipantUpserted", command.participantId, "name=${command.name}")
    }

    fun upsertAccount(actor: AdminActor, command: UpsertAccountCommand) {
        requirePermission(actor, Permission.REFERENCE_WRITE)
        runtimePersistence.saveAccount(Account(command.accountId, command.participantId))
        emitAudit(actor, "AdminAccountUpserted", command.accountId, "participantId=${command.participantId}")
    }

    fun upsertRole(actor: AdminActor, roleId: String, permissionsCsv: String) {
        requirePermission(actor, Permission.AUTH_ADMIN)
        val permissions = permissionsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        runtimePersistence.saveRole(RoleDefinition(roleId, permissions))
        emitAudit(actor, "AdminRoleUpserted", roleId, "permissions=${permissions.joinToString(",")}")
    }

    fun assignRole(actor: AdminActor, targetActorId: String, roleId: String) {
        requirePermission(actor, Permission.AUTH_ADMIN)
        runtimePersistence.saveActorRoleBinding(ActorRoleBinding(targetActorId, roleId))
        emitAudit(actor, "AdminRoleAssigned", targetActorId, "roleId=$roleId")
    }

    fun listInstruments(): List<Instrument> = runtimePersistence.instruments()

    fun listParticipants(): List<Participant> = runtimePersistence.participants()

    fun listAccounts(): List<Account> = runtimePersistence.accounts()

    fun listRoles(): List<RoleDefinition> = runtimePersistence.roles()

    fun listActorRoles(actorId: String): List<ActorRoleBinding> = runtimePersistence.actorRoleBindings(actorId)

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

    private fun requirePermission(actor: AdminActor, permission: String) {
        val boundRoleIds = runtimePersistence.actorRoleBindings(actor.actorId).map { it.roleId }.toSet()
        val allowed = runtimePersistence.roles()
            .filter { it.roleId in boundRoleIds }
            .flatMap { it.permissions }
            .toSet()
        if (permission !in allowed && Permission.SUPERUSER !in allowed) {
            throw AuthorizationException("actor ${actor.actorId} missing permission $permission")
        }
    }
}

object Permission {
    const val SUPERUSER = "admin.superuser"
    const val REFERENCE_WRITE = "reference.write"
    const val AUTH_ADMIN = "auth.admin"
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
