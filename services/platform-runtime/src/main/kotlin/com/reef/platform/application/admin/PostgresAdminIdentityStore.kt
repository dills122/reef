package com.reef.platform.application.admin

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PostgresAdminIdentityStore(
    private val dataSource: DataSource,
    private val names: PostgresAdminIdentitySqlNames = PostgresAdminIdentitySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : AdminIdentityStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.adminIdentity(
                        users = names.users,
                        roles = names.roles,
                        userRoles = names.userRoles,
                        userBotLimits = names.userBotLimits,
                        userBotOwnerships = names.userBotOwnerships,
                        auditEvents = names.auditEvents
                    )
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.users} (
                      reef_user_id TEXT PRIMARY KEY,
                      github_user_id BIGINT NOT NULL UNIQUE,
                      github_login TEXT NOT NULL,
                      display_name TEXT NOT NULL DEFAULT '',
                      trust_state TEXT NOT NULL DEFAULT 'new',
                      created_at TIMESTAMPTZ NOT NULL,
                      last_seen_at TIMESTAMPTZ NOT NULL,
                      updated_at TIMESTAMPTZ NOT NULL,
                      CHECK (github_user_id > 0),
                      CHECK (github_login ~ '^[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$'),
                      CHECK (trust_state IN ('new', 'trusted', 'limited', 'banned'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.roles} (
                      role_id TEXT PRIMARY KEY,
                      description TEXT NOT NULL DEFAULT '',
                      created_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.userRoles} (
                      reef_user_id TEXT NOT NULL REFERENCES ${names.users}(reef_user_id),
                      role_id TEXT NOT NULL REFERENCES ${names.roles}(role_id),
                      assigned_by TEXT NOT NULL,
                      assigned_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (reef_user_id, role_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.userBotLimits} (
                      reef_user_id TEXT PRIMARY KEY REFERENCES ${names.users}(reef_user_id),
                      max_bots INTEGER NOT NULL,
                      max_active_bots INTEGER NOT NULL,
                      max_version_submissions_per_day INTEGER NOT NULL,
                      updated_by TEXT NOT NULL,
                      updated_at TIMESTAMPTZ NOT NULL,
                      CHECK (max_bots >= 0),
                      CHECK (max_active_bots >= 0),
                      CHECK (max_active_bots <= max_bots),
                      CHECK (max_version_submissions_per_day >= 0)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.userBotOwnerships} (
                      reef_user_id TEXT NOT NULL REFERENCES ${names.users}(reef_user_id),
                      bot_id TEXT NOT NULL,
                      ownership_state TEXT NOT NULL DEFAULT 'owner',
                      assigned_by TEXT NOT NULL,
                      assigned_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (reef_user_id, bot_id),
                      CHECK (bot_id ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
                      CHECK (ownership_state IN ('owner', 'maintainer', 'revoked'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.auditEvents} (
                      event_id TEXT PRIMARY KEY,
                      actor_id TEXT NOT NULL,
                      event_type TEXT NOT NULL,
                      target_type TEXT NOT NULL,
                      target_id TEXT NOT NULL,
                      detail TEXT NOT NULL DEFAULT '',
                      occurred_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_users_github_login ON ${names.users}(github_login)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_user_roles_role ON ${names.userRoles}(role_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_bot_ownerships_bot ON ${names.userBotOwnerships}(bot_id)")
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_admin_audit_target
                    ON ${names.auditEvents}(target_type, target_id, occurred_at DESC)
                    """.trimIndent()
                )
            }
        }
    }

    override fun saveUser(user: AdminUser): AdminUser {
        AdminIdentityValidation.reefUserId(user.reefUserId)
        AdminIdentityValidation.requireGitHubUserId(user.githubUserId)
        AdminIdentityValidation.githubLogin(user.githubLogin)
        AdminIdentityValidation.displayName(user.displayName)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.users}(
                  reef_user_id, github_user_id, github_login, display_name,
                  trust_state, created_at, last_seen_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reef_user_id) DO UPDATE SET
                  github_user_id = EXCLUDED.github_user_id,
                  github_login = EXCLUDED.github_login,
                  display_name = EXCLUDED.display_name,
                  trust_state = EXCLUDED.trust_state,
                  last_seen_at = EXCLUDED.last_seen_at,
                  updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, user.reefUserId)
                ps.setLong(2, user.githubUserId)
                ps.setString(3, user.githubLogin)
                ps.setString(4, user.displayName)
                ps.setString(5, user.trustState.dbValue)
                ps.setTimestamp(6, Timestamp.from(user.createdAt))
                ps.setTimestamp(7, Timestamp.from(user.lastSeenAt))
                ps.setTimestamp(8, Timestamp.from(user.updatedAt))
                ps.executeUpdate()
            }
        }
        return user
    }

    override fun userByReefUserId(reefUserId: String): AdminUser? {
        val id = AdminIdentityValidation.reefUserId(reefUserId)
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT reef_user_id, github_user_id, github_login, display_name,
                       trust_state, created_at, last_seen_at, updated_at
                FROM ${names.users}
                WHERE reef_user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) userFrom(rs) else null
                }
            }
        }
    }

    override fun userByGithubUserId(githubUserId: Long): AdminUser? {
        val id = AdminIdentityValidation.requireGitHubUserId(githubUserId)
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT reef_user_id, github_user_id, github_login, display_name,
                       trust_state, created_at, last_seen_at, updated_at
                FROM ${names.users}
                WHERE github_user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) userFrom(rs) else null
                }
            }
        }
    }

    override fun saveRole(role: AdminRole): AdminRole {
        AdminIdentityValidation.roleId(role.roleId)
        AdminIdentityValidation.detail(role.description)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.roles}(role_id, description, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (role_id) DO UPDATE SET
                  description = EXCLUDED.description
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, role.roleId)
                ps.setString(2, role.description)
                ps.setTimestamp(3, Timestamp.from(role.createdAt))
                ps.executeUpdate()
            }
        }
        return role
    }

    override fun role(roleId: String): AdminRole? {
        val id = AdminIdentityValidation.roleId(roleId)
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT role_id, description, created_at FROM ${names.roles} WHERE role_id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) roleFrom(rs) else null
                }
            }
        }
    }

    override fun roles(): List<AdminRole> {
        return queryList("SELECT role_id, description, created_at FROM ${names.roles} ORDER BY role_id") { rs ->
            roleFrom(rs)
        }
    }

    override fun assignRole(binding: AdminUserRole): AdminUserRole {
        AdminIdentityValidation.reefUserId(binding.reefUserId)
        AdminIdentityValidation.roleId(binding.roleId)
        AdminIdentityValidation.actorId(binding.assignedBy)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.userRoles}(reef_user_id, role_id, assigned_by, assigned_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (reef_user_id, role_id) DO UPDATE SET
                  assigned_by = EXCLUDED.assigned_by,
                  assigned_at = EXCLUDED.assigned_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, binding.reefUserId)
                ps.setString(2, binding.roleId)
                ps.setString(3, binding.assignedBy)
                ps.setTimestamp(4, Timestamp.from(binding.assignedAt))
                ps.executeUpdate()
            }
        }
        return binding
    }

    override fun rolesForUser(reefUserId: String): List<AdminUserRole> {
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        return queryList(
            """
            SELECT reef_user_id, role_id, assigned_by, assigned_at
            FROM ${names.userRoles}
            WHERE reef_user_id = ?
            ORDER BY role_id
            """.trimIndent(),
            userId
        ) { rs -> userRoleFrom(rs) }
    }

    override fun saveUserBotLimit(limit: AdminUserBotLimit): AdminUserBotLimit {
        AdminIdentityValidation.reefUserId(limit.reefUserId)
        AdminIdentityValidation.actorId(limit.updatedBy)
        AdminIdentityValidation.userBotLimit(limit)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.userBotLimits}(
                  reef_user_id, max_bots, max_active_bots,
                  max_version_submissions_per_day, updated_by, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (reef_user_id) DO UPDATE SET
                  max_bots = EXCLUDED.max_bots,
                  max_active_bots = EXCLUDED.max_active_bots,
                  max_version_submissions_per_day = EXCLUDED.max_version_submissions_per_day,
                  updated_by = EXCLUDED.updated_by,
                  updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, limit.reefUserId)
                ps.setInt(2, limit.maxBots)
                ps.setInt(3, limit.maxActiveBots)
                ps.setInt(4, limit.maxVersionSubmissionsPerDay)
                ps.setString(5, limit.updatedBy)
                ps.setTimestamp(6, Timestamp.from(limit.updatedAt))
                ps.executeUpdate()
            }
        }
        return limit
    }

    override fun userBotLimit(reefUserId: String): AdminUserBotLimit? {
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT reef_user_id, max_bots, max_active_bots,
                       max_version_submissions_per_day, updated_by, updated_at
                FROM ${names.userBotLimits}
                WHERE reef_user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) userBotLimitFrom(rs) else null
                }
            }
        }
    }

    override fun saveBotOwnership(ownership: AdminUserBotOwnership): AdminUserBotOwnership {
        AdminIdentityValidation.reefUserId(ownership.reefUserId)
        AdminIdentityValidation.botId(ownership.botId)
        AdminIdentityValidation.actorId(ownership.assignedBy)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.userBotOwnerships}(
                  reef_user_id, bot_id, ownership_state, assigned_by, assigned_at
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (reef_user_id, bot_id) DO UPDATE SET
                  ownership_state = EXCLUDED.ownership_state,
                  assigned_by = EXCLUDED.assigned_by,
                  assigned_at = EXCLUDED.assigned_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownership.reefUserId)
                ps.setString(2, ownership.botId)
                ps.setString(3, ownership.ownershipState.dbValue)
                ps.setString(4, ownership.assignedBy)
                ps.setTimestamp(5, Timestamp.from(ownership.assignedAt))
                ps.executeUpdate()
            }
        }
        return ownership
    }

    override fun botOwnershipsForUser(reefUserId: String): List<AdminUserBotOwnership> {
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        return queryList(
            """
            SELECT reef_user_id, bot_id, ownership_state, assigned_by, assigned_at
            FROM ${names.userBotOwnerships}
            WHERE reef_user_id = ?
            ORDER BY bot_id
            """.trimIndent(),
            userId
        ) { rs -> botOwnershipFrom(rs) }
    }

    override fun botOwnerships(botId: String): List<AdminUserBotOwnership> {
        val bot = AdminIdentityValidation.botId(botId)
        return queryList(
            """
            SELECT reef_user_id, bot_id, ownership_state, assigned_by, assigned_at
            FROM ${names.userBotOwnerships}
            WHERE bot_id = ?
            ORDER BY reef_user_id
            """.trimIndent(),
            bot
        ) { rs -> botOwnershipFrom(rs) }
    }

    override fun appendAuditEvent(event: AdminIdentityAuditEvent) {
        AdminIdentityValidation.actorId(event.actorId)
        AdminIdentityValidation.eventType(event.eventType)
        AdminIdentityValidation.targetType(event.targetType)
        require(event.targetId.isNotBlank()) { "targetId is required" }
        AdminIdentityValidation.detail(event.detail)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.auditEvents}(
                  event_id, actor_id, event_type, target_type, target_id, detail, occurred_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, event.eventId)
                ps.setString(2, event.actorId)
                ps.setString(3, event.eventType)
                ps.setString(4, event.targetType)
                ps.setString(5, event.targetId)
                ps.setString(6, event.detail)
                ps.setTimestamp(7, Timestamp.from(event.occurredAt))
                ps.executeUpdate()
            }
        }
    }

    override fun auditEvents(targetType: String, targetId: String): List<AdminIdentityAuditEvent> {
        val type = AdminIdentityValidation.targetType(targetType)
        require(targetId.isNotBlank()) { "targetId is required" }
        return queryList(
            """
            SELECT event_id, actor_id, event_type, target_type, target_id, detail, occurred_at
            FROM ${names.auditEvents}
            WHERE target_type = ? AND target_id = ?
            ORDER BY occurred_at, event_id
            """.trimIndent(),
            type,
            targetId
        ) { rs -> auditEventFrom(rs) }
    }

    private fun <T> queryList(sql: String, vararg params: String, mapper: (ResultSet) -> T): List<T> {
        connection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, value -> ps.setString(index + 1, value) }
                ps.executeQuery().use { rs ->
                    val values = mutableListOf<T>()
                    while (rs.next()) values += mapper(rs)
                    return values
                }
            }
        }
    }

    private fun userFrom(rs: ResultSet): AdminUser {
        return AdminUser(
            reefUserId = rs.getString("reef_user_id"),
            githubUserId = rs.getLong("github_user_id"),
            githubLogin = rs.getString("github_login"),
            displayName = rs.getString("display_name"),
            trustState = AdminTrustState.fromDb(rs.getString("trust_state")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun roleFrom(rs: ResultSet): AdminRole {
        return AdminRole(
            roleId = rs.getString("role_id"),
            description = rs.getString("description"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    private fun userRoleFrom(rs: ResultSet): AdminUserRole {
        return AdminUserRole(
            reefUserId = rs.getString("reef_user_id"),
            roleId = rs.getString("role_id"),
            assignedBy = rs.getString("assigned_by"),
            assignedAt = rs.getTimestamp("assigned_at").toInstant()
        )
    }

    private fun userBotLimitFrom(rs: ResultSet): AdminUserBotLimit {
        return AdminUserBotLimit(
            reefUserId = rs.getString("reef_user_id"),
            maxBots = rs.getInt("max_bots"),
            maxActiveBots = rs.getInt("max_active_bots"),
            maxVersionSubmissionsPerDay = rs.getInt("max_version_submissions_per_day"),
            updatedBy = rs.getString("updated_by"),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun botOwnershipFrom(rs: ResultSet): AdminUserBotOwnership {
        return AdminUserBotOwnership(
            reefUserId = rs.getString("reef_user_id"),
            botId = rs.getString("bot_id"),
            ownershipState = AdminBotOwnershipState.fromDb(rs.getString("ownership_state")),
            assignedBy = rs.getString("assigned_by"),
            assignedAt = rs.getTimestamp("assigned_at").toInstant()
        )
    }

    private fun auditEventFrom(rs: ResultSet): AdminIdentityAuditEvent {
        return AdminIdentityAuditEvent(
            eventId = rs.getString("event_id"),
            actorId = rs.getString("actor_id"),
            eventType = rs.getString("event_type"),
            targetType = rs.getString("target_type"),
            targetId = rs.getString("target_id"),
            detail = rs.getString("detail"),
            occurredAt = rs.getTimestamp("occurred_at").toInstant()
        )
    }

    private fun connection(): Connection = dataSource.connection
}
