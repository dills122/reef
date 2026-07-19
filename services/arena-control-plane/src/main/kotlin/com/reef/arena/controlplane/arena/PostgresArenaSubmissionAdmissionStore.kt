package com.reef.arena.controlplane.arena

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/** Durable pre-merge admission state for fork-safe Arena bot submissions. */
class PostgresArenaSubmissionAdmissionStore(
    private val dataSource: DataSource,
    private val names: PostgresArenaSqlNames = PostgresArenaSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : ArenaSubmissionAdmissionStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(conn, ArenaPostgresSchemaRequirements.submissionAdmissions(names))
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.submissionAdmissions} (
                      repository TEXT NOT NULL,
                      pull_request_number BIGINT NOT NULL,
                      bot_id TEXT NOT NULL,
                      head_repository TEXT NOT NULL,
                      head_owner_login TEXT NOT NULL,
                      github_user_id BIGINT NOT NULL,
                      github_login TEXT NOT NULL,
                      head_sha TEXT NOT NULL,
                      state TEXT NOT NULL,
                      invitation_actor TEXT,
                      invitation_reason TEXT NOT NULL DEFAULT '',
                      invited_at TIMESTAMPTZ,
                      created_at TIMESTAMPTZ NOT NULL,
                      updated_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (repository, pull_request_number),
                      CHECK (pull_request_number > 0),
                      CHECK (github_user_id > 0),
                      CHECK (bot_id ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
                      CHECK (state IN ('pending_invite_review', 'invite_approved'))
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun recordPending(command: RecordArenaSubmissionPendingCommand): ArenaSubmissionAdmission {
        val normalized = ArenaSubmissionAdmissionValidation.recordPending(command)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.submissionAdmissions}(
                  repository, pull_request_number, bot_id, head_repository, head_owner_login,
                  github_user_id, github_login, head_sha, state, invitation_actor,
                  invitation_reason, invited_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending_invite_review', NULL, '', NULL, ?, ?)
                ON CONFLICT (repository, pull_request_number) DO UPDATE SET
                  bot_id = EXCLUDED.bot_id,
                  head_repository = EXCLUDED.head_repository,
                  head_owner_login = EXCLUDED.head_owner_login,
                  github_user_id = EXCLUDED.github_user_id,
                  github_login = EXCLUDED.github_login,
                  state = CASE
                    WHEN ${names.submissionAdmissions}.head_sha = EXCLUDED.head_sha THEN ${names.submissionAdmissions}.state
                    ELSE 'pending_invite_review'
                  END,
                  invitation_actor = CASE
                    WHEN ${names.submissionAdmissions}.head_sha = EXCLUDED.head_sha THEN ${names.submissionAdmissions}.invitation_actor
                    ELSE NULL
                  END,
                  invitation_reason = CASE
                    WHEN ${names.submissionAdmissions}.head_sha = EXCLUDED.head_sha THEN ${names.submissionAdmissions}.invitation_reason
                    ELSE ''
                  END,
                  invited_at = CASE
                    WHEN ${names.submissionAdmissions}.head_sha = EXCLUDED.head_sha THEN ${names.submissionAdmissions}.invited_at
                    ELSE NULL
                  END,
                  head_sha = EXCLUDED.head_sha,
                  updated_at = EXCLUDED.updated_at
                RETURNING *
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, normalized.repository)
                ps.setLong(2, normalized.pullRequestNumber)
                ps.setString(3, normalized.botId)
                ps.setString(4, normalized.headRepository)
                ps.setString(5, normalized.headOwnerLogin)
                ps.setLong(6, normalized.githubUserId)
                ps.setString(7, normalized.githubLogin)
                ps.setString(8, normalized.headSha)
                ps.setTimestamp(9, Timestamp.from(normalized.occurredAt))
                ps.setTimestamp(10, Timestamp.from(normalized.occurredAt))
                ps.executeQuery().use { rs ->
                    require(rs.next()) { "submission admission record was not returned" }
                    return rs.toArenaSubmissionAdmission()
                }
            }
        }
    }

    override fun approveInvite(command: ApproveArenaSubmissionInviteCommand): ArenaSubmissionAdmission {
        val normalized = ArenaSubmissionAdmissionValidation.approveInvite(command)
        connection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.submissionAdmissions}
                SET state = 'invite_approved', invitation_actor = ?, invitation_reason = ?,
                    invited_at = ?, updated_at = ?
                WHERE repository = ? AND pull_request_number = ? AND head_sha = ?
                RETURNING *
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, normalized.actorId)
                ps.setString(2, normalized.reason)
                ps.setTimestamp(3, Timestamp.from(normalized.occurredAt))
                ps.setTimestamp(4, Timestamp.from(normalized.occurredAt))
                ps.setString(5, normalized.repository)
                ps.setLong(6, normalized.pullRequestNumber)
                ps.setString(7, normalized.approvedHeadSha)
                ps.executeQuery().use { rs ->
                    require(rs.next()) { "submission admission not found or head SHA changed; invitation approval is invalid" }
                    return rs.toArenaSubmissionAdmission()
                }
            }
        }
    }

    override fun admission(repository: String, pullRequestNumber: Long): ArenaSubmissionAdmission? {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM ${names.submissionAdmissions} WHERE repository = ? AND pull_request_number = ?"
            ).use { ps ->
                ps.setString(1, ArenaSubmissionAdmissionValidation.repository(repository))
                ps.setLong(2, ArenaSubmissionAdmissionValidation.pullRequestNumber(pullRequestNumber))
                ps.executeQuery().use { rs -> return if (rs.next()) rs.toArenaSubmissionAdmission() else null }
            }
        }
    }

    private fun connection(): Connection = dataSource.connection

    private fun ResultSet.toArenaSubmissionAdmission() = ArenaSubmissionAdmission(
        repository = getString("repository"),
        pullRequestNumber = getLong("pull_request_number"),
        botId = getString("bot_id"),
        headRepository = getString("head_repository"),
        headOwnerLogin = getString("head_owner_login"),
        githubUserId = getLong("github_user_id"),
        githubLogin = getString("github_login"),
        headSha = getString("head_sha"),
        state = ArenaSubmissionAdmissionState.fromDb(getString("state")),
        invitationActor = getString("invitation_actor"),
        invitationReason = getString("invitation_reason"),
        invitedAt = getTimestamp("invited_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant()
    )
}
