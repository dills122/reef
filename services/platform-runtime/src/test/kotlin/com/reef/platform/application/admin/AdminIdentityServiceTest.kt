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
    fun trustedOperatorCanManageReviewerRoleAndNonBannedTrustStatesWithReason() {
        val operator = service.ensureGitHubUser(GitHubUserIdentity(100, "operator"))
        val target = service.ensureGitHubUser(GitHubUserIdentity(101, "target"))
        service.updateTrustState("bootstrap", operator.reefUserId, AdminTrustState.Trusted)
        service.assignRole("bootstrap", operator.reefUserId, AdminIdentityService.RoleOperator)

        val binding = service.assignRoleByOperator(
            operator.reefUserId,
            target.reefUserId,
            AdminIdentityService.RoleReviewer,
            "ready to review submissions"
        )
        val updated = service.updateTrustStateByOperator(
            operator.reefUserId,
            target.reefUserId,
            AdminTrustState.Limited,
            "temporary moderation"
        )

        assertEquals(AdminIdentityService.RoleReviewer, binding.roleId)
        assertEquals(AdminTrustState.Limited, updated.trustState)
        assertEquals(
            listOf(
                "AdminUserCreated",
                "AdminUserRoleAssigned",
                "AdminAccessRoleAssigned",
                "AdminAccessTrustStateChanged"
            ),
            store.auditEvents("admin-user", target.reefUserId).map { it.eventType }
        )
    }

    @Test
    fun operatorCannotAssignPrivilegedRolesOrBanUsers() {
        val operator = service.ensureGitHubUser(GitHubUserIdentity(110, "operator-two"))
        val target = service.ensureGitHubUser(GitHubUserIdentity(111, "target-two"))
        service.updateTrustState("bootstrap", operator.reefUserId, AdminTrustState.Trusted)
        service.assignRole("bootstrap", operator.reefUserId, AdminIdentityService.RoleOperator)

        assertFailsWith<IllegalArgumentException> {
            service.assignRoleByOperator(
                operator.reefUserId,
                target.reefUserId,
                AdminIdentityService.RoleOperator,
                "promote to operator"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateTrustStateByOperator(
                operator.reefUserId,
                target.reefUserId,
                AdminTrustState.Banned,
                "ban requested"
            )
        }
    }

    @Test
    fun trustedPlatformAdminCanManagePrivilegedRolesAndBans() {
        val platformAdmin = service.ensureGitHubUser(GitHubUserIdentity(120, "platform-admin"))
        val target = service.ensureGitHubUser(GitHubUserIdentity(121, "target-three"))
        service.updateTrustState("bootstrap", platformAdmin.reefUserId, AdminTrustState.Trusted)
        service.assignRole("bootstrap", platformAdmin.reefUserId, AdminIdentityService.RolePlatformAdmin)

        service.assignRoleByOperator(
            platformAdmin.reefUserId,
            target.reefUserId,
            AdminIdentityService.RoleOperator,
            "trusted operations owner"
        )
        service.updateTrustStateByOperator(
            platformAdmin.reefUserId,
            target.reefUserId,
            AdminTrustState.Banned,
            "account compromise"
        )

        assertEquals(
            listOf(AdminIdentityService.RoleParticipant, AdminIdentityService.RoleOperator),
            service.rolesForUser(target.reefUserId).map { it.roleId }
        )
        assertEquals(AdminTrustState.Banned, service.user(target.reefUserId)?.trustState)
    }

    @Test
    fun accessMutationsRequireReasonAndRoleRevocationPolicy() {
        val platformAdmin = service.ensureGitHubUser(GitHubUserIdentity(130, "platform-admin-two"))
        val target = service.ensureGitHubUser(GitHubUserIdentity(131, "target-four"))
        service.updateTrustState("bootstrap", platformAdmin.reefUserId, AdminTrustState.Trusted)
        service.assignRole("bootstrap", platformAdmin.reefUserId, AdminIdentityService.RolePlatformAdmin)
        service.assignRole("bootstrap", target.reefUserId, AdminIdentityService.RoleReviewer)

        assertFailsWith<IllegalArgumentException> {
            service.assignRoleByOperator(
                platformAdmin.reefUserId,
                target.reefUserId,
                AdminIdentityService.RoleOperator,
                " "
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.revokeRoleByOperator(
                platformAdmin.reefUserId,
                target.reefUserId,
                AdminIdentityService.RoleParticipant,
                "remove participant"
            )
        }

        service.revokeRoleByOperator(
            platformAdmin.reefUserId,
            target.reefUserId,
            AdminIdentityService.RoleReviewer,
            "reviewer no longer needed"
        )

        assertEquals(
            listOf(AdminIdentityService.RoleParticipant),
            service.rolesForUser(target.reefUserId).map { it.roleId }
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
