package com.reef.arena.controlplane.arena

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryArenaBotEntitlementStoreTest {
    private val now = Instant.parse("2026-07-18T12:00:00Z")

    @Test
    fun savesOwnershipsWithoutPlatformIdentityState() {
        val store = InMemoryArenaBotEntitlementStore()
        val ownership = ArenaBotOwnership(
            "user-gh-123",
            "sample-bot",
            ArenaBotOwnershipState.Owner,
            "operator-1",
            now
        )

        assertEquals(ownership, store.saveBotOwnership(ownership))
        assertEquals(listOf(ownership), store.botOwnershipsForUser("user-gh-123"))
        assertEquals(listOf(ownership), store.botOwnerships("sample-bot"))
    }

    @Test
    fun replacesEntitlementRecordsByTheirNaturalKeys() {
        val store = InMemoryArenaBotEntitlementStore()
        val revoked = ArenaBotOwnership(
            "user-gh-123",
            "sample-bot",
            ArenaBotOwnershipState.Revoked,
            "operator-2",
            now.plusSeconds(1)
        )

        store.saveBotOwnership(ArenaBotOwnership("user-gh-123", "sample-bot", ArenaBotOwnershipState.Owner, "operator-1", now))
        store.saveBotOwnership(revoked)

        assertEquals(listOf(revoked), store.botOwnerships("sample-bot"))
    }

    @Test
    fun rejectsInvalidArenaEntitlements() {
        val store = InMemoryArenaBotEntitlementStore()

        assertFailsWith<IllegalArgumentException> {
            store.saveBotOwnership(
                ArenaBotOwnership("not-a-user", "sample-bot", ArenaBotOwnershipState.Owner, "operator-1", now)
            )
        }
    }
}
