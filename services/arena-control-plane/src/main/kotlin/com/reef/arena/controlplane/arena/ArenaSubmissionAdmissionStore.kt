package com.reef.arena.controlplane.arena

import java.time.Instant

/**
 * Arena-owned admission record for an untrusted bot-submission pull request.
 *
 * This is intentionally separate from bot-version lifecycle: a bot version is
 * registered only after merge, while admission records the pre-merge GitHub
 * trust decision bound to an immutable user id and exact reviewed head SHA.
 */
enum class ArenaSubmissionAdmissionState(val dbValue: String) {
    PendingInviteReview("pending_invite_review"),
    InviteApproved("invite_approved");

    companion object {
        fun fromDb(value: String): ArenaSubmissionAdmissionState =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unsupported Arena submission admission state: $value")
    }
}

data class ArenaSubmissionAdmission(
    val repository: String,
    val pullRequestNumber: Long,
    val botId: String,
    val headRepository: String,
    val headOwnerLogin: String,
    val githubUserId: Long,
    val githubLogin: String,
    val headSha: String,
    val state: ArenaSubmissionAdmissionState,
    val invitationActor: String? = null,
    val invitationReason: String = "",
    val invitedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class RecordArenaSubmissionPendingCommand(
    val repository: String,
    val pullRequestNumber: Long,
    val botId: String,
    val headRepository: String,
    val headOwnerLogin: String,
    val githubUserId: Long,
    val githubLogin: String,
    val headSha: String,
    val occurredAt: Instant
)

data class ApproveArenaSubmissionInviteCommand(
    val repository: String,
    val pullRequestNumber: Long,
    val approvedHeadSha: String,
    val actorId: String,
    val reason: String,
    val occurredAt: Instant
)

interface ArenaSubmissionAdmissionStore {
    /**
     * Creates a pending admission record. A new head SHA revokes any previous
     * invitation by returning the record to pending review.
     */
    fun recordPending(command: RecordArenaSubmissionPendingCommand): ArenaSubmissionAdmission

    /** Approval is only valid for the exact current head SHA. */
    fun approveInvite(command: ApproveArenaSubmissionInviteCommand): ArenaSubmissionAdmission

    fun admission(repository: String, pullRequestNumber: Long): ArenaSubmissionAdmission?
}

class InMemoryArenaSubmissionAdmissionStore : ArenaSubmissionAdmissionStore {
    private val admissions = linkedMapOf<Pair<String, Long>, ArenaSubmissionAdmission>()

    override fun recordPending(command: RecordArenaSubmissionPendingCommand): ArenaSubmissionAdmission {
        val normalized = ArenaSubmissionAdmissionValidation.recordPending(command)
        val key = normalized.repository to normalized.pullRequestNumber
        val existing = admissions[key]
        val next = if (existing == null) {
            ArenaSubmissionAdmissionValidation.toAdmission(normalized)
        } else if (existing.headSha != normalized.headSha) {
            ArenaSubmissionAdmissionValidation.toAdmission(normalized, existing.createdAt)
        } else {
            existing.copy(
                botId = normalized.botId,
                headRepository = normalized.headRepository,
                headOwnerLogin = normalized.headOwnerLogin,
                githubUserId = normalized.githubUserId,
                githubLogin = normalized.githubLogin,
                updatedAt = normalized.occurredAt
            )
        }
        admissions[key] = next
        return next
    }

    override fun approveInvite(command: ApproveArenaSubmissionInviteCommand): ArenaSubmissionAdmission {
        val normalized = ArenaSubmissionAdmissionValidation.approveInvite(command)
        val key = normalized.repository to normalized.pullRequestNumber
        val existing = requireNotNull(admissions[key]) { "submission admission not found" }
        require(existing.headSha == normalized.approvedHeadSha) { "submission head SHA changed; invitation approval is invalid" }
        val approved = existing.copy(
            state = ArenaSubmissionAdmissionState.InviteApproved,
            invitationActor = normalized.actorId,
            invitationReason = normalized.reason,
            invitedAt = normalized.occurredAt,
            updatedAt = normalized.occurredAt
        )
        admissions[key] = approved
        return approved
    }

    override fun admission(repository: String, pullRequestNumber: Long): ArenaSubmissionAdmission? =
        admissions[ArenaSubmissionAdmissionValidation.repository(repository) to ArenaSubmissionAdmissionValidation.pullRequestNumber(pullRequestNumber)]
}

object ArenaSubmissionAdmissionValidation {
    private val repositoryPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val githubLoginPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    private val shaPattern = Regex("[0-9a-fA-F]{40,64}")

    fun recordPending(command: RecordArenaSubmissionPendingCommand): RecordArenaSubmissionPendingCommand = command.copy(
        repository = repository(command.repository),
        pullRequestNumber = pullRequestNumber(command.pullRequestNumber),
        botId = ArenaBotEntitlementValidation.botId(command.botId),
        headRepository = repository(command.headRepository),
        headOwnerLogin = githubLogin(command.headOwnerLogin),
        githubUserId = githubUserId(command.githubUserId),
        githubLogin = githubLogin(command.githubLogin),
        headSha = headSha(command.headSha)
    )

    fun approveInvite(command: ApproveArenaSubmissionInviteCommand): ApproveArenaSubmissionInviteCommand = command.copy(
        repository = repository(command.repository),
        pullRequestNumber = pullRequestNumber(command.pullRequestNumber),
        approvedHeadSha = headSha(command.approvedHeadSha),
        actorId = ArenaBotEntitlementValidation.actorId(command.actorId),
        reason = command.reason.trim().also { require(it.isNotEmpty() && it.length <= 500) { "invalid invitation reason" } }
    )

    fun repository(value: String): String = value.trim().also { require(repositoryPattern.matches(it)) { "invalid repository" } }
    fun pullRequestNumber(value: Long): Long = value.also { require(it > 0) { "invalid pullRequestNumber" } }
    private fun githubUserId(value: Long): Long = value.also { require(it > 0) { "invalid githubUserId" } }
    private fun githubLogin(value: String): String = value.trim().also { require(githubLoginPattern.matches(it)) { "invalid GitHub login" } }
    private fun headSha(value: String): String = value.trim().lowercase().also { require(shaPattern.matches(it)) { "invalid head SHA" } }

    fun toAdmission(command: RecordArenaSubmissionPendingCommand, createdAt: Instant = command.occurredAt) = ArenaSubmissionAdmission(
        repository = command.repository,
        pullRequestNumber = command.pullRequestNumber,
        botId = command.botId,
        headRepository = command.headRepository,
        headOwnerLogin = command.headOwnerLogin,
        githubUserId = command.githubUserId,
        githubLogin = command.githubLogin,
        headSha = command.headSha,
        state = ArenaSubmissionAdmissionState.PendingInviteReview,
        createdAt = createdAt,
        updatedAt = command.occurredAt
    )
}
