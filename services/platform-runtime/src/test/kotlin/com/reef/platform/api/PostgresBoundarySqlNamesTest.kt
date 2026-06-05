package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PostgresBoundarySqlNamesTest {
    @Test
    fun defaultsBoundaryTablesToBoundarySchema() {
        val names = PostgresBoundarySqlNames()

        assertEquals("boundary.api_idempotency_records", names.idempotencyRecords)
        assertEquals("boundary.api_command_captures", names.commandCaptures)
        assertEquals("idx_api_command_captures_status_updated", names.commandCapturesStatusUpdatedIndex)
    }

    @Test
    fun blankSchemaNameFallsBackToBoundarySchema() {
        val names = PostgresBoundarySqlNames(schema = "")

        assertFalse(names.idempotencyRecords.startsWith("."))
        assertFalse(names.idempotencyRecords.endsWith("."))
        assertFalse(names.commandCaptures.startsWith("."))
        assertFalse(names.commandCaptures.endsWith("."))
    }

    @Test
    fun rejectsUnsafeSchemaName() {
        assertFailsWith<IllegalArgumentException> {
            PostgresBoundarySqlNames(schema = "boundary;drop schema runtime")
        }
    }
}
