package com.reef.platform.infrastructure.persistence

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresSchemaMigrationIntegrationTest {
    @Test
    fun migratedTablesLandInDomainSchemasWhenConfigured() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            val appliedMigrations = conn.prepareStatement(
                """
                SELECT migration_id
                FROM public.reef_schema_migrations
                WHERE migration_id IN (
                  'runtime/0003_live_runtime_persistence.sql',
                  'auth/0002_live_auth_tables.sql',
                  'boundary/0002_live_boundary_tables.sql'
                )
                ORDER BY migration_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows.add(rs.getString("migration_id"))
                    rows
                }
            }

            assertEquals(
                listOf(
                    "auth/0002_live_auth_tables.sql",
                    "boundary/0002_live_boundary_tables.sql",
                    "runtime/0003_live_runtime_persistence.sql"
                ),
                appliedMigrations
            )

            val expectedTables = setOf(
                "auth.auth_actor_roles",
                "auth.auth_roles",
                "boundary.api_command_captures",
                "boundary.api_idempotency_records",
                "runtime.executions",
                "runtime.orders",
                "runtime.reference_instruments",
                "runtime.runtime_events",
                "runtime.submit_results",
                "runtime.trades"
            )

            val actualTables = conn.prepareStatement(
                """
                SELECT table_schema || '.' || table_name AS table_name
                FROM information_schema.tables
                WHERE table_schema IN ('runtime', 'auth', 'boundary')
                  AND table_name IN (
                    'orders',
                    'executions',
                    'trades',
                    'runtime_events',
                    'submit_results',
                    'reference_instruments',
                    'auth_roles',
                    'auth_actor_roles',
                    'api_idempotency_records',
                    'api_command_captures'
                  )
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableSetOf<String>()
                    while (rs.next()) rows.add(rs.getString("table_name"))
                    rows
                }
            }

            assertEquals(expectedTables, actualTables)

            val publicTables = conn.prepareStatement(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'orders',
                    'executions',
                    'trades',
                    'runtime_events',
                    'submit_results',
                    'reference_instruments',
                    'auth_roles',
                    'auth_actor_roles',
                    'api_idempotency_records',
                    'api_command_captures'
                  )
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows.add(rs.getString("table_name"))
                    rows
                }
            }

            assertTrue(publicTables.isEmpty(), "unexpected public persistence tables: $publicTables")
        }
    }
}
