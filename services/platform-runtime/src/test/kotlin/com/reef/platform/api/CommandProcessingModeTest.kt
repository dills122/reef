package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandProcessingModeTest {
    @Test
    fun defaultsToSyncResultWhenUnsetOrBlank() {
        assertEquals(CommandProcessingMode.SyncResult, CommandProcessingMode.from(null))
        assertEquals(CommandProcessingMode.SyncResult, CommandProcessingMode.from(""))
        assertEquals(CommandProcessingMode.SyncResult, CommandProcessingMode.from("   "))
    }

    @Test
    fun parsesSupportedModes() {
        assertEquals(CommandProcessingMode.SyncResult, CommandProcessingMode.from("sync-result"))
        assertEquals(CommandProcessingMode.CapturedSyncEngine, CommandProcessingMode.from("captured-sync-engine"))
        assertEquals(CommandProcessingMode.CapturedAck, CommandProcessingMode.from("captured-ack"))
        assertEquals(CommandProcessingMode.StreamAck, CommandProcessingMode.from("stream-ack"))
        assertEquals(CommandProcessingMode.AcceptedAsync, CommandProcessingMode.from("accepted-async"))
    }

    @Test
    fun rejectsUnsupportedModes() {
        assertFailsWith<IllegalArgumentException> {
            CommandProcessingMode.from("async")
        }
    }

    @Test
    fun readsFromEnvironmentLookup() {
        val mode = CommandProcessingMode.fromEnv { key ->
            when (key) {
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" -> "captured-sync-engine"
                else -> null
            }
        }

        assertEquals(CommandProcessingMode.CapturedSyncEngine, mode)
    }
}
