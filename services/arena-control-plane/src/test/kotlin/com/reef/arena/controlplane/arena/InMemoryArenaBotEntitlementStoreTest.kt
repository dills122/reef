package com.reef.arena.controlplane.arena

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryArenaBotEntitlementStoreTest {
    private val now = Instant.parse("2026-07-18T12:00:00Z")

    @Test
    fun savesLimitsAndOwnershipsWithoutPlatformIdentityState() {
        val store = InMemoryArenaBotEntitlementStore()
        val limit = ArenaUserBotLimit("user-gh-123", 4, 2, 10, "operator-1", now)
        val ownership = ArenaBotOwnership(
            "user-gh-123",
            "sample-bot",
            ArenaBotOwnershipState.Owner,
            "operator-1",
            now
        )

        assertEquals(limit, store.saveUserBotLimit(limit))
        assertEquals(limit, store.userBotLimit("user-gh-123"))
        assertEquals(ownership, store.saveBotOwnership(ownership))
        assertEquals(listOf(ownership), store.botOwnershipsForUser("user-gh-123"))
        assertEquals(listOf(ownership), store.botOwnerships("sample-bot"))
    }

    @Test
    fun replacesEntitlementRecordsByTheirNaturalKeys() {
        val store = InMemoryArenaBotEntitlementStore()
        store.saveUserBotLimit(ArenaUserBotLimit("user-gh-123", 2, 1, 5, "operator-1", now))
        val revoked = ArenaBotOwnership(
            "user-gh-123",
            "sample-bot",
            ArenaBotOwnershipState.Revoked,
            "operator-2",
            now.plusSeconds(1)
        )

        store.saveUserBotLimit(ArenaUserBotLimit("user-gh-123", 4, 2, 10, "operator-2", now.plusSeconds(1)))
        store.saveBotOwnership(ArenaBotOwnership("user-gh-123", "sample-bot", ArenaBotOwnershipState.Owner, "operator-1", now))
        store.saveBotOwnership(revoked)

        assertEquals(4, store.userBotLimit("user-gh-123")?.maxBots)
        assertEquals(listOf(revoked), store.botOwnerships("sample-bot"))
    }

    @Test
    fun rejectsInvalidArenaEntitlements() {
        val store = InMemoryArenaBotEntitlementStore()

        assertFailsWith<IllegalArgumentException> {
            store.saveUserBotLimit(ArenaUserBotLimit("user-gh-123", 1, 2, 1, "operator-1", now))
        }
        assertFailsWith<IllegalArgumentException> {
            store.saveBotOwnership(
                ArenaBotOwnership("not-a-user", "sample-bot", ArenaBotOwnershipState.Owner, "operator-1", now)
            )
        }
    }
}
