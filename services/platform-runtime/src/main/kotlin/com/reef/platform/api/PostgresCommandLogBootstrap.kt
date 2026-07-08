package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import javax.sql.DataSource

internal object PostgresCommandLogBootstrap {
    fun ensure(
        dataSource: DataSource,
        names: PostgresCommandLogSqlNames,
        bootstrapMode: PostgresBootstrapMode
    ) {
        dataSource.connection.use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.commandLog(
                        commands = names.commands,
                        payloads = names.commandPayloads,
                        workQueue = names.commandWorkQueue,
                        results = names.commandResults,
                        resultsArchive = names.commandResultsArchive,
                        retentionPins = names.retentionPins,
                        appendFunction = names.commandAppendFunction
                    )
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commands} (
                      command_id TEXT PRIMARY KEY,
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      trace_id TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      actor_id TEXT NOT NULL,
                      command_type TEXT NOT NULL,
                      run_id TEXT NOT NULL DEFAULT '',
                      run_kind TEXT NOT NULL DEFAULT '',
                      scenario_id TEXT NOT NULL DEFAULT '',
                      received_at TIMESTAMPTZ NOT NULL,
                      payload_json JSONB NOT NULL,
                      status TEXT NOT NULL DEFAULT 'RECEIVED',
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      response_status INTEGER NOT NULL DEFAULT 0,
                      response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      UNIQUE (client_id, route, idempotency_key),
                      CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commands}
                      ADD COLUMN IF NOT EXISTS response_status INTEGER NOT NULL DEFAULT 0,
                      ADD COLUMN IF NOT EXISTS response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '',
                      ADD COLUMN IF NOT EXISTS run_kind TEXT NOT NULL DEFAULT '',
                      ADD COLUMN IF NOT EXISTS scenario_id TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commands}
                      ALTER COLUMN status SET DEFAULT 'RECEIVED'
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandPayloads} (
                      command_id TEXT PRIMARY KEY,
                      payload_json JSONB NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    INSERT INTO ${names.commandPayloads}(command_id, payload_json, created_at)
                    SELECT command_id, payload_json, created_at
                    FROM ${names.commands}
                    WHERE payload_json <> '{}'::jsonb
                    ON CONFLICT (command_id) DO NOTHING
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE UNLOGGED TABLE IF NOT EXISTS ${names.commandWorkQueue} (
                      command_id TEXT PRIMARY KEY,
                      status TEXT NOT NULL,
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      leased_by TEXT NOT NULL DEFAULT '',
                      leased_until TIMESTAMPTZ,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_work_queue_status_updated
                    ON ${names.commandWorkQueue}(status, updated_at, command_id)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandResults} (
                      command_id TEXT PRIMARY KEY,
                      status TEXT NOT NULL DEFAULT 'COMPLETED',
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      response_status INTEGER NOT NULL,
                      response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      CHECK (status IN ('COMPLETED', 'FAILED'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commandResults}
                      ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'COMPLETED',
                      ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
                      ADD COLUMN IF NOT EXISTS last_error TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commandPayloads}
                      DROP CONSTRAINT IF EXISTS command_payloads_command_id_fkey
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commandWorkQueue}
                      DROP CONSTRAINT IF EXISTS command_work_queue_command_id_fkey
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commandResults}
                      DROP CONSTRAINT IF EXISTS command_results_command_id_fkey
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_results_status_completed
                    ON ${names.commandResults}(status, completed_at, command_id)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandResultsArchive} (
                      command_id TEXT NOT NULL,
                      status TEXT NOT NULL DEFAULT 'COMPLETED',
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      response_status INTEGER NOT NULL,
                      response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      completed_at TIMESTAMPTZ NOT NULL,
                      archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      CHECK (status IN ('COMPLETED', 'FAILED')),
                      PRIMARY KEY (completed_at, command_id)
                    ) PARTITION BY RANGE (completed_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandResultsArchiveDefault}
                    PARTITION OF ${names.commandResultsArchive} DEFAULT
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_results_archive_command
                    ON ${names.commandResultsArchive}(command_id, completed_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_results_archive_status_completed
                    ON ${names.commandResultsArchive}(status, completed_at, command_id)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.retentionPins} (
                      pin_id TEXT PRIMARY KEY,
                      selector_type TEXT NOT NULL,
                      selector_value TEXT NOT NULL,
                      reason TEXT NOT NULL DEFAULT '',
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      UNIQUE (selector_type, selector_value),
                      CHECK (selector_type IN ('command_id', 'idempotency_prefix', 'trace_id', 'correlation_id', 'client_id', 'run_id'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_retention_pins_selector
                    ON ${names.retentionPins}(selector_type, selector_value)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error, updated_at)
                    SELECT command_id, status, attempt_count, last_error, created_at
                    FROM ${names.commands}
                    ON CONFLICT (command_id) DO NOTHING
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    INSERT INTO ${names.commandResults}(
                      command_id,
                      status,
                      attempt_count,
                      last_error,
                      response_status,
                      response_payload_json,
                      completed_at
                    )
                    SELECT command_id, status, attempt_count, last_error, response_status, response_payload_json, created_at
                    FROM ${names.commands}
                    WHERE status IN ('COMPLETED', 'FAILED') OR response_status > 0
                    ON CONFLICT (command_id) DO NOTHING
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    DELETE FROM ${names.commandWorkQueue}
                    WHERE status IN ('COMPLETED', 'FAILED')
                    """.trimIndent()
                )
                stmt.execute("DROP FUNCTION IF EXISTS ${names.commandAppendFunction}(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, JSONB)")
                stmt.execute("DROP FUNCTION IF EXISTS ${names.commandAppendFunction}(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, JSONB)")
                stmt.execute(commandAppendFunctionSql(names))
            }
        }
    }

    private fun commandAppendFunctionSql(names: PostgresCommandLogSqlNames): String {
        return """
            CREATE OR REPLACE FUNCTION ${names.commandAppendFunction}(
              p_command_id TEXT,
              p_client_id TEXT,
              p_route TEXT,
              p_idempotency_key TEXT,
              p_trace_id TEXT,
              p_correlation_id TEXT,
              p_actor_id TEXT,
              p_command_type TEXT,
              p_run_id TEXT,
              p_run_kind TEXT,
              p_scenario_id TEXT,
              p_received_at TIMESTAMPTZ,
              p_payload_json JSONB
            )
            RETURNS TABLE (
              out_appended BOOLEAN,
              out_command_id TEXT,
              out_client_id TEXT,
              out_route TEXT,
              out_idempotency_key TEXT,
              out_trace_id TEXT,
              out_correlation_id TEXT,
              out_actor_id TEXT,
              out_command_type TEXT,
              out_run_id TEXT,
              out_run_kind TEXT,
              out_scenario_id TEXT,
              out_received_at TIMESTAMPTZ,
              out_payload_json JSONB,
              out_status TEXT,
              out_attempt_count INTEGER,
              out_last_error TEXT,
              out_response_status INTEGER,
              out_response_payload_json JSONB
            )
            LANGUAGE plpgsql
            AS $$
            DECLARE
              v_inserted INTEGER := 0;
              v_command_id TEXT;
            BEGIN
              INSERT INTO ${names.commands} AS command_row(
                command_id,
                client_id,
                route,
                idempotency_key,
                trace_id,
                correlation_id,
                actor_id,
                command_type,
                run_id,
                run_kind,
                scenario_id,
                received_at,
                payload_json
              )
              VALUES (
                p_command_id,
                p_client_id,
                p_route,
                p_idempotency_key,
                p_trace_id,
                p_correlation_id,
                p_actor_id,
                p_command_type,
                p_run_id,
                p_run_kind,
                p_scenario_id,
                p_received_at,
                '{}'::jsonb
              )
              ON CONFLICT DO NOTHING;

              GET DIAGNOSTICS v_inserted = ROW_COUNT;

              IF v_inserted = 1 THEN
                INSERT INTO ${names.commandPayloads}(command_id, payload_json, created_at)
                VALUES (p_command_id, p_payload_json, p_received_at)
                ON CONFLICT (command_id) DO NOTHING;

                INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error, updated_at)
                VALUES (p_command_id, 'RECEIVED', 0, '', p_received_at)
                ON CONFLICT (command_id) DO NOTHING;

                v_command_id := p_command_id;
              ELSE
                SELECT c.command_id
                INTO v_command_id
                FROM ${names.commands} c
                WHERE c.client_id = p_client_id
                  AND c.route = p_route
                  AND c.idempotency_key = p_idempotency_key;

                IF v_command_id IS NULL THEN
                  SELECT c.command_id
                  INTO v_command_id
                  FROM ${names.commands} c
                  WHERE c.command_id = p_command_id;
                END IF;
              END IF;

              RETURN QUERY
              SELECT
                v_inserted = 1 AS out_appended,
                c.command_id AS out_command_id,
                c.client_id AS out_client_id,
                c.route AS out_route,
                c.idempotency_key AS out_idempotency_key,
                c.trace_id AS out_trace_id,
                c.correlation_id AS out_correlation_id,
                c.actor_id AS out_actor_id,
                c.command_type AS out_command_type,
                c.run_id AS out_run_id,
                c.run_kind AS out_run_kind,
                c.scenario_id AS out_scenario_id,
                c.received_at AS out_received_at,
                COALESCE(p.payload_json, c.payload_json) AS out_payload_json,
                COALESCE(q.status, r.status, c.status) AS out_status,
                COALESCE(q.attempt_count, r.attempt_count, c.attempt_count) AS out_attempt_count,
                COALESCE(q.last_error, r.last_error, c.last_error) AS out_last_error,
                COALESCE(r.response_status, c.response_status, 0) AS out_response_status,
                COALESCE(r.response_payload_json, c.response_payload_json, '{}'::jsonb) AS out_response_payload_json
              FROM ${names.commands} c
              LEFT JOIN ${names.commandPayloads} p ON p.command_id = c.command_id
              LEFT JOIN ${names.commandWorkQueue} q ON q.command_id = c.command_id
              LEFT JOIN ${names.commandResults} r ON r.command_id = c.command_id
              WHERE c.command_id = v_command_id;
            END;
            $$;
        """.trimIndent()
    }
}
