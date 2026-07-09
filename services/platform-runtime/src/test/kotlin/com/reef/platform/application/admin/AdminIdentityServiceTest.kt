package com.reef.platform.application.admin

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AdminIdentityServiceTest {
    private var clock = Instant.parse("2026-07-09T12:00:00Z")
    private var eventCounter = 0
    private val store = InMemoryAdminIdentityStore()
    private val service = AdminIdentityService(
        store = store,
        now = { clock },
        eventIdFactory = { "evt-${++eventCounter}" }
    )

    @Test
    fun ensuresGithubUserWithParticipantRoleAndDefaultLimits() {
        val user = service.ensureGitHubUser(
            GitHubUserIdentity(
                githubUserId = 12345,
                githubLogin = "octo-dev",
                displayName = "Octo Dev"
            )
        )

        assertEquals("user-gh-12345", user.reefUserId)
        assertEquals(AdminTrustState.New, user.trustState)
        assertEquals(listOf(AdminIdentityService.RoleParticipant), service.rolesForUser(user.reefUserId).map { it.roleId })
        assertEquals(
            AdminUserBotLimit(
                reefUserId = user.reefUserId,
                maxBots = AdminIdentityService.DefaultMaxBots,
                maxActiveBots = AdminIdentityService.DefaultMaxActiveBots,
                maxVersionSubmissionsPerDay = AdminIdentityService.DefaultMaxVersionSubmissionsPerDay,
                updatedBy = "github-oauth",
                updatedAt = clock
            ),
            service.botLimit(user.reefUserId)
        )
        assertEquals(
            listOf("AdminUserCreated"),
            store.auditEvents("admin-user", user.reefUserId).map { it.eventType }
        )
    }

    @Test
    fun updatesGithubUserDisplayDataWithoutResettingTrustOrCreatedAt() {
        val created = service.ensureGitHubUser(GitHubUserIdentity(12345, "old-login", "Old Name"))
        service.updateTrustState("operator-1", created.reefUserId, AdminTrustState.Trusted)
        clock = Instant.parse("2026-07-10T12:00:00Z")

        val seen = service.ensureGitHubUser(GitHubUserIdentity(12345, "new-login", "New Name"))

        assertEquals(created.reefUserId, seen.reefUserId)
        assertEquals("new-login", seen.githubLogin)
        assertEquals("New Name", seen.displayName)
        assertEquals(AdminTrustState.Trusted, seen.trustState)
        assertEquals(created.createdAt, seen.createdAt)
        assertEquals(clock, seen.lastSeenAt)
        assertEquals(
            listOf("AdminUserCreated", "AdminUserTrustStateUpdated", "AdminUserSeen"),
            store.auditEvents("admin-user", created.reefUserId).map { it.eventType }
        )
    }

    @Test
    fun assignsRoleAndBotOwnership() {
        val user = service.ensureGitHubUser(GitHubUserIdentity(999, "bot-maker"))

        service.assignRole("operator-1", user.reefUserId, AdminIdentityService.RoleReviewer)
        val ownership = service.assignBotOwnership(
            "operator-1",
            AdminBotOwnershipCommand(
                reefUserId = user.reefUserId,
                botId = "sample-bot",
                ownershipState = AdminBotOwnershipState.Owner
            )
        )

        assertEquals(
            listOf(AdminIdentityService.RoleParticipant, AdminIdentityService.RoleReviewer),
            service.rolesForUser(user.reefUserId).map { it.roleId }
        )
        assertEquals(ownership, store.botOwnerships("sample-bot").single())
        assertEquals(
            listOf("AdminUserBotOwnershipAssigned"),
            store.auditEvents("arena-bot", "sample-bot").map { it.eventType }
        )
    }

    @Test
    fun rejectsInvalidGithubIdentityAndLimits() {
        assertFailsWith<IllegalArgumentException> {
            service.ensureGitHubUser(GitHubUserIdentity(0, "octo"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.ensureGitHubUser(GitHubUserIdentity(1, " "))
        }
        assertFailsWith<IllegalArgumentException> {
            service.ensureGitHubUser(GitHubUserIdentity(2, "-octo"))
        }

        val user = service.ensureGitHubUser(GitHubUserIdentity(1, "octo"))
        assertFailsWith<IllegalArgumentException> {
            service.updateBotLimits(
                "operator-1",
                AdminBotLimitCommand(
                    reefUserId = user.reefUserId,
                    maxBots = -1,
                    maxActiveBots = 1,
                    maxVersionSubmissionsPerDay = 1
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateBotLimits(
                "operator-1",
                AdminBotLimitCommand(
                    reefUserId = user.reefUserId,
                    maxBots = 1,
                    maxActiveBots = 2,
                    maxVersionSubmissionsPerDay = 1
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.assignBotOwnership(
                "operator-1",
                AdminBotOwnershipCommand(
                    reefUserId = user.reefUserId,
                    botId = "../../bad"
                )
            )
        }
        assertNotNull(store.userByGithubUserId(1))
    }
}
