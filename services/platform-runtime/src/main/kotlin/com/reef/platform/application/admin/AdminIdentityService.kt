package com.reef.platform.application.admin

import java.time.Instant
import java.util.UUID

data class GitHubUserIdentity(
    val githubUserId: Long,
    val githubLogin: String,
    val displayName: String = ""
)

data class AdminBotLimitCommand(
    val reefUserId: String,
    val maxBots: Int,
    val maxActiveBots: Int,
    val maxVersionSubmissionsPerDay: Int
)

data class AdminBotOwnershipCommand(
    val reefUserId: String,
    val botId: String,
    val ownershipState: AdminBotOwnershipState = AdminBotOwnershipState.Owner
)

data class AdminBotOwnerMetadata(
    val reefUserId: String,
    val githubLogin: String,
    val displayName: String,
    val trustState: AdminTrustState,
    val ownershipState: AdminBotOwnershipState,
    val assignedAt: Instant
)

class AdminIdentityService(
    private val store: AdminIdentityStore,
    private val now: () -> Instant = { Instant.now() },
    private val eventIdFactory: () -> String = { "evt-admin-identity-${UUID.randomUUID()}" }
) {
    init {
        seedBaselineRoles()
    }

    fun ensureGitHubUser(identity: GitHubUserIdentity, actorId: String = "github-oauth"): AdminUser {
        val githubUserId = AdminIdentityValidation.requireGitHubUserId(identity.githubUserId)
        val login = AdminIdentityValidation.githubLogin(identity.githubLogin)
        val displayName = AdminIdentityValidation.displayName(identity.displayName)
        val actor = AdminIdentityValidation.actorId(actorId)
        val seenAt = now()
        val existing = store.userByGithubUserId(githubUserId)
        if (existing != null) {
            val updated = existing.copy(
                githubLogin = login,
                displayName = displayName.ifBlank { existing.displayName },
                lastSeenAt = seenAt,
                updatedAt = seenAt
            )
            store.saveUser(updated)
            audit(actor, "AdminUserSeen", "admin-user", updated.reefUserId, "githubLogin=$login")
            return updated
        }

        val created = AdminUser(
            reefUserId = reefUserId(githubUserId),
            githubUserId = githubUserId,
            githubLogin = login,
            displayName = displayName,
            trustState = AdminTrustState.New,
            createdAt = seenAt,
            lastSeenAt = seenAt,
            updatedAt = seenAt
        )
        store.saveUser(created)
        store.assignRole(
            AdminUserRole(
                reefUserId = created.reefUserId,
                roleId = RoleParticipant,
                assignedBy = actor,
                assignedAt = seenAt
            )
        )
        store.saveUserBotLimit(
            AdminUserBotLimit(
                reefUserId = created.reefUserId,
                maxBots = DefaultMaxBots,
                maxActiveBots = DefaultMaxActiveBots,
                maxVersionSubmissionsPerDay = DefaultMaxVersionSubmissionsPerDay,
                updatedBy = actor,
                updatedAt = seenAt
            )
        )
        audit(actor, "AdminUserCreated", "admin-user", created.reefUserId, "githubLogin=$login")
        return created
    }

    fun updateTrustState(actorId: String, reefUserId: String, trustState: AdminTrustState): AdminUser {
        val actor = AdminIdentityValidation.actorId(actorId)
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        val existing = store.userByReefUserId(userId) ?: throw IllegalArgumentException("Unknown admin user: $userId")
        val updatedAt = now()
        val updated = existing.copy(trustState = trustState, updatedAt = updatedAt)
        store.saveUser(updated)
        audit(actor, "AdminUserTrustStateUpdated", "admin-user", userId, "trustState=${trustState.dbValue}")
        return updated
    }

    fun assignRole(actorId: String, reefUserId: String, roleId: String): AdminUserRole {
        val actor = AdminIdentityValidation.actorId(actorId)
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        val role = AdminIdentityValidation.roleId(roleId)
        require(store.role(role) != null) { "Unknown admin role: $role" }
        require(store.userByReefUserId(userId) != null) { "Unknown admin user: $userId" }
        val binding = AdminUserRole(reefUserId = userId, roleId = role, assignedBy = actor, assignedAt = now())
        store.assignRole(binding)
        audit(actor, "AdminUserRoleAssigned", "admin-user", userId, "roleId=$role")
        return binding
    }

    fun updateBotLimits(actorId: String, command: AdminBotLimitCommand): AdminUserBotLimit {
        val actor = AdminIdentityValidation.actorId(actorId)
        val userId = AdminIdentityValidation.reefUserId(command.reefUserId)
        require(store.userByReefUserId(userId) != null) { "Unknown admin user: $userId" }
        val limit = AdminUserBotLimit(
            reefUserId = userId,
            maxBots = command.maxBots,
            maxActiveBots = command.maxActiveBots,
            maxVersionSubmissionsPerDay = command.maxVersionSubmissionsPerDay,
            updatedBy = actor,
            updatedAt = now()
        )
        AdminIdentityValidation.userBotLimit(limit)
        store.saveUserBotLimit(limit)
        audit(
            actor,
            "AdminUserBotLimitsUpdated",
            "admin-user",
            userId,
            "maxBots=${command.maxBots},maxActiveBots=${command.maxActiveBots}"
        )
        return limit
    }

    fun assignBotOwnership(actorId: String, command: AdminBotOwnershipCommand): AdminUserBotOwnership {
        val actor = AdminIdentityValidation.actorId(actorId)
        val userId = AdminIdentityValidation.reefUserId(command.reefUserId)
        val bot = AdminIdentityValidation.botId(command.botId)
        require(store.userByReefUserId(userId) != null) { "Unknown admin user: $userId" }
        val ownership = AdminUserBotOwnership(
            reefUserId = userId,
            botId = bot,
            ownershipState = command.ownershipState,
            assignedBy = actor,
            assignedAt = now()
        )
        store.saveBotOwnership(ownership)
        audit(
            actor,
            "AdminUserBotOwnershipAssigned",
            "arena-bot",
            ownership.botId,
            "reefUserId=${ownership.reefUserId},state=${ownership.ownershipState.dbValue}"
        )
        return ownership
    }

    fun rolesForUser(reefUserId: String): List<AdminUserRole> = store.rolesForUser(reefUserId)

    fun botLimit(reefUserId: String): AdminUserBotLimit? = store.userBotLimit(reefUserId)

    fun user(reefUserId: String): AdminUser? = store.userByReefUserId(reefUserId)

    fun botOwnershipsForUser(reefUserId: String): List<AdminUserBotOwnership> {
        return store.botOwnershipsForUser(reefUserId)
            .filter { it.ownershipState != AdminBotOwnershipState.Revoked }
    }

    fun botOwnerMetadata(botId: String): List<AdminBotOwnerMetadata> {
        return store.botOwnerships(botId)
            .filter { it.ownershipState != AdminBotOwnershipState.Revoked }
            .mapNotNull { ownership ->
                store.userByReefUserId(ownership.reefUserId)?.let { user ->
                    AdminBotOwnerMetadata(
                        reefUserId = user.reefUserId,
                        githubLogin = user.githubLogin,
                        displayName = user.displayName,
                        trustState = user.trustState,
                        ownershipState = ownership.ownershipState,
                        assignedAt = ownership.assignedAt
                    )
                }
            }
    }

    fun recordControlPlaneAudit(actorId: String, eventType: String, targetType: String, targetId: String, detail: String) {
        audit(actorId, eventType, targetType, targetId, detail)
    }

    private fun seedBaselineRoles() {
        val createdAt = now()
        BaselineRoles.forEach { (roleId, description) ->
            store.saveRole(AdminRole(roleId = roleId, description = description, createdAt = createdAt))
        }
    }

    private fun audit(actorId: String, eventType: String, targetType: String, targetId: String, detail: String) {
        val actor = AdminIdentityValidation.actorId(actorId)
        store.appendAuditEvent(
            AdminIdentityAuditEvent(
                eventId = eventIdFactory(),
                actorId = actor,
                eventType = AdminIdentityValidation.eventType(eventType),
                targetType = AdminIdentityValidation.targetType(targetType),
                targetId = targetId,
                detail = AdminIdentityValidation.detail(detail),
                occurredAt = now()
            )
        )
    }

    companion object {
        const val RoleParticipant = "participant"
        const val RoleReviewer = "reviewer"
        const val RoleOperator = "operator"
        const val RoleSecretAdmin = "secret-admin"
        const val RolePlatformAdmin = "platform-admin"
        const val DefaultMaxBots = 3
        const val DefaultMaxActiveBots = 1
        const val DefaultMaxVersionSubmissionsPerDay = 10

        val BaselineRoles = linkedMapOf(
            RoleParticipant to "Can own accepted bots and manage own bot config",
            RoleReviewer to "Can review bot submissions",
            RoleOperator to "Can operate arena runs and game settings",
            RoleSecretAdmin to "Can perform explicit secret repair and rotation actions",
            RolePlatformAdmin to "Can administer the Reef control plane"
        )

        fun reefUserId(githubUserId: Long): String = "user-gh-$githubUserId"
    }
}
