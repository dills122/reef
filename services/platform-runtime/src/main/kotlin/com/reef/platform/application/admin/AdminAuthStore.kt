package com.reef.platform.application.admin

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

enum class AdminAuthProvider(val dbValue: String) {
    GitHub("github");

    companion object {
        fun fromDb(value: String): AdminAuthProvider {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unsupported admin auth provider: $value")
        }
    }
}

enum class AdminServiceTokenFamily(val dbValue: String) {
    Ci("ci"),
    Sim("sim"),
    RunPlane("run-plane"),
    Admin("admin");

    companion object {
        fun fromDb(value: String): AdminServiceTokenFamily {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unsupported admin service token family: $value")
        }
    }
}

data class AdminOAuthState(
    val stateHash: String,
    val provider: AdminAuthProvider,
    val redirectPath: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant? = null
)

data class AdminSession(
    val sessionHash: String,
    val reefUserId: String,
    val authProvider: AdminAuthProvider,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant? = null
)

data class AdminServiceToken(
    val tokenId: String,
    val tokenHash: String,
    val tokenFamily: AdminServiceTokenFamily,
    val subjectActorId: String,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null
)

interface AdminAuthStore {
    fun saveOAuthState(state: AdminOAuthState): AdminOAuthState
    fun oauthState(stateHash: String): AdminOAuthState?
    fun consumeOAuthState(stateHash: String, consumedAt: Instant): AdminOAuthState
    fun saveSession(session: AdminSession): AdminSession
    fun session(sessionHash: String): AdminSession?
    fun touchSession(sessionHash: String, seenAt: Instant): AdminSession
    fun revokeSession(sessionHash: String, revokedAt: Instant): AdminSession
    fun saveServiceToken(token: AdminServiceToken): AdminServiceToken
    fun serviceTokenByHash(tokenHash: String): AdminServiceToken?
    fun touchServiceToken(tokenHash: String, usedAt: Instant): AdminServiceToken
    fun revokeServiceToken(tokenId: String, revokedAt: Instant): AdminServiceToken
}

object AdminAuthTokenCodec {
    private val random = SecureRandom()
    private val tokenPattern = Regex("[A-Za-z0-9_-]{32,256}")
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val redirectPathPattern = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,511}")

    fun newToken(byteCount: Int = 32): String {
        require(byteCount in 16..96) { "byteCount out of range" }
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hashToken(token: String): String {
        val normalized = requireToken(token)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun requireToken(token: String): String {
        val value = token.trim()
        require(tokenPattern.matches(value)) { "invalid token shape" }
        return value
    }

    fun requireHash(hash: String): String {
        val value = hash.trim()
        require(hashPattern.matches(value)) { "invalid token hash" }
        return value
    }

    fun redirectPath(path: String): String {
        val value = path.trim().ifBlank { "/" }
        require(redirectPathPattern.matches(value) && !value.startsWith("//")) { "invalid redirectPath" }
        return value
    }
}

class InMemoryAdminAuthStore : AdminAuthStore {
    private val oauthStates = linkedMapOf<String, AdminOAuthState>()
    private val sessions = linkedMapOf<String, AdminSession>()
    private val serviceTokensById = linkedMapOf<String, AdminServiceToken>()
    private val serviceTokenIdsByHash = linkedMapOf<String, String>()

    override fun saveOAuthState(state: AdminOAuthState): AdminOAuthState {
        AdminAuthTokenCodec.requireHash(state.stateHash)
        AdminAuthTokenCodec.redirectPath(state.redirectPath)
        require(state.expiresAt.isAfter(state.createdAt)) { "oauth state expiry must be after creation" }
        oauthStates[state.stateHash] = state
        return state
    }

    override fun oauthState(stateHash: String): AdminOAuthState? {
        return oauthStates[AdminAuthTokenCodec.requireHash(stateHash)]
    }

    override fun consumeOAuthState(stateHash: String, consumedAt: Instant): AdminOAuthState {
        val hash = AdminAuthTokenCodec.requireHash(stateHash)
        val existing = oauthStates[hash] ?: throw IllegalArgumentException("Unknown OAuth state")
        require(existing.consumedAt == null) { "OAuth state already consumed" }
        val consumed = existing.copy(consumedAt = consumedAt)
        oauthStates[hash] = consumed
        return consumed
    }

    override fun saveSession(session: AdminSession): AdminSession {
        AdminAuthTokenCodec.requireHash(session.sessionHash)
        AdminIdentityValidation.reefUserId(session.reefUserId)
        require(session.expiresAt.isAfter(session.createdAt)) { "session expiry must be after creation" }
        sessions[session.sessionHash] = session
        return session
    }

    override fun session(sessionHash: String): AdminSession? {
        return sessions[AdminAuthTokenCodec.requireHash(sessionHash)]
    }

    override fun touchSession(sessionHash: String, seenAt: Instant): AdminSession {
        val hash = AdminAuthTokenCodec.requireHash(sessionHash)
        val existing = sessions[hash] ?: throw IllegalArgumentException("Unknown admin session")
        val touched = existing.copy(lastSeenAt = seenAt)
        sessions[hash] = touched
        return touched
    }

    override fun revokeSession(sessionHash: String, revokedAt: Instant): AdminSession {
        val hash = AdminAuthTokenCodec.requireHash(sessionHash)
        val existing = sessions[hash] ?: throw IllegalArgumentException("Unknown admin session")
        val revoked = existing.copy(revokedAt = revokedAt)
        sessions[hash] = revoked
        return revoked
    }

    override fun saveServiceToken(token: AdminServiceToken): AdminServiceToken {
        AdminIdentityValidation.actorId(token.tokenId)
        AdminAuthTokenCodec.requireHash(token.tokenHash)
        AdminIdentityValidation.actorId(token.subjectActorId)
        token.expiresAt?.let { require(it.isAfter(token.createdAt)) { "service token expiry must be after creation" } }
        serviceTokensById[token.tokenId]?.let { previous ->
            serviceTokenIdsByHash.remove(previous.tokenHash)
        }
        serviceTokensById[token.tokenId] = token
        serviceTokenIdsByHash[token.tokenHash] = token.tokenId
        return token
    }

    override fun serviceTokenByHash(tokenHash: String): AdminServiceToken? {
        return serviceTokenIdsByHash[AdminAuthTokenCodec.requireHash(tokenHash)]?.let(serviceTokensById::get)
    }

    override fun touchServiceToken(tokenHash: String, usedAt: Instant): AdminServiceToken {
        val existing = serviceTokenByHash(tokenHash) ?: throw IllegalArgumentException("Unknown service token")
        val touched = existing.copy(lastUsedAt = usedAt)
        serviceTokensById[touched.tokenId] = touched
        return touched
    }

    override fun revokeServiceToken(tokenId: String, revokedAt: Instant): AdminServiceToken {
        val id = AdminIdentityValidation.actorId(tokenId)
        val existing = serviceTokensById[id] ?: throw IllegalArgumentException("Unknown service token")
        val revoked = existing.copy(revokedAt = revokedAt)
        serviceTokensById[id] = revoked
        return revoked
    }
}
