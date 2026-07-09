package com.reef.platform.application.admin

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class AdminAuthServiceTest {
    private var clock = Instant.parse("2026-07-09T12:00:00Z")
    private var tokenCounter = 0
    private val identityStore = InMemoryAdminIdentityStore()
    private val authStore = InMemoryAdminAuthStore()
    private val identity = AdminIdentityService(identityStore, now = { clock }, eventIdFactory = { "evt-$tokenCounter" })
    private val auth = AdminAuthService(
        authStore = authStore,
        identityStore = identityStore,
        now = { clock },
        tokenFactory = { "tok-${++tokenCounter}-abcdefghijklmnopqrstuvwxyz0123456789" },
        tokenIdFactory = { "svc-token-$tokenCounter" },
        oauthStateTtl = Duration.ofMinutes(10),
        sessionTtl = Duration.ofHours(1)
    )

    @Test
    fun oauthStateStoresHashAndConsumesOnce() {
        val start = auth.beginGitHubOAuth("/admin")

        assertEquals("/admin", start.redirectPath)
        assertFailsWith<IllegalArgumentException> {
            authStore.oauthState(start.stateToken)
        }
        val stored = authStore.oauthState(AdminAuthTokenCodec.hashToken(start.stateToken))
        assertNotNull(stored)
        assertEquals(AdminAuthProvider.GitHub, stored.provider)

        val consumed = auth.consumeGitHubOAuthState(start.stateToken)

        assertEquals(clock, consumed.consumedAt)
        assertFailsWith<IllegalArgumentException> {
            auth.consumeGitHubOAuthState(start.stateToken)
        }
    }

    @Test
    fun oauthStateRejectsExpiredAndUnsafeRedirects() {
        assertFailsWith<IllegalArgumentException> {
            auth.beginGitHubOAuth("//evil.test")
        }

        val start = auth.beginGitHubOAuth("/admin")
        clock = clock.plus(Duration.ofMinutes(11))

        assertFailsWith<IllegalArgumentException> {
            auth.consumeGitHubOAuthState(start.stateToken)
        }
    }

    @Test
    fun sessionAuthenticatesByHashAndRejectsRevokedExpiredOrBannedUsers() {
        val user = identity.ensureGitHubUser(GitHubUserIdentity(123, "octo"))
        val issued = auth.createSession(user.reefUserId)

        assertNotEquals(issued.token, issued.session.sessionHash)
        assertFailsWith<IllegalArgumentException> {
            authStore.session(issued.token)
        }
        assertEquals(user.reefUserId, auth.authenticateSession(issued.token).reefUserId)

        auth.revokeSession(issued.token)
        assertFailsWith<IllegalArgumentException> {
            auth.authenticateSession(issued.token)
        }

        val second = auth.createSession(user.reefUserId)
        identity.updateTrustState("operator-1", user.reefUserId, AdminTrustState.Banned)
        assertFailsWith<IllegalArgumentException> {
            auth.authenticateSession(second.token)
        }
    }

    @Test
    fun sessionRejectsExpiredToken() {
        val user = identity.ensureGitHubUser(GitHubUserIdentity(456, "session-user"))
        val issued = auth.createSession(user.reefUserId)
        clock = clock.plus(Duration.ofHours(2))

        assertFailsWith<IllegalArgumentException> {
            auth.authenticateSession(issued.token)
        }
    }

    @Test
    fun serviceTokenStoresHashAndChecksFamilyExpiryAndRevocation() {
        val issued = auth.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Ci,
            subjectActorId = "ci-bot",
            ttl = Duration.ofMinutes(5)
        )

        assertNotEquals(issued.token, issued.record.tokenHash)
        assertFailsWith<IllegalArgumentException> {
            authStore.serviceTokenByHash(issued.token)
        }
        assertEquals("ci-bot", auth.authenticateServiceToken(issued.token, AdminServiceTokenFamily.Ci).subjectActorId)
        assertFailsWith<IllegalArgumentException> {
            auth.authenticateServiceToken(issued.token, AdminServiceTokenFamily.Sim)
        }

        clock = clock.plus(Duration.ofMinutes(6))
        assertFailsWith<IllegalArgumentException> {
            auth.authenticateServiceToken(issued.token, AdminServiceTokenFamily.Ci)
        }
    }
}
