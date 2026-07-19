package com.reef.arena.controlplane.arena

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * Arena-owned bot entitlements. Reef user identities are deliberately represented
 * only by their stable identifier: Arena and Reef may use separate databases.
 */
class PostgresArenaBotEntitlementStore(
    private val dataSource: DataSource,
    private val names: PostgresArenaSqlNames = PostgresArenaSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : ArenaBotEntitlementStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(conn, ArenaPostgresSchemaRequirements.entitlements(names))
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.userBotLimits} (
                      reef_user_id TEXT PRIMARY KEY,
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
                      reef_user_id TEXT NOT NULL,
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
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_bot_ownerships_bot ON ${names.userBotOwnerships}(bot_id)")
            }
        }
    }

    override fun saveUserBotLimit(limit: ArenaUserBotLimit): ArenaUserBotLimit {
        ArenaBotEntitlementValidation.reefUserId(limit.reefUserId)
        ArenaBotEntitlementValidation.actorId(limit.updatedBy)
        ArenaBotEntitlementValidation.userBotLimit(limit)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.userBotLimits}(
                  reef_user_id, max_bots, max_active_bots,
                  max_version_submissions_per_day, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
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

    override fun userBotLimit(reefUserId: String): ArenaUserBotLimit? {
        val userId = ArenaBotEntitlementValidation.reefUserId(reefUserId)
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT reef_user_id, max_bots, max_active_bots,
                       max_version_submissions_per_day, updated_by, updated_at
                FROM ${names.userBotLimits} WHERE reef_user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> return if (rs.next()) rs.toArenaUserBotLimit() else null }
            }
        }
    }

    override fun saveBotOwnership(ownership: ArenaBotOwnership): ArenaBotOwnership {
        ArenaBotEntitlementValidation.reefUserId(ownership.reefUserId)
        ArenaBotEntitlementValidation.botId(ownership.botId)
        ArenaBotEntitlementValidation.actorId(ownership.assignedBy)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.userBotOwnerships}(
                  reef_user_id, bot_id, ownership_state, assigned_by, assigned_at
                ) VALUES (?, ?, ?, ?, ?)
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

    override fun botOwnershipsForUser(reefUserId: String): List<ArenaBotOwnership> =
        queryOwnerships("reef_user_id = ?", ArenaBotEntitlementValidation.reefUserId(reefUserId), "bot_id")

    override fun botOwnerships(botId: String): List<ArenaBotOwnership> =
        queryOwnerships("bot_id = ?", ArenaBotEntitlementValidation.botId(botId), "reef_user_id")

    private fun queryOwnerships(where: String, value: String, orderBy: String): List<ArenaBotOwnership> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT reef_user_id, bot_id, ownership_state, assigned_by, assigned_at
                FROM ${names.userBotOwnerships}
                WHERE $where
                ORDER BY $orderBy
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use { rs ->
                    val ownerships = mutableListOf<ArenaBotOwnership>()
                    while (rs.next()) ownerships += rs.toArenaBotOwnership()
                    return ownerships
                }
            }
        }
    }

    private fun connection(): Connection = dataSource.connection

    private fun ResultSet.toArenaUserBotLimit() = ArenaUserBotLimit(
        reefUserId = getString("reef_user_id"),
        maxBots = getInt("max_bots"),
        maxActiveBots = getInt("max_active_bots"),
        maxVersionSubmissionsPerDay = getInt("max_version_submissions_per_day"),
        updatedBy = getString("updated_by"),
        updatedAt = getTimestamp("updated_at").toInstant()
    )

    private fun ResultSet.toArenaBotOwnership() = ArenaBotOwnership(
        reefUserId = getString("reef_user_id"),
        botId = getString("bot_id"),
        ownershipState = ArenaBotOwnershipState.fromDb(getString("ownership_state")),
        assignedBy = getString("assigned_by"),
        assignedAt = getTimestamp("assigned_at").toInstant()
    )
}
