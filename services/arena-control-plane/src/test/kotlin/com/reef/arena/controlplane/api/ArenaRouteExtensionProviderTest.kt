package com.reef.arena.controlplane.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArenaRouteExtensionProviderTest {
    @Test
    fun accountRiskControlsUseTheRuntimeDatabase() {
        val environment = mapOf(
            "RUNTIME_POSTGRES_JDBC_URL" to "jdbc:postgresql://runtime:5432/reef",
            "RUNTIME_POSTGRES_USER" to "runtime_app",
            "RUNTIME_POSTGRES_PASSWORD" to "runtime-secret",
            "ADMIN_POSTGRES_JDBC_URL" to "jdbc:postgresql://admin:5432/admin",
            "ADMIN_POSTGRES_USER" to "admin_app",
            "ADMIN_POSTGRES_PASSWORD" to "admin-secret"
        )

        val settings = arenaAccountRiskDatabaseSettings(environment::get)

        assertEquals("jdbc:postgresql://runtime:5432/reef", settings.jdbcUrl)
        assertEquals("runtime_app", settings.user)
        assertEquals("runtime-secret", settings.password)
    }

    @Test
    fun accountRiskControlsRequireTheRuntimeDatabase() {
        assertFailsWith<IllegalStateException> {
            arenaAccountRiskDatabaseSettings(emptyMap<String, String>()::get)
        }
    }
}
