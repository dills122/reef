package com.reef.platform.application.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PostgresAdminAuthSqlNamesTest {
    @Test
    fun defaultsAuthTablesToAdminSchema() {
        val names = PostgresAdminAuthSqlNames()

        assertEquals("admin.oauth_states", names.oauthStates)
        assertEquals("admin.sessions", names.sessions)
        assertEquals("admin.service_tokens", names.serviceTokens)
    }

    @Test
    fun blankSchemaNameFallsBackToAdminSchema() {
        val names = PostgresAdminAuthSqlNames(schema = "")

        assertFalse(names.sessions.startsWith("."))
        assertFalse(names.sessions.endsWith("."))
    }

    @Test
    fun rejectsUnsafeSchemaName() {
        assertFailsWith<IllegalArgumentException> {
            PostgresAdminAuthSqlNames(schema = "admin;drop schema admin")
        }
    }
}
