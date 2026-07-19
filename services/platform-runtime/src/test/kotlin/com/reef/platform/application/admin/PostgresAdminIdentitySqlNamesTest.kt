package com.reef.platform.application.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PostgresAdminIdentitySqlNamesTest {
    @Test
    fun defaultsIdentityTablesToAdminSchema() {
        val names = PostgresAdminIdentitySqlNames()

        assertEquals("admin.users", names.users)
        assertEquals("admin.roles", names.roles)
        assertEquals("admin.user_roles", names.userRoles)
        assertEquals("admin.audit_events", names.auditEvents)
    }

    @Test
    fun blankSchemaNameFallsBackToAdminSchema() {
        val names = PostgresAdminIdentitySqlNames(schema = "")

        assertFalse(names.users.startsWith("."))
        assertFalse(names.users.endsWith("."))
    }

    @Test
    fun rejectsUnsafeSchemaName() {
        assertFailsWith<IllegalArgumentException> {
            PostgresAdminIdentitySqlNames(schema = "admin;drop schema arena")
        }
    }
}
