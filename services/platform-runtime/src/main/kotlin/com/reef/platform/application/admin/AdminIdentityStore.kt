package com.reef.platform.application.admin

import java.time.Instant

enum class AdminTrustState(val dbValue: String) {
    New("new"),
    Trusted("trusted"),
    Limited("limited"),
    Banned("banned");

    companion object {
        fun fromDb(value: String): AdminTrustState {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unsupported admin trust state: $value")
        }
    }
}

data class AdminUser(
    val reefUserId: String,
    val githubUserId: Long,
    val githubLogin: String,
    val displayName: String,
    val trustState: AdminTrustState,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val updatedAt: Instant
)

data class AdminRole(
    val roleId: String,
    val description: String,
    val createdAt: Instant
)

data class AdminUserRole(
    val reefUserId: String,
    val roleId: String,
    val assignedBy: String,
    val assignedAt: Instant
)

data class AdminIdentityAuditEvent(
    val eventId: String,
    val actorId: String,
    val eventType: String,
    val targetType: String,
    val targetId: String,
    val detail: String,
    val occurredAt: Instant
)

object AdminIdentityValidation {
    private val githubLoginPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    private val reefUserIdPattern = Regex("user-gh-[1-9][0-9]{0,19}")
    private val roleIdPattern = Regex("[a-z][a-z0-9-]{0,63}")
    private val actorIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}")
    private val eventTypePattern = Regex("[A-Za-z][A-Za-z0-9_-]{0,79}")

    fun requireGitHubUserId(githubUserId: Long): Long {
        require(githubUserId > 0) { "githubUserId must be positive" }
        return githubUserId
    }

    fun githubLogin(value: String): String {
        val login = value.trim()
        require(githubLoginPattern.matches(login)) {
            "githubLogin must match GitHub username rules"
        }
        return login
    }

    fun displayName(value: String): String {
        val displayName = value.trim()
        require(displayName.length <= 160) { "displayName is too long" }
        return displayName
    }

    fun reefUserId(value: String): String {
        val id = value.trim()
        require(reefUserIdPattern.matches(id)) { "invalid reefUserId" }
        return id
    }

    fun roleId(value: String): String {
        val id = value.trim()
        require(roleIdPattern.matches(id)) { "invalid roleId" }
        return id
    }

    fun actorId(value: String): String {
        val id = value.trim()
        require(actorIdPattern.matches(id)) { "invalid actorId" }
        return id
    }

    fun eventType(value: String): String {
        val eventType = value.trim()
        require(eventTypePattern.matches(eventType)) { "invalid eventType" }
        return eventType
    }

    fun targetType(value: String): String = eventType(value)

    fun detail(value: String): String {
        require(value.length <= 2_000) { "audit detail is too long" }
        return value
    }

}

interface AdminIdentityStore {
    fun saveUser(user: AdminUser): AdminUser
    fun users(limit: Int = 100): List<AdminUser>
    fun userByReefUserId(reefUserId: String): AdminUser?
    fun userByGithubUserId(githubUserId: Long): AdminUser?
    fun saveRole(role: AdminRole): AdminRole
    fun role(roleId: String): AdminRole?
    fun roles(): List<AdminRole>
    fun assignRole(binding: AdminUserRole): AdminUserRole
    fun revokeRole(reefUserId: String, roleId: String)
    fun rolesForUser(reefUserId: String): List<AdminUserRole>
    fun appendAuditEvent(event: AdminIdentityAuditEvent)
    fun auditEvents(targetType: String, targetId: String): List<AdminIdentityAuditEvent>
}

class InMemoryAdminIdentityStore : AdminIdentityStore {
    private val users = linkedMapOf<String, AdminUser>()
    private val githubUsers = linkedMapOf<Long, String>()
    private val roles = linkedMapOf<String, AdminRole>()
    private val userRoles = linkedMapOf<Pair<String, String>, AdminUserRole>()
    private val auditEvents = mutableListOf<AdminIdentityAuditEvent>()

    override fun saveUser(user: AdminUser): AdminUser {
        AdminIdentityValidation.reefUserId(user.reefUserId)
        AdminIdentityValidation.requireGitHubUserId(user.githubUserId)
        AdminIdentityValidation.githubLogin(user.githubLogin)
        AdminIdentityValidation.displayName(user.displayName)
        githubUsers[user.githubUserId]?.let { existingId ->
            require(existingId == user.reefUserId) {
                "GitHub user ${user.githubUserId} is already bound to $existingId"
            }
        }
        users[user.reefUserId] = user
        githubUsers[user.githubUserId] = user.reefUserId
        return user
    }

    override fun userByReefUserId(reefUserId: String): AdminUser? {
        return users[AdminIdentityValidation.reefUserId(reefUserId)]
    }

    override fun users(limit: Int): List<AdminUser> {
        return users.values.sortedByDescending { it.lastSeenAt }.take(limit.coerceIn(1, 500))
    }

    override fun userByGithubUserId(githubUserId: Long): AdminUser? {
        AdminIdentityValidation.requireGitHubUserId(githubUserId)
        return githubUsers[githubUserId]?.let(users::get)
    }

    override fun saveRole(role: AdminRole): AdminRole {
        AdminIdentityValidation.roleId(role.roleId)
        AdminIdentityValidation.detail(role.description)
        roles[role.roleId] = role
        return role
    }

    override fun role(roleId: String): AdminRole? = roles[AdminIdentityValidation.roleId(roleId)]

    override fun roles(): List<AdminRole> = roles.values.toList()

    override fun assignRole(binding: AdminUserRole): AdminUserRole {
        AdminIdentityValidation.reefUserId(binding.reefUserId)
        AdminIdentityValidation.roleId(binding.roleId)
        AdminIdentityValidation.actorId(binding.assignedBy)
        require(users.containsKey(binding.reefUserId)) { "Unknown admin user: ${binding.reefUserId}" }
        require(roles.containsKey(binding.roleId)) { "Unknown admin role: ${binding.roleId}" }
        userRoles[binding.reefUserId to binding.roleId] = binding
        return binding
    }

    override fun revokeRole(reefUserId: String, roleId: String) {
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        val role = AdminIdentityValidation.roleId(roleId)
        userRoles.remove(userId to role)
    }

    override fun rolesForUser(reefUserId: String): List<AdminUserRole> {
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        return userRoles.values.filter { it.reefUserId == userId }
    }

    override fun appendAuditEvent(event: AdminIdentityAuditEvent) {
        AdminIdentityValidation.actorId(event.actorId)
        AdminIdentityValidation.eventType(event.eventType)
        AdminIdentityValidation.targetType(event.targetType)
        require(event.targetId.isNotBlank()) { "targetId is required" }
        AdminIdentityValidation.detail(event.detail)
        auditEvents += event
    }

    override fun auditEvents(targetType: String, targetId: String): List<AdminIdentityAuditEvent> {
        val type = AdminIdentityValidation.targetType(targetType)
        require(targetId.isNotBlank()) { "targetId is required" }
        return auditEvents.filter { it.targetType == type && it.targetId == targetId }
    }
}
