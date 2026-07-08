package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.AccountRiskCheckRequest
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.CommandCircuitBreakerRequest
import com.reef.platform.api.DefaultIdempotencyRetentionPolicy
import com.reef.platform.api.BoundaryError
import com.reef.platform.api.InstrumentPriceCollarRequest
import com.reef.platform.api.PostgresAccountRiskCheck
import com.reef.platform.api.PostgresBoundaryRejectionLog
import com.reef.platform.api.PostgresCommandCaptureStore
import com.reef.platform.api.PostgresCommandCircuitBreakerStore
import com.reef.platform.api.PostgresInstrumentPriceCollarStore
import com.reef.platform.api.PostgresCommandLogStore
import com.reef.platform.api.PostgresIdempotencyStore
import com.reef.platform.application.arena.ArenaBotMetadata
import com.reef.platform.application.arena.ArenaBotVersionStatus
import com.reef.platform.application.arena.ArenaControlPlaneService
import com.reef.platform.application.arena.ArenaQualificationStatus
import com.reef.platform.application.arena.ArenaRunBotResult
import com.reef.platform.application.arena.ArenaRunBotVersionRef
import com.reef.platform.application.arena.ArenaRunStatus
import com.reef.platform.application.arena.ArenaRuntimeConfigDescriptor
import com.reef.platform.application.arena.ArenaRuntimeConfigProvider
import com.reef.platform.application.arena.PostgresArenaBotRegistryStore
import com.reef.platform.application.arena.RegisterArenaBotCommand
import com.reef.platform.application.arena.RegisterArenaBotVersionCommand
import com.reef.platform.application.arena.RegisterArenaRunCommand
import com.reef.platform.domain.RuntimeEvent
import java.sql.DriverManager
import java.time.Instant
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
                  'runtime/0007_projection_watermarks.sql',
                  'runtime/0008_partitioned_projection_batching.sql',
                  'runtime/0009_runtime_canonical_event_row_toggle.sql',
                  'runtime/0010_venue_event_batch_materialization.sql',
                  'runtime/0011_canonical_command_outcome_projection.sql',
                  'runtime/0012_cap_command_outcome_projection_batch.sql',
                  'runtime/0013_scope_venue_event_batch_identity.sql',
                  'runtime/0014_lifecycle_command_outcome_projection.sql',
                  'runtime/0015_market_data_snapshots.sql',
                  'runtime/0016_order_lifecycle_state.sql',
                  'runtime/0027_audit_persistence_hardening.sql',
                  'runtime/0028_typed_top_of_book_facts.sql',
                  'auth/0002_live_auth_tables.sql',
                  'boundary/0002_live_boundary_tables.sql',
                  'boundary/0003_command_capture_live_shape.sql',
                  'boundary/0004_command_capture_legacy_defaults.sql',
                  'boundary/0006_account_risk_controls.sql',
                  'boundary/0007_command_circuit_breakers.sql',
                  'boundary/0008_account_risk_limits.sql',
                  'boundary/0009_instrument_price_collars.sql',
                  'boundary/0010_boundary_rejections.sql',
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
                  'command_log/0013_drop_hot_path_foreign_keys.sql',
                  'command_log/0014_integrity_audit_views.sql'
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
                    "boundary/0006_account_risk_controls.sql",
                    "boundary/0007_command_circuit_breakers.sql",
                    "boundary/0008_account_risk_limits.sql",
                    "boundary/0009_instrument_price_collars.sql",
                    "boundary/0010_boundary_rejections.sql",
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
                    "command_log/0014_integrity_audit_views.sql",
                    "runtime/0003_live_runtime_persistence.sql",
                    "runtime/0004_bulk_submit_outcomes.sql",
                    "runtime/0005_set_based_submit_outcomes.sql",
                    "runtime/0006_canonical_append_store.sql",
                    "runtime/0007_projection_watermarks.sql",
                    "runtime/0008_partitioned_projection_batching.sql",
                    "runtime/0009_runtime_canonical_event_row_toggle.sql",
                    "runtime/0010_venue_event_batch_materialization.sql",
                    "runtime/0011_canonical_command_outcome_projection.sql",
                    "runtime/0012_cap_command_outcome_projection_batch.sql",
                    "runtime/0013_scope_venue_event_batch_identity.sql",
                    "runtime/0014_lifecycle_command_outcome_projection.sql",
                    "runtime/0015_market_data_snapshots.sql",
                    "runtime/0016_order_lifecycle_state.sql",
                    "runtime/0027_audit_persistence_hardening.sql",
                    "runtime/0028_typed_top_of_book_facts.sql",
                    "runtime/0029_typed_runtime_event_facts.sql",
                    "runtime/0030_typed_submit_result_facts.sql",
                    "runtime/0031_typed_execution_trade_facts.sql",
                    "runtime/0032_typed_order_facts.sql"
                ),
                appliedMigrations
            )

            val expectedTables = setOf(
                "auth.auth_actor_roles",
                "auth.auth_roles",
                "boundary.api_command_captures",
                "boundary.api_idempotency_records",
                "boundary.account_risk_controls",
                "boundary.account_risk_decisions",
                "boundary.command_circuit_breakers",
                "boundary.instrument_price_collars",
                "boundary.boundary_rejections",
                "command_log.command_payloads",
                "command_log.command_integrity_violations",
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
                "runtime.canonical_venue_event_batches",
                "runtime.canonical_command_outcomes",
                "runtime.market_data_snapshots",
                "runtime.order_lifecycle_state",
                "runtime.projection_watermarks",
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
                    'canonical_venue_event_batches',
                    'canonical_command_outcomes',
                    'market_data_snapshots',
                    'order_lifecycle_state',
                    'projection_watermarks',
                    'reference_instruments',
                    'auth_roles',
                    'auth_actor_roles',
                    'api_idempotency_records',
                    'api_command_captures',
                    'account_risk_controls',
                    'account_risk_decisions',
                    'command_circuit_breakers',
                    'instrument_price_collars',
                    'boundary_rejections',
                    'commands',
                    'command_payloads',
                    'command_integrity_violations',
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
                  AND routine_name IN ('command_append', 'command_integrity_summary')
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
                    "command_log.command_append",
                    "command_log.command_integrity_summary"
                ),
                commandLogFunctions
            )

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
                    'event_id_uuid',
                    'occurred_at',
                    'occurred_at_ts',
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
                    "event_id_uuid:uuid",
                    "occurred_at:text",
                    "occurred_at_ts:timestamp with time zone",
                    "payload_json:jsonb",
                    "sequence_number:bigint"
                ),
                runtimeEventColumns
            )

            val submitResultColumns = conn.prepareStatement(
                """
                SELECT column_name || ':' || data_type AS column_name
                FROM information_schema.columns
                WHERE table_schema = 'runtime'
                  AND table_name = 'submit_results'
                  AND column_name IN (
                    'command_id',
                    'event_id',
                    'event_id_uuid',
                    'occurred_at',
                    'occurred_at_ts',
                    'result_type'
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
                    "event_id:text",
                    "event_id_uuid:uuid",
                    "occurred_at:text",
                    "occurred_at_ts:timestamp with time zone",
                    "result_type:text"
                ),
                submitResultColumns
            )

            val executionTradeColumns = conn.prepareStatement(
                """
                SELECT table_name || '.' || column_name || ':' || data_type AS column_name
                FROM information_schema.columns
                WHERE table_schema = 'runtime'
                  AND (
                    (table_name = 'executions' AND column_name IN (
                      'event_id',
                      'event_id_uuid',
                      'quantity_units_num',
                      'execution_price_num',
                      'occurred_at',
                      'occurred_at_ts'
                    ))
                    OR (table_name = 'trades' AND column_name IN (
                      'event_id',
                      'event_id_uuid',
                      'quantity_units_num',
                      'price_num',
                      'occurred_at',
                      'occurred_at_ts'
                    ))
                  )
                ORDER BY table_name, column_name
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
                    "executions.event_id:text",
                    "executions.event_id_uuid:uuid",
                    "executions.execution_price_num:numeric",
                    "executions.occurred_at:text",
                    "executions.occurred_at_ts:timestamp with time zone",
                    "executions.quantity_units_num:numeric",
                    "trades.event_id:text",
                    "trades.event_id_uuid:uuid",
                    "trades.occurred_at:text",
                    "trades.occurred_at_ts:timestamp with time zone",
                    "trades.price_num:numeric",
                    "trades.quantity_units_num:numeric"
                ),
                executionTradeColumns
            )

            val orderColumns = conn.prepareStatement(
                """
                SELECT column_name || ':' || data_type AS column_name
                FROM information_schema.columns
                WHERE table_schema = 'runtime'
                  AND table_name = 'orders'
                  AND column_name IN (
                    'accepted_at',
                    'accepted_at_ts',
                    'quantity_units_num',
                    'limit_price_num',
                    'client_order_id',
                    'run_id',
                    'venue_session_id'
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
                    "accepted_at:text",
                    "accepted_at_ts:timestamp with time zone",
                    "client_order_id:text",
                    "limit_price_num:numeric",
                    "quantity_units_num:numeric",
                    "run_id:text",
                    "venue_session_id:text"
                ),
                orderColumns
            )

            val topOfBookColumns = conn.prepareStatement(
                """
                SELECT table_name || '.' || column_name || ':' || data_type AS column_name
                FROM information_schema.columns
                WHERE table_schema = 'runtime'
                  AND (
                    (table_name = 'order_lifecycle_state' AND column_name IN (
                      'original_quantity_units_num',
                      'remaining_quantity_units_num',
                      'filled_quantity_units_num',
                      'limit_price_num'
                    ))
                    OR
                    (table_name = 'market_data_snapshots' AND column_name IN (
                      'best_bid_price_num',
                      'best_bid_quantity_num',
                      'best_ask_price_num',
                      'best_ask_quantity_num'
                    ))
                  )
                ORDER BY table_name, column_name
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
                    "market_data_snapshots.best_ask_price_num:numeric",
                    "market_data_snapshots.best_ask_quantity_num:numeric",
                    "market_data_snapshots.best_bid_price_num:numeric",
                    "market_data_snapshots.best_bid_quantity_num:numeric",
                    "order_lifecycle_state.filled_quantity_units_num:numeric",
                    "order_lifecycle_state.limit_price_num:numeric",
                    "order_lifecycle_state.original_quantity_units_num:numeric",
                    "order_lifecycle_state.remaining_quantity_units_num:numeric"
                ),
                topOfBookColumns
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
                    'runtime_append_canonical_submit_outcomes',
                    'runtime_project_canonical_submit_outcomes',
                    'runtime_project_canonical_command_outcomes',
                    'runtime_materialize_venue_event_batch'
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
                    "runtime.runtime_append_canonical_submit_outcomes",
                    "runtime.runtime_project_canonical_submit_outcomes",
                    "runtime.runtime_project_canonical_command_outcomes",
                    "runtime.runtime_materialize_venue_event_batch"
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
                    'canonical_venue_event_batches',
                    'canonical_command_outcomes',
                    'market_data_snapshots',
                    'order_lifecycle_state',
                    'projection_watermarks',
                    'reference_instruments',
                    'auth_roles',
                    'auth_actor_roles',
                    'api_idempotency_records',
                    'api_command_captures',
                    'account_risk_controls',
                    'account_risk_decisions',
                    'command_circuit_breakers',
                    'instrument_price_collars',
                    'boundary_rejections',
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
        PostgresAccountRiskCheck(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresCommandCircuitBreakerStore(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresInstrumentPriceCollarStore(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresBoundaryRejectionLog(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        PostgresCommandLogStore(
            dataSource = boundaryDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
    }

    @Test
    fun validateModeArenaRegistryStorePersistsControlPlaneStateWhenConfigured() {
        val jdbcUrl = System.getenv("ARENA_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("ARENA_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("ARENA_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val store = PostgresArenaBotRegistryStore(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val botId = "bot-$suffix"
        val versionId = "v1"
        val runId = "run-$suffix"
        val controlPlane = ArenaControlPlaneService(store) { Instant.parse("2026-07-05T12:00:00Z") }

        controlPlane.registerBot(
            RegisterArenaBotCommand(
                botId = botId,
                fileName = "$botId.ts",
                metadata = ArenaBotMetadata(
                    name = botId,
                    publisher = "Schema Test",
                    email = "schema-test@example.com",
                    description = "postgres arena store test",
                    version = "1.0.0"
                )
            )
        )
        controlPlane.registerVersion(
            RegisterArenaBotVersionCommand(
                botId = botId,
                versionId = versionId,
                sourceHash = "sha256:source-$suffix",
                artifactHash = "sha256:artifact-$suffix",
                sdkVersion = "1.5.0",
                apiVersion = "v1",
                dependencyManifestHash = "sha256:deps-$suffix"
            )
        )
        controlPlane.transitionVersion(botId, versionId, ArenaBotVersionStatus.Submitted, "scanner", "submitted", "corr-$suffix")
        controlPlane.transitionVersion(botId, versionId, ArenaBotVersionStatus.ChecksPassed, "scanner", "passed", "corr-$suffix")
        controlPlane.transitionVersion(botId, versionId, ArenaBotVersionStatus.Approved, "admin-cli", "approved", "corr-$suffix")
        controlPlane.recordQualificationReport(
            botId = botId,
            versionId = versionId,
            reportId = "report-$suffix",
            status = ArenaQualificationStatus.Passed,
            issues = listOf("scanner ok", "stress ok"),
            policyVersion = "policy-v1"
        )
        controlPlane.replaceRuntimeConfigDescriptors(
            botId,
            versionId,
            listOf(
                ArenaRuntimeConfigDescriptor(
                    botId = botId,
                    versionId = versionId,
                    key = "maxInventory",
                    provider = ArenaRuntimeConfigProvider.OpenBao,
                    secretPath = "kv/bots/$botId/$versionId",
                    required = true,
                    description = "inventory cap"
                )
            )
        )
        controlPlane.registerRun(
            RegisterArenaRunCommand(
                runId = runId,
                modeId = "hosted-sim",
                scenarioId = "scenario-schema",
                seed = 42,
                policyVersion = "policy-v1",
                botVersions = listOf(ArenaRunBotVersionRef(botId, versionId))
            )
        )
        controlPlane.updateRunStatus(runId, ArenaRunStatus.Running)
        controlPlane.updateRunStatus(runId, ArenaRunStatus.Completed)
        controlPlane.recordRunBotResult(
            ArenaRunBotResult(
                runId = runId,
                botId = botId,
                versionId = versionId,
                scoringPolicyVersion = "score-v1",
                finalEquity = 1_025_000,
                realizedPnl = 25_000,
                maxDrawdown = 1_000,
                actionsProposed = 12,
                orderActionsProposed = 8,
                dataCalls = 20,
                signalsGenerated = 4,
                disqualified = false,
                createdAt = Instant.parse("2026-07-05T12:00:00Z")
            )
        )

        assertEquals(botId, store.bot(botId)?.botId)
        assertEquals(ArenaBotVersionStatus.Approved, store.version(botId, versionId)?.status)
        assertEquals(listOf("scanner ok", "stress ok"), store.qualificationReports(botId, versionId).single().issues)
        assertEquals("admin-cli", store.operatorDecisions(botId, versionId).last().actorId)
        assertEquals("maxInventory", store.runtimeConfigDescriptors(botId, versionId).single().key)
        assertEquals("scenario-schema", store.runRecord(runId)?.scenarioId)
        assertEquals(1_025_000, store.runBotResults(runId).single().finalEquity)
        assertEquals(botId, store.leaderboard("hosted-sim", "score-v1").single().botId)
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

    @Test
    fun postgresAccountRiskCheckRejectsFromControlStateAndAuditsDecision() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val riskCheck = PostgresAccountRiskCheck(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            cacheTtlMillis = 0L
        )
        val suffix = UUID.randomUUID().toString()
        val accountId = "acct-risk-$suffix"
        val commandId = "cmd-risk-$suffix"

        riskCheck.upsertControl("ACCOUNT", accountId, AccountRiskDecision.REJECT, "operator hold", "", "", "")
        val result = riskCheck.evaluate(
            AccountRiskCheckRequest(
                clientId = "client-risk",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = commandId,
                idempotencyKey = "idem-risk-$suffix",
                correlationId = "corr-risk-$suffix",
                actorId = "actor-risk",
                participantId = "participant-risk",
                accountId = accountId,
                botId = "bot-risk",
                runId = "run-risk",
                venueSessionId = "session-risk",
                instrumentId = "AAPL",
                orderId = "ord-risk",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                payloadHash = "hash-risk"
            )
        )

        assertEquals(AccountRiskDecision.REJECT, result.decision)
        assertEquals("operator hold", result.message)
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                SELECT decision, code, message, account_id, command_id, quantity_units, limit_price, currency
                FROM boundary.account_risk_decisions
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("REJECT", rs.getString("decision"))
                    assertEquals("ACCOUNT_RISK_REJECTED", rs.getString("code"))
                    assertEquals("operator hold", rs.getString("message"))
                    assertEquals(accountId, rs.getString("account_id"))
                    assertEquals(commandId, rs.getString("command_id"))
                    assertEquals("100", rs.getString("quantity_units"))
                    assertEquals("150250000000", rs.getString("limit_price"))
                    assertEquals("USD", rs.getString("currency"))
                }
            }
        }
    }

    @Test
    fun postgresAccountRiskCheckRejectsSubmitOverMaxQuantityLimit() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val riskCheck = PostgresAccountRiskCheck(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            cacheTtlMillis = 0L
        )
        val suffix = UUID.randomUUID().toString()
        val accountId = "acct-limit-$suffix"
        val commandId = "cmd-limit-$suffix"

        riskCheck.upsertControl(
            scopeType = "ACCOUNT",
            scopeId = accountId,
            decision = AccountRiskDecision.ALLOW,
            reason = "desk limit",
            maxQuantityUnits = "100",
            maxNotional = "",
            currency = "USD"
        )
        val result = riskCheck.evaluate(
            AccountRiskCheckRequest(
                clientId = "client-limit",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = commandId,
                idempotencyKey = "idem-limit-$suffix",
                correlationId = "corr-limit-$suffix",
                actorId = "actor-limit",
                participantId = "participant-limit",
                accountId = accountId,
                botId = "bot-limit",
                runId = "run-limit",
                venueSessionId = "session-limit",
                instrumentId = "AAPL",
                orderId = "ord-limit",
                quantityUnits = "101",
                limitPrice = "150250000000",
                currency = "USD",
                payloadHash = "hash-limit"
            )
        )

        assertEquals(AccountRiskDecision.REJECT, result.decision)
        assertEquals("ACCOUNT_RISK_MAX_QUANTITY_EXCEEDED", result.code)
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                SELECT code, quantity_units, limit_price, currency
                FROM boundary.account_risk_decisions
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("ACCOUNT_RISK_MAX_QUANTITY_EXCEEDED", rs.getString("code"))
                    assertEquals("101", rs.getString("quantity_units"))
                    assertEquals("150250000000", rs.getString("limit_price"))
                    assertEquals("USD", rs.getString("currency"))
                }
            }
        }
    }

    @Test
    fun postgresCommandCircuitBreakerRejectsTrippedInstrument() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val breakers = PostgresCommandCircuitBreakerStore(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            cacheTtlMillis = 0L
        )
        val instrumentId = "HALT-${UUID.randomUUID()}"

        breakers.setBreaker("INSTRUMENT", instrumentId, true, "operator halt")
        val error = breakers.evaluate(
            CommandCircuitBreakerRequest(
                clientId = "client-breaker",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = "cmd-breaker",
                correlationId = "corr-breaker",
                venueSessionId = "session-breaker",
                instrumentId = instrumentId
            )
        )

        assertEquals(503, error?.status)
        assertEquals("COMMAND_CIRCUIT_BREAKER_TRIPPED", error?.code)
        assertTrue(error?.message?.contains("operator halt") == true)
    }

    @Test
    fun postgresInstrumentPriceCollarRejectsOutsideBand() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val collars = PostgresInstrumentPriceCollarStore(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            cacheTtlMillis = 0L
        )
        val instrumentId = "COLLAR-${UUID.randomUUID()}"

        collars.setCollar(instrumentId, "150000000000", "151000000000", "USD", "regular band")
        val low = collars.evaluate(
            InstrumentPriceCollarRequest(
                clientId = "client-collar",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = "cmd-collar-low",
                correlationId = "corr-collar-low",
                instrumentId = instrumentId,
                limitPrice = "149999999999",
                currency = "USD"
            )
        )
        val high = collars.evaluate(
            InstrumentPriceCollarRequest(
                clientId = "client-collar",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = "cmd-collar-high",
                correlationId = "corr-collar-high",
                instrumentId = instrumentId,
                limitPrice = "151000000001",
                currency = "USD"
            )
        )
        val inside = collars.evaluate(
            InstrumentPriceCollarRequest(
                clientId = "client-collar",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = "cmd-collar-ok",
                correlationId = "corr-collar-ok",
                instrumentId = instrumentId,
                limitPrice = "150500000000",
                currency = "USD"
            )
        )

        assertEquals(422, low?.status)
        assertEquals("PRICE_COLLAR_LOW", low?.code)
        assertEquals(422, high?.status)
        assertEquals("PRICE_COLLAR_HIGH", high?.code)
        assertEquals(null, inside)
    }

    @Test
    fun postgresBoundaryRejectionLogPersistsGuardrailEvidence() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val log = PostgresBoundaryRejectionLog(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val commandId = "cmd-rejection-$suffix"

        log.recordRejection(
            guardrailType = "instrument-price-collar",
            scopeType = "INSTRUMENT",
            scopeId = "AAPL",
            request = AccountRiskCheckRequest(
                clientId = "client-rejection",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = commandId,
                idempotencyKey = "idem-rejection-$suffix",
                correlationId = "corr-rejection-$suffix",
                actorId = "actor-rejection",
                participantId = "participant-rejection",
                accountId = "account-rejection",
                botId = "bot-rejection",
                runId = "run-rejection",
                venueSessionId = "session-rejection",
                instrumentId = "AAPL",
                orderId = "ord-rejection",
                quantityUnits = "100",
                limitPrice = "149999999999",
                currency = "USD",
                payloadHash = "hash-rejection"
            ),
            error = BoundaryError(422, "PRICE_COLLAR_LOW", "limit price below collar")
        )

        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                SELECT guardrail_type, scope_type, scope_id, status, code, command_id, limit_price, currency
                FROM boundary.boundary_rejections
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("instrument-price-collar", rs.getString("guardrail_type"))
                    assertEquals("INSTRUMENT", rs.getString("scope_type"))
                    assertEquals("AAPL", rs.getString("scope_id"))
                    assertEquals(422, rs.getInt("status"))
                    assertEquals("PRICE_COLLAR_LOW", rs.getString("code"))
                    assertEquals(commandId, rs.getString("command_id"))
                    assertEquals("149999999999", rs.getString("limit_price"))
                    assertEquals("USD", rs.getString("currency"))
                }
            }
        }
    }
}
