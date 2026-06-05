package com.reef.platform.infrastructure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresBootstrapModeTest {
    @Test
    fun defaultsToCompatWhenUnsetOrBlank() {
        assertEquals(PostgresBootstrapMode.Compat, PostgresBootstrapMode.from(null))
        assertEquals(PostgresBootstrapMode.Compat, PostgresBootstrapMode.from(""))
        assertEquals(PostgresBootstrapMode.Compat, PostgresBootstrapMode.from("   "))
    }

    @Test
    fun parsesCompatAndValidateCaseInsensitively() {
        assertEquals(PostgresBootstrapMode.Compat, PostgresBootstrapMode.from("compat"))
        assertEquals(PostgresBootstrapMode.Compat, PostgresBootstrapMode.from("COMPAT"))
        assertEquals(PostgresBootstrapMode.Validate, PostgresBootstrapMode.from("validate"))
        assertEquals(PostgresBootstrapMode.Validate, PostgresBootstrapMode.from("VALIDATE"))
    }

    @Test
    fun rejectsUnknownBootstrapMode() {
        assertFailsWith<IllegalArgumentException> {
            PostgresBootstrapMode.from("create")
        }
    }
}
