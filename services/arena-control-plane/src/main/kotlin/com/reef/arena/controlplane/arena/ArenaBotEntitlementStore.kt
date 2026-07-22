package com.reef.arena.controlplane.arena

import java.time.Instant

enum class ArenaBotOwnershipState(val dbValue: String) {
    Owner("owner"),
    Maintainer("maintainer"),
    Revoked("revoked");

    companion object {
        fun fromDb(value: String): ArenaBotOwnershipState {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unsupported Arena bot ownership state: $value")
        }
    }
}

data class ArenaBotOwnership(
    val reefUserId: String,
    val botId: String,
    val ownershipState: ArenaBotOwnershipState,
    val assignedBy: String,
    val assignedAt: Instant
)

interface ArenaBotEntitlementStore {
    fun saveBotOwnership(ownership: ArenaBotOwnership): ArenaBotOwnership
    fun botOwnershipsForUser(reefUserId: String): List<ArenaBotOwnership>
    fun botOwnerships(botId: String): List<ArenaBotOwnership>
    /**
     * Batch lookup of ownerships for a page of bot ids in a single round trip.
     *
     * Stores backed by a single query surface (e.g. Postgres) should override this
     * with one `WHERE bot_id = ANY(?)`-style query; the default keeps small
     * in-memory/test stores source-compatible.
     */
    fun ownershipsForBots(botIds: List<String>): Map<String, List<ArenaBotOwnership>> =
        botIds.distinct().associateWith { botOwnerships(it) }
}

class InMemoryArenaBotEntitlementStore : ArenaBotEntitlementStore {
    private val ownerships = linkedMapOf<Pair<String, String>, ArenaBotOwnership>()

    override fun saveBotOwnership(ownership: ArenaBotOwnership): ArenaBotOwnership {
        ArenaBotEntitlementValidation.reefUserId(ownership.reefUserId)
        ArenaBotEntitlementValidation.botId(ownership.botId)
        ArenaBotEntitlementValidation.actorId(ownership.assignedBy)
        ownerships[ownership.reefUserId to ownership.botId] = ownership
        return ownership
    }

    override fun botOwnershipsForUser(reefUserId: String): List<ArenaBotOwnership> {
        val userId = ArenaBotEntitlementValidation.reefUserId(reefUserId)
        return ownerships.values.filter { it.reefUserId == userId }
    }

    override fun botOwnerships(botId: String): List<ArenaBotOwnership> {
        val id = ArenaBotEntitlementValidation.botId(botId)
        return ownerships.values.filter { it.botId == id }
    }

    override fun ownershipsForBots(botIds: List<String>): Map<String, List<ArenaBotOwnership>> {
        val ids = botIds.distinct().map { ArenaBotEntitlementValidation.botId(it) }.toSet()
        return ownerships.values
            .filter { it.botId in ids }
            .groupBy { it.botId }
    }
}

object ArenaBotEntitlementValidation {
    private val reefUserIdPattern = Regex("user-gh-[1-9][0-9]{0,19}")
    private val actorIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}")
    private val botIdPattern = Regex("[a-z0-9][a-z0-9._-]{2,63}")

    fun reefUserId(value: String): String {
        val id = value.trim()
        require(reefUserIdPattern.matches(id)) { "invalid reefUserId" }
        return id
    }

    fun actorId(value: String): String {
        val id = value.trim()
        require(actorIdPattern.matches(id)) { "invalid actorId" }
        return id
    }

    fun botId(value: String): String {
        val id = value.trim()
        require(botIdPattern.matches(id)) { "invalid botId" }
        return id
    }

}
