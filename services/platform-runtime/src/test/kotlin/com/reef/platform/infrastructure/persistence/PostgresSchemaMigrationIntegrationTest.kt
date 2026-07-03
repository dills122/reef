package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.DefaultIdempotencyRetentionPolicy
import com.reef.platform.api.PostgresCommandCaptureStore
import com.reef.platform.api.PostgresCommandLogStore
import com.reef.platform.api.PostgresIdempotencyStore
import com.reef.platform.domain.RuntimeEvent
import java.sql.DriverManager
import java.util.UUID
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
                  'runtime/0004_bulk_submit_outcomes.sql',
                  'runtime/0005_set_based_submit_outcomes.sql',
                  'runtime/0006_canonical_append_store.sql',
                  'auth/0002_live_auth_tables.sql',
                  'boundary/0002_live_boundary_tables.sql',
                  'boundary/0003_command_capture_live_shape.sql',
                  'boundary/0004_command_capture_legacy_defaults.sql',
                  'command_log/0001_commands.sql',
                  'command_log/0002_command_results.sql',
                  'command_log/0003_queue_result_split.sql',
                  'command_log/0004_terminal_results_active_queue.sql',
                  'command_log/0005_result_terminal_metadata.sql',
                  'command_log/0006_command_append_function.sql',
                  'command_log/0007_retention_pins.sql',
                  'command_log/0008_command_append_queue_timestamp.sql',
                  'command_log/0009_run_metadata.sql',
                  'command_log/0010_drop_legacy_status_index.sql',
                  'command_log/0011_unlogged_active_queue.sql',
                  'command_log/0012_command_payloads.sql',
                  'command_log/0013_drop_hot_path_foreign_keys.sql'
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
                    "command_log/0001_commands.sql",
                    "command_log/0002_command_results.sql",
                    "command_log/0003_queue_result_split.sql",
                    "command_log/0004_terminal_results_active_queue.sql",
                    "command_log/0005_result_terminal_metadata.sql",
                    "command_log/0006_command_append_function.sql",
                    "command_log/0007_retention_pins.sql",
                    "command_log/0008_command_append_queue_timestamp.sql",
                    "command_log/0009_run_metadata.sql",
                    "command_log/0010_drop_legacy_status_index.sql",
                    "command_log/0011_unlogged_active_queue.sql",
                    "command_log/0012_command_payloads.sql",
                    "command_log/0013_drop_hot_path_foreign_keys.sql",
                    "runtime/0003_live_runtime_persistence.sql",
                    "runtime/0004_bulk_submit_outcomes.sql",
                    "runtime/0005_set_based_submit_outcomes.sql",
                    "runtime/0006_canonical_append_store.sql"
                ),
                appliedMigrations
            )

            val expectedTables = setOf(
                "auth.auth_actor_roles",
                "auth.auth_roles",
                "boundary.api_command_captures",
                "boundary.api_idempotency_records",
                "command_log.command_payloads",
                "command_log.command_results",
                "command_log.command_work_queue",
                "command_log.commands",
                "command_log.retention_pins",
                "runtime.executions",
                "runtime.orders",
                "runtime.reference_instruments",
                "runtime.runtime_events",
                "runtime.submit_results",
                "runtime.canonical_command_results",
                "runtime.canonical_venue_events",
                "runtime.trades"
            )

            val actualTables = conn.prepareStatement(
                """
                SELECT table_schema || '.' || table_name AS table_name
                FROM information_schema.tables
                WHERE table_schema IN ('runtime', 'auth', 'boundary', 'command_log')
                  AND table_name IN (
                    'orders',
                    'executions',
                    'trades',
                    'runtime_events',
                    'submit_results',
                    'canonical_command_results',
                    'canonical_venue_events',
                    'reference_instruments',
                    'auth_roles',
                    'auth_actor_roles',
                    'api_idempotency_records',
                    'api_command_captures',
                    'commands',
                    'command_payloads',
                    'command_work_queue',
                    'command_results',
                    'retention_pins'
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

            val retentionPinColumns = conn.prepareStatement(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'command_log'
                  AND table_name = 'retention_pins'
                  AND column_name IN (
                    'pin_id',
                    'selector_type',
                    'selector_value',
                    'reason',
                    'created_at',
                    'updated_at'
                  )
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableSetOf<String>()
                    while (rs.next()) rows.add(rs.getString("column_name"))
                    rows
                }
            }

            assertEquals(
                setOf(
                    "pin_id",
                    "selector_type",
                    "selector_value",
                    "reason",
                    "created_at",
                    "updated_at"
                ),
                retentionPinColumns
            )

            val commandLogFunctions = conn.prepareStatement(
                """
                SELECT routine_schema || '.' || routine_name AS routine_name
                FROM information_schema.routines
                WHERE routine_schema = 'command_log'
                  AND routine_name = 'command_append'
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableSetOf<String>()
                    while (rs.next()) rows.add(rs.getString("routine_name"))
                    rows
                }
            }

            assertEquals(setOf("command_log.command_append"), commandLogFunctions)

            val commandLogForeignKeys = conn.prepareStatement(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conrelid IN (
                  'command_log.command_payloads'::regclass,
                  'command_log.command_work_queue'::regclass,
                  'command_log.command_results'::regclass
                )
                  AND contype = 'f'
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows.add(rs.getString("conname"))
                    rows
                }
            }

            assertTrue(commandLogForeignKeys.isEmpty(), "unexpected command-log hot-path foreign keys: $commandLogForeignKeys")

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

            val runtimeEventColumns = conn.prepareStatement(
                """
                SELECT column_name || ':' || data_type AS column_name
                FROM information_schema.columns
                WHERE table_schema = 'runtime'
                  AND table_name = 'runtime_events'
                  AND column_name IN (
                    'event_id',
                    'occurred_at',
                    'actor_id',
                    'payload_json',
                    'sequence_number'
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
                    "actor_id:text",
                    "event_id:text",
                    "occurred_at:text",
                    "payload_json:jsonb",
                    "sequence_number:bigint"
                ),
                runtimeEventColumns
            )

            val runtimeFunctions = conn.prepareStatement(
                """
                SELECT routine_schema || '.' || routine_name AS routine_name
                FROM information_schema.routines
                WHERE routine_schema = 'runtime'
                  AND routine_name IN (
                    'runtime_validate_reference_data',
                    'runtime_persist_submit_outcome',
                    'runtime_persist_submit_outcomes',
                    'runtime_append_canonical_submit_outcomes'
                  )
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableSetOf<String>()
                    while (rs.next()) rows.add(rs.getString("routine_name"))
                    rows
                }
            }

            assertEquals(
                setOf(
                    "runtime.runtime_validate_reference_data",
                    "runtime.runtime_persist_submit_outcome",
                    "runtime.runtime_persist_submit_outcomes",
                    "runtime.runtime_append_canonical_submit_outcomes"
                ),
                runtimeFunctions
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
                    'canonical_command_results',
                    'canonical_venue_events',
                    'reference_instruments',
                    'auth_roles',
                    'auth_actor_roles',
                    'api_idempotency_records',
                    'api_command_captures',
                    'commands'
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
        ).let { runtimePersistence ->
            val traceId = "trace-schema-${UUID.randomUUID()}"
            runtimePersistence.saveEvent(
                RuntimeEvent(
                    eventId = "evt-schema-${UUID.randomUUID()}",
                    eventType = "SchemaRoundTrip",
                    orderId = "ord-schema",
                    traceId = traceId,
                    causationId = "cmd-schema",
                    correlationId = "corr-schema",
                    actorId = "actor-schema",
                    producer = "platform-runtime-test",
                    schemaVersion = "v1",
                    payloadJson = """{"source":"postgres-integration"}""",
                    occurredAt = "2026-03-14T18:00:00Z"
                )
            )
            val event = runtimePersistence.eventsForTrace(traceId).single()
            assertEquals("actor-schema", event.actorId)
            assertTrue(event.payloadJson.contains("postgres-integration"), event.payloadJson)
        }
        PostgresIdempotencyStore(
            dataSource = boundaryDataSource,
            retentionPolicy = DefaultIdempotencyRetentionPolicy(),
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresCommandCaptureStore(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresCommandLogStore(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
    }

    @Test
    fun validateModeCommandCaptureStorePersistsReceivedCompletedAndFailedStates() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val store = PostgresCommandCaptureStore(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val clientId = "client-$suffix"
        val route = "/api/v1/orders/submit"
        val completedKey = "completed-$suffix"
        val failedKey = "failed-$suffix"

        store.captureReceived(
            clientId = clientId,
            route = route,
            idempotencyKey = completedKey,
            correlationId = "corr-completed",
            requestPayload = """{"commandId":"cmd-completed"}"""
        )
        store.markCompleted(
            clientId = clientId,
            route = route,
            idempotencyKey = completedKey,
            responseStatus = 200,
            responsePayload = """{"accepted":true}"""
        )
        store.captureReceived(
            clientId = clientId,
            route = route,
            idempotencyKey = failedKey,
            correlationId = "corr-failed",
            requestPayload = """{"commandId":"cmd-failed"}"""
        )
        store.markFailed(
            clientId = clientId,
            route = route,
            idempotencyKey = failedKey,
            responseStatus = 503,
            errorClass = "BOUNDARY_UNAVAILABLE",
            errorMessage = "capture failed"
        )

        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                SELECT idempotency_key, correlation_id, status, response_status, response_payload, error_class, error_message
                FROM boundary.api_command_captures
                WHERE client_id = ? AND route = ?
                ORDER BY idempotency_key
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(completedKey, rs.getString("idempotency_key"))
                    assertEquals("corr-completed", rs.getString("correlation_id"))
                    assertEquals("COMPLETED", rs.getString("status"))
                    assertEquals(200, rs.getInt("response_status"))
                    assertEquals("""{"accepted":true}""", rs.getString("response_payload"))
                    assertEquals("", rs.getString("error_class"))
                    assertEquals("", rs.getString("error_message"))

                    assertTrue(rs.next())
                    assertEquals(failedKey, rs.getString("idempotency_key"))
                    assertEquals("corr-failed", rs.getString("correlation_id"))
                    assertEquals("FAILED", rs.getString("status"))
                    assertEquals(503, rs.getInt("response_status"))
                    assertEquals("", rs.getString("response_payload"))
                    assertEquals("BOUNDARY_UNAVAILABLE", rs.getString("error_class"))
                    assertEquals("capture failed", rs.getString("error_message"))
                }
            }
        }
    }
}
