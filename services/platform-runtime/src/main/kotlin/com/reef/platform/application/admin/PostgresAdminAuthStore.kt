package com.reef.platform.application.admin

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PostgresAdminAuthStore(
    private val dataSource: DataSource,
    private val names: PostgresAdminAuthSqlNames = PostgresAdminAuthSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : AdminAuthStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.adminAuth(
                        oauthStates = names.oauthStates,
                        sessions = names.sessions,
                        serviceTokens = names.serviceTokens
                    )
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.oauthStates} (
                      state_hash TEXT PRIMARY KEY,
                      provider TEXT NOT NULL,
                      redirect_path TEXT NOT NULL DEFAULT '/',
                      created_at TIMESTAMPTZ NOT NULL,
                      expires_at TIMESTAMPTZ NOT NULL,
                      consumed_at TIMESTAMPTZ,
                      CHECK (length(state_hash) = 64),
                      CHECK (provider IN ('github')),
                      CHECK (redirect_path LIKE '/%' AND redirect_path NOT LIKE '//%'),
                      CHECK (expires_at > created_at)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.sessions} (
                      session_hash TEXT PRIMARY KEY,
                      reef_user_id TEXT NOT NULL REFERENCES ${names.schemaName}.users(reef_user_id),
                      auth_provider TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL,
                      expires_at TIMESTAMPTZ NOT NULL,
                      last_seen_at TIMESTAMPTZ NOT NULL,
                      revoked_at TIMESTAMPTZ,
                      CHECK (length(session_hash) = 64),
                      CHECK (auth_provider IN ('github')),
                      CHECK (expires_at > created_at)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.serviceTokens} (
                      token_id TEXT PRIMARY KEY,
                      token_hash TEXT NOT NULL UNIQUE,
                      token_family TEXT NOT NULL,
                      subject_actor_id TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL,
                      expires_at TIMESTAMPTZ,
                      last_used_at TIMESTAMPTZ,
                      revoked_at TIMESTAMPTZ,
                      CHECK (length(token_hash) = 64),
                      CHECK (token_family IN ('ci', 'sim', 'run-plane', 'admin'))
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_oauth_states_expiry ON ${names.oauthStates}(expires_at)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_sessions_user ON ${names.sessions}(reef_user_id, expires_at)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_admin_service_tokens_family ON ${names.serviceTokens}(token_family, expires_at)")
            }
        }
    }

    override fun saveOAuthState(state: AdminOAuthState): AdminOAuthState {
        AdminAuthTokenCodec.requireHash(state.stateHash)
        AdminAuthTokenCodec.redirectPath(state.redirectPath)
        require(state.expiresAt.isAfter(state.createdAt)) { "oauth state expiry must be after creation" }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.oauthStates}(
                  state_hash, provider, redirect_path, created_at, expires_at, consumed_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (state_hash) DO UPDATE SET
                  redirect_path = EXCLUDED.redirect_path,
                  expires_at = EXCLUDED.expires_at,
                  consumed_at = EXCLUDED.consumed_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, state.stateHash)
                ps.setString(2, state.provider.dbValue)
                ps.setString(3, state.redirectPath)
                ps.setTimestamp(4, Timestamp.from(state.createdAt))
                ps.setTimestamp(5, Timestamp.from(state.expiresAt))
                ps.setNullableTimestamp(6, state.consumedAt)
                ps.executeUpdate()
            }
        }
        return state
    }

    override fun oauthState(stateHash: String): AdminOAuthState? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT state_hash, provider, redirect_path, created_at, expires_at, consumed_at
                FROM ${names.oauthStates}
                WHERE state_hash = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, AdminAuthTokenCodec.requireHash(stateHash))
                ps.executeQuery().use { rs -> return if (rs.next()) oauthStateFrom(rs) else null }
            }
        }
    }

    override fun consumeOAuthState(stateHash: String, consumedAt: Instant): AdminOAuthState {
        connection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.oauthStates}
                SET consumed_at = ?
                WHERE state_hash = ? AND consumed_at IS NULL
                RETURNING state_hash, provider, redirect_path, created_at, expires_at, consumed_at
                """.trimIndent()
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(consumedAt))
                ps.setString(2, AdminAuthTokenCodec.requireHash(stateHash))
                ps.executeQuery().use { rs ->
                    if (rs.next()) return oauthStateFrom(rs)
                }
            }
        }
        val existing = oauthState(stateHash) ?: throw IllegalArgumentException("Unknown OAuth state")
        require(existing.consumedAt == null) { "OAuth state already consumed" }
        error("OAuth state consume failed")
    }

    override fun saveSession(session: AdminSession): AdminSession {
        AdminAuthTokenCodec.requireHash(session.sessionHash)
        AdminIdentityValidation.reefUserId(session.reefUserId)
        require(session.expiresAt.isAfter(session.createdAt)) { "session expiry must be after creation" }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.sessions}(
                  session_hash, reef_user_id, auth_provider, created_at, expires_at, last_seen_at, revoked_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_hash) DO UPDATE SET
                  expires_at = EXCLUDED.expires_at,
                  last_seen_at = EXCLUDED.last_seen_at,
                  revoked_at = EXCLUDED.revoked_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, session.sessionHash)
                ps.setString(2, session.reefUserId)
                ps.setString(3, session.authProvider.dbValue)
                ps.setTimestamp(4, Timestamp.from(session.createdAt))
                ps.setTimestamp(5, Timestamp.from(session.expiresAt))
                ps.setTimestamp(6, Timestamp.from(session.lastSeenAt))
                ps.setNullableTimestamp(7, session.revokedAt)
                ps.executeUpdate()
            }
        }
        return session
    }

    override fun session(sessionHash: String): AdminSession? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT session_hash, reef_user_id, auth_provider, created_at, expires_at, last_seen_at, revoked_at
                FROM ${names.sessions}
                WHERE session_hash = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, AdminAuthTokenCodec.requireHash(sessionHash))
                ps.executeQuery().use { rs -> return if (rs.next()) sessionFrom(rs) else null }
            }
        }
    }

    override fun touchSession(sessionHash: String, seenAt: Instant): AdminSession {
        val existing = session(sessionHash) ?: throw IllegalArgumentException("Unknown admin session")
        val touched = existing.copy(lastSeenAt = seenAt)
        saveSession(touched)
        return touched
    }

    override fun revokeSession(sessionHash: String, revokedAt: Instant): AdminSession {
        val existing = session(sessionHash) ?: throw IllegalArgumentException("Unknown admin session")
        val revoked = existing.copy(revokedAt = revokedAt)
        saveSession(revoked)
        return revoked
    }

    override fun saveServiceToken(token: AdminServiceToken): AdminServiceToken {
        AdminIdentityValidation.actorId(token.tokenId)
        AdminAuthTokenCodec.requireHash(token.tokenHash)
        AdminIdentityValidation.actorId(token.subjectActorId)
        token.expiresAt?.let { require(it.isAfter(token.createdAt)) { "service token expiry must be after creation" } }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.serviceTokens}(
                  token_id, token_hash, token_family, subject_actor_id,
                  created_at, expires_at, last_used_at, revoked_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (token_id) DO UPDATE SET
                  token_hash = EXCLUDED.token_hash,
                  token_family = EXCLUDED.token_family,
                  subject_actor_id = EXCLUDED.subject_actor_id,
                  expires_at = EXCLUDED.expires_at,
                  last_used_at = EXCLUDED.last_used_at,
                  revoked_at = EXCLUDED.revoked_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, token.tokenId)
                ps.setString(2, token.tokenHash)
                ps.setString(3, token.tokenFamily.dbValue)
                ps.setString(4, token.subjectActorId)
                ps.setTimestamp(5, Timestamp.from(token.createdAt))
                ps.setNullableTimestamp(6, token.expiresAt)
                ps.setNullableTimestamp(7, token.lastUsedAt)
                ps.setNullableTimestamp(8, token.revokedAt)
                ps.executeUpdate()
            }
        }
        return token
    }

    override fun serviceTokenByHash(tokenHash: String): AdminServiceToken? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT token_id, token_hash, token_family, subject_actor_id,
                       created_at, expires_at, last_used_at, revoked_at
                FROM ${names.serviceTokens}
                WHERE token_hash = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, AdminAuthTokenCodec.requireHash(tokenHash))
                ps.executeQuery().use { rs -> return if (rs.next()) serviceTokenFrom(rs) else null }
            }
        }
    }

    override fun touchServiceToken(tokenHash: String, usedAt: Instant): AdminServiceToken {
        val existing = serviceTokenByHash(tokenHash) ?: throw IllegalArgumentException("Unknown service token")
        val touched = existing.copy(lastUsedAt = usedAt)
        saveServiceToken(touched)
        return touched
    }

    override fun revokeServiceToken(tokenId: String, revokedAt: Instant): AdminServiceToken {
        val id = AdminIdentityValidation.actorId(tokenId)
        val existing = queryServiceTokenById(id) ?: throw IllegalArgumentException("Unknown service token")
        val revoked = existing.copy(revokedAt = revokedAt)
        saveServiceToken(revoked)
        return revoked
    }

    private fun queryServiceTokenById(tokenId: String): AdminServiceToken? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT token_id, token_hash, token_family, subject_actor_id,
                       created_at, expires_at, last_used_at, revoked_at
                FROM ${names.serviceTokens}
                WHERE token_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tokenId)
                ps.executeQuery().use { rs -> return if (rs.next()) serviceTokenFrom(rs) else null }
            }
        }
    }

    private fun oauthStateFrom(rs: ResultSet): AdminOAuthState {
        return AdminOAuthState(
            stateHash = rs.getString("state_hash"),
            provider = AdminAuthProvider.fromDb(rs.getString("provider")),
            redirectPath = rs.getString("redirect_path"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            consumedAt = rs.getNullableInstant("consumed_at")
        )
    }

    private fun sessionFrom(rs: ResultSet): AdminSession {
        return AdminSession(
            sessionHash = rs.getString("session_hash"),
            reefUserId = rs.getString("reef_user_id"),
            authProvider = AdminAuthProvider.fromDb(rs.getString("auth_provider")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
            revokedAt = rs.getNullableInstant("revoked_at")
        )
    }

    private fun serviceTokenFrom(rs: ResultSet): AdminServiceToken {
        return AdminServiceToken(
            tokenId = rs.getString("token_id"),
            tokenHash = rs.getString("token_hash"),
            tokenFamily = AdminServiceTokenFamily.fromDb(rs.getString("token_family")),
            subjectActorId = rs.getString("subject_actor_id"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getNullableInstant("expires_at"),
            lastUsedAt = rs.getNullableInstant("last_used_at"),
            revokedAt = rs.getNullableInstant("revoked_at")
        )
    }

    private fun java.sql.PreparedStatement.setNullableTimestamp(index: Int, instant: Instant?) {
        if (instant == null) {
            setTimestamp(index, null)
        } else {
            setTimestamp(index, Timestamp.from(instant))
        }
    }

    private fun ResultSet.getNullableInstant(column: String): Instant? {
        return getTimestamp(column)?.toInstant()
    }

    private fun connection(): Connection = dataSource.connection
}
