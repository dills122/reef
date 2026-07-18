package com.reef.platform.application.admin

import java.time.Instant
import java.util.UUID

data class GitHubUserIdentity(
    val githubUserId: Long,
    val githubLogin: String,
    val displayName: String = ""
)

data class AdminAccessUserSummary(
    val user: AdminUser,
    val roles: List<AdminUserRole>
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

    fun updateTrustStateByOperator(
        actorId: String,
        reefUserId: String,
        trustState: AdminTrustState,
        reason: String
    ): AdminUser {
        val actor = AdminIdentityValidation.actorId(actorId)
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        val detail = requiredReason(reason)
        requireTrustStateActor(actor, trustState)
        val existing = store.userByReefUserId(userId) ?: throw IllegalArgumentException("Unknown admin user: $userId")
        val updated = existing.copy(trustState = trustState, updatedAt = now())
        store.saveUser(updated)
        audit(
            actor,
            "AdminAccessTrustStateChanged",
            "admin-user",
            updated.reefUserId,
            "trustState=${trustState.dbValue},reason=$detail"
        )
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

    fun assignRoleByOperator(actorId: String, reefUserId: String, roleId: String, reason: String): AdminUserRole {
        val actor = AdminIdentityValidation.actorId(actorId)
        val role = AdminIdentityValidation.roleId(roleId)
        val detail = requiredReason(reason)
        requireRoleActor(actor, role)
        val binding = assignRole(actor, reefUserId, role)
        audit(actor, "AdminAccessRoleAssigned", "admin-user", binding.reefUserId, "roleId=$role,reason=$detail")
        return binding
    }

    fun revokeRoleByOperator(actorId: String, reefUserId: String, roleId: String, reason: String) {
        val actor = AdminIdentityValidation.actorId(actorId)
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        val role = AdminIdentityValidation.roleId(roleId)
        val detail = requiredReason(reason)
        require(role != RoleParticipant) { "participant role cannot be revoked" }
        require(!(actor == userId && role == RolePlatformAdmin)) { "platform-admin cannot revoke itself" }
        requireRoleActor(actor, role)
        require(store.userByReefUserId(userId) != null) { "Unknown admin user: $userId" }
        store.revokeRole(userId, role)
        audit(actor, "AdminAccessRoleRevoked", "admin-user", userId, "roleId=$role,reason=$detail")
    }

    fun rolesForUser(reefUserId: String): List<AdminUserRole> = store.rolesForUser(reefUserId)

    fun accessUsers(limit: Int = 100): List<AdminAccessUserSummary> {
        return store.users(limit.coerceIn(1, 500)).map { user ->
            AdminAccessUserSummary(
                user = user,
                roles = store.rolesForUser(user.reefUserId)
            )
        }
    }

    fun roles(): List<AdminRole> = store.roles()

    fun user(reefUserId: String): AdminUser? = store.userByReefUserId(reefUserId)

    fun recordControlPlaneAudit(actorId: String, eventType: String, targetType: String, targetId: String, detail: String) {
        audit(actorId, eventType, targetType, targetId, detail)
    }

    private fun seedBaselineRoles() {
        val createdAt = now()
        BaselineRoles.forEach { (roleId, description) ->
            store.saveRole(AdminRole(roleId = roleId, description = description, createdAt = createdAt))
        }
    }

    private fun requiredReason(reason: String): String {
        val detail = AdminIdentityValidation.detail(reason.trim())
        require(detail.isNotBlank()) { "reason is required" }
        return detail
    }

    private fun requireTrustStateActor(actorId: String, trustState: AdminTrustState) {
        if (trustState == AdminTrustState.Banned) {
            requirePlatformAdmin(actorId)
            return
        }
        requireOperatorOrPlatformAdmin(actorId)
    }

    private fun requireRoleActor(actorId: String, roleId: String) {
        when (roleId) {
            RoleReviewer -> requireOperatorOrPlatformAdmin(actorId)
            RoleOperator, RoleSecretAdmin, RolePlatformAdmin -> requirePlatformAdmin(actorId)
            RoleParticipant -> requirePlatformAdmin(actorId)
            else -> throw IllegalArgumentException("Unsupported admin role: $roleId")
        }
    }

    private fun requireOperatorOrPlatformAdmin(actorId: String) {
        val roles = trustedActorRoles(actorId)
        require(RoleOperator in roles || RolePlatformAdmin in roles) {
            "trusted operator or platform-admin role required"
        }
    }

    private fun requirePlatformAdmin(actorId: String) {
        val roles = trustedActorRoles(actorId)
        require(RolePlatformAdmin in roles) { "trusted platform-admin role required" }
    }

    private fun trustedActorRoles(actorId: String): Set<String> {
        val actor = store.userByReefUserId(actorId) ?: throw IllegalArgumentException("Unknown admin actor: $actorId")
        require(actor.trustState == AdminTrustState.Trusted) { "trusted admin actor required" }
        return store.rolesForUser(actorId).map { it.roleId }.toSet()
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
