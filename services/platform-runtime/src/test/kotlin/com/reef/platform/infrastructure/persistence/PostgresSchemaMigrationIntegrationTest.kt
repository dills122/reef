package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.DefaultIdempotencyRetentionPolicy
import com.reef.platform.api.PostgresCommandCaptureStore
import com.reef.platform.api.PostgresIdempotencyStore
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
                  'boundary/0002_live_boundary_tables.sql',
                  'boundary/0003_command_capture_live_shape.sql',
                  'boundary/0004_command_capture_legacy_defaults.sql'
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
                    "boundary/0003_command_capture_live_shape.sql",
                    "boundary/0004_command_capture_legacy_defaults.sql",
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

            val commandCaptureColumns = conn.prepareStatement(
                """
                SELECT column_name || ':' || data_type AS column_name
                FROM information_schema.columns
                WHERE table_schema = 'boundary'
                  AND table_name = 'api_command_captures'
                  AND column_name IN (
                    'command_id',
                    'correlation_id',
                    'created_at',
                    'request_payload',
                    'response_status',
                    'response_payload',
                    'error_class',
                    'error_message',
                    'first_received_at',
                    'last_updated_at'
                  )
                ORDER BY column_name
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows.add(rs.getString("column_name"))
                    rows
                }
            }

            assertEquals(
                listOf(
                    "command_id:text",
                    "correlation_id:text",
                    "created_at:text",
                    "error_class:text",
                    "error_message:text",
                    "first_received_at:timestamp with time zone",
                    "last_updated_at:timestamp with time zone",
                    "request_payload:text",
                    "response_payload:text",
                    "response_status:integer"
                ),
                commandCaptureColumns
            )

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

    @Test
    fun validateModeConstructorsAcceptMigratedSchemaWhenConfigured() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val runtimeDataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val boundaryDataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)

        PostgresRuntimePersistence(
            dataSource = runtimeDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresIdempotencyStore(
            dataSource = boundaryDataSource,
            retentionPolicy = DefaultIdempotencyRetentionPolicy(),
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresCommandCaptureStore(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
    }
}
