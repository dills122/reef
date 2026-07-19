package com.reef.arena.controlplane.arena

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InMemoryArenaSubmissionAdmissionStoreTest {
    private val now = Instant.parse("2026-07-19T12:00:00Z")
    private val shaOne = "a".repeat(40)
    private val shaTwo = "b".repeat(40)

    @Test
    fun recordsForkSubmissionAsPendingInviteReview() {
        val store = InMemoryArenaSubmissionAdmissionStore()

        val admission = store.recordPending(pending())

        assertEquals(ArenaSubmissionAdmissionState.PendingInviteReview, admission.state)
        assertEquals(4242L, admission.githubUserId)
        assertEquals(shaOne, admission.headSha)
        assertNull(admission.invitationActor)
        assertEquals(admission, store.admission("dills122/reef", 42))
    }

    @Test
    fun approvalIsBoundToCurrentHeadSha() {
        val store = InMemoryArenaSubmissionAdmissionStore()
        store.recordPending(pending())

        val approved = store.approveInvite(approval())

        assertEquals(ArenaSubmissionAdmissionState.InviteApproved, approved.state)
        assertEquals("reviewer-1", approved.invitationActor)
        assertEquals("invite verified", approved.invitationReason)
        assertFailsWith<IllegalArgumentException> { store.approveInvite(approval(shaTwo)) }
    }

    @Test
    fun newHeadShaInvalidatesApprovalAndPreservesCreationTime() {
        val store = InMemoryArenaSubmissionAdmissionStore()
        val pending = store.recordPending(pending())
        store.approveInvite(approval())

        val changed = store.recordPending(pending(sha = shaTwo, occurredAt = now.plusSeconds(10)))

        assertEquals(ArenaSubmissionAdmissionState.PendingInviteReview, changed.state)
        assertEquals(shaTwo, changed.headSha)
        assertNull(changed.invitationActor)
        assertEquals(pending.createdAt, changed.createdAt)
        assertEquals(now.plusSeconds(10), changed.updatedAt)
    }

    @Test
    fun rejectsInvalidOrUnknownAdmissionCommands() {
        val store = InMemoryArenaSubmissionAdmissionStore()

        assertFailsWith<IllegalArgumentException> { store.recordPending(pending(sha = "not-a-sha")) }
        assertFailsWith<IllegalArgumentException> { store.approveInvite(approval()) }
    }

    private fun pending(sha: String = shaOne, occurredAt: Instant = now) = RecordArenaSubmissionPendingCommand(
        repository = "dills122/reef",
        pullRequestNumber = 42,
        botId = "sample-bot",
        headRepository = "contributor/reef",
        headOwnerLogin = "octo-user",
        githubUserId = 4242,
        githubLogin = "octo-user",
        headSha = sha,
        occurredAt = occurredAt
    )

    private fun approval(sha: String = shaOne) = ApproveArenaSubmissionInviteCommand(
        repository = "dills122/reef",
        pullRequestNumber = 42,
        approvedHeadSha = sha,
        actorId = "reviewer-1",
        reason = "invite verified",
        occurredAt = now.plusSeconds(1)
    )
}
