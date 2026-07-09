package com.reef.platform.application.admin

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AdminOAuthStart(
    val stateToken: String,
    val redirectPath: String,
    val expiresAt: Instant
)

data class AdminSessionToken(
    val token: String,
    val session: AdminSession
)

data class AdminServiceTokenIssued(
    val token: String,
    val record: AdminServiceToken
)

class AdminAuthService(
    private val authStore: AdminAuthStore,
    private val identityStore: AdminIdentityStore,
    private val now: () -> Instant = { Instant.now() },
    private val tokenFactory: () -> String = { AdminAuthTokenCodec.newToken() },
    private val tokenIdFactory: () -> String = { "svc-${UUID.randomUUID()}" },
    private val oauthStateTtl: Duration = Duration.ofMinutes(10),
    private val sessionTtl: Duration = Duration.ofHours(12)
) {
    fun beginGitHubOAuth(redirectPath: String = "/"): AdminOAuthStart {
        val token = tokenFactory()
        val createdAt = now()
        val redirect = AdminAuthTokenCodec.redirectPath(redirectPath)
        val state = AdminOAuthState(
            stateHash = AdminAuthTokenCodec.hashToken(token),
            provider = AdminAuthProvider.GitHub,
            redirectPath = redirect,
            createdAt = createdAt,
            expiresAt = createdAt.plus(oauthStateTtl)
        )
        authStore.saveOAuthState(state)
        return AdminOAuthStart(stateToken = token, redirectPath = redirect, expiresAt = state.expiresAt)
    }

    fun consumeGitHubOAuthState(stateToken: String): AdminOAuthState {
        val hash = AdminAuthTokenCodec.hashToken(stateToken)
        val state = authStore.oauthState(hash) ?: throw AuthorizationException("unknown OAuth state")
        val current = now()
        require(state.provider == AdminAuthProvider.GitHub) { "OAuth provider mismatch" }
        require(state.consumedAt == null) { "OAuth state already consumed" }
        require(current.isBefore(state.expiresAt)) { "OAuth state expired" }
        return authStore.consumeOAuthState(hash, current)
    }

    fun createSession(reefUserId: String): AdminSessionToken {
        val userId = AdminIdentityValidation.reefUserId(reefUserId)
        val user = identityStore.userByReefUserId(userId) ?: throw AuthorizationException("unknown admin user")
        require(user.trustState != AdminTrustState.Banned) { "admin user is banned" }
        val token = tokenFactory()
        val createdAt = now()
        val session = AdminSession(
            sessionHash = AdminAuthTokenCodec.hashToken(token),
            reefUserId = userId,
            authProvider = AdminAuthProvider.GitHub,
            createdAt = createdAt,
            expiresAt = createdAt.plus(sessionTtl),
            lastSeenAt = createdAt
        )
        authStore.saveSession(session)
        return AdminSessionToken(token = token, session = session)
    }

    fun authenticateSession(token: String): AdminSession {
        val hash = AdminAuthTokenCodec.hashToken(token)
        val session = authStore.session(hash) ?: throw AuthorizationException("unknown admin session")
        val current = now()
        require(session.revokedAt == null) { "admin session revoked" }
        require(current.isBefore(session.expiresAt)) { "admin session expired" }
        val user = identityStore.userByReefUserId(session.reefUserId)
            ?: throw AuthorizationException("unknown admin user")
        require(user.trustState != AdminTrustState.Banned) { "admin user is banned" }
        return authStore.touchSession(hash, current)
    }

    fun revokeSession(token: String): AdminSession {
        return authStore.revokeSession(AdminAuthTokenCodec.hashToken(token), now())
    }

    fun issueServiceToken(
        tokenFamily: AdminServiceTokenFamily,
        subjectActorId: String,
        ttl: Duration? = Duration.ofDays(30)
    ): AdminServiceTokenIssued {
        val actor = AdminIdentityValidation.actorId(subjectActorId)
        val token = tokenFactory()
        val createdAt = now()
        val record = AdminServiceToken(
            tokenId = AdminIdentityValidation.actorId(tokenIdFactory()),
            tokenHash = AdminAuthTokenCodec.hashToken(token),
            tokenFamily = tokenFamily,
            subjectActorId = actor,
            createdAt = createdAt,
            expiresAt = ttl?.let { createdAt.plus(it) }
        )
        authStore.saveServiceToken(record)
        return AdminServiceTokenIssued(token = token, record = record)
    }

    fun authenticateServiceToken(token: String, expectedFamily: AdminServiceTokenFamily): AdminServiceToken {
        val hash = AdminAuthTokenCodec.hashToken(token)
        val record = authStore.serviceTokenByHash(hash) ?: throw AuthorizationException("unknown service token")
        val current = now()
        require(record.tokenFamily == expectedFamily) { "service token family mismatch" }
        require(record.revokedAt == null) { "service token revoked" }
        record.expiresAt?.let { require(current.isBefore(it)) { "service token expired" } }
        return authStore.touchServiceToken(hash, current)
    }
}
