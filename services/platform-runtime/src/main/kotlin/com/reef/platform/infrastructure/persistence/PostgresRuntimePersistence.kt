package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.config.RuntimeEnv
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

class PostgresRuntimePersistence(
    private val dataSource: DataSource,
    private val names: PostgresRuntimeSqlNames = PostgresRuntimeSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val projectionDataSource: DataSource = dataSource
) : RuntimePersistence {
    private val streamAckCanonicalEventRowsEnabled: Boolean =
        RuntimeEnv.bool("STREAM_ACK_CANONICAL_EVENT_ROWS_ENABLED", true)
    private val streamAckCanonicalQueryIndexesEnabled: Boolean =
        RuntimeEnv.bool("STREAM_ACK_CANONICAL_QUERY_INDEXES_ENABLED", true)

    private data class ProjectionCandidate(
        val partitionId: Int,
        val partitionSequence: Long,
        val resultPayload: String,
        val partitionRow: Int = 0
    )

    init {
        canonicalConnection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(conn, PostgresSchemaRequirements.runtime(names))
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE SCHEMA IF NOT EXISTS ${names.runtimeSchemaName}
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE SCHEMA IF NOT EXISTS ${names.authSchemaName}
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.referenceInstruments} (
                      instrument_id TEXT PRIMARY KEY,
                      symbol TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.referenceParticipants} (
                      participant_id TEXT PRIMARY KEY,
                      name TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.referenceAccounts} (
                      account_id TEXT PRIMARY KEY,
                      participant_id TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.authRoles} (
                      role_id TEXT PRIMARY KEY,
                      permissions TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.authActorRoles} (
                      actor_id TEXT NOT NULL,
                      role_id TEXT NOT NULL,
                      PRIMARY KEY (actor_id, role_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.orders} (
                      order_id TEXT PRIMARY KEY,
                      engine_order_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      side TEXT NOT NULL,
                      order_type TEXT NOT NULL,
                      quantity_units TEXT NOT NULL,
                      limit_price TEXT NOT NULL,
                      currency TEXT NOT NULL,
                      time_in_force TEXT NOT NULL,
                      accepted_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.executions} (
                      event_id TEXT PRIMARY KEY,
                      execution_id TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      quantity_units TEXT NOT NULL,
                      execution_price TEXT NOT NULL,
                      currency TEXT NOT NULL,
                      occurred_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.trades} (
                      event_id TEXT PRIMARY KEY,
                      trade_id TEXT NOT NULL,
                      execution_id TEXT NOT NULL,
                      buy_order_id TEXT NOT NULL,
                      sell_order_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      quantity_units TEXT NOT NULL,
                      price TEXT NOT NULL,
                      currency TEXT NOT NULL,
                      occurred_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runtimeEvents} (
                      event_id TEXT PRIMARY KEY,
                      event_type TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      trace_id TEXT NOT NULL,
                      causation_id TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      actor_id TEXT NOT NULL DEFAULT '',
                      producer TEXT NOT NULL,
                      schema_version TEXT NOT NULL,
                      sequence_number BIGINT NOT NULL,
                      payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      occurred_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.runtimeEvents}
                    ADD COLUMN IF NOT EXISTS actor_id TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.runtimeEvents}
                    ADD COLUMN IF NOT EXISTS payload_json JSONB NOT NULL DEFAULT '{}'::jsonb
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runtimeTraceSequences} (
                      trace_id TEXT PRIMARY KEY,
                      next_sequence BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_trace_sequence
                    ON ${names.runtimeEvents}(trace_id, sequence_number)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_order_trace_sequence
                    ON ${names.runtimeEvents}(order_id, trace_id, sequence_number)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_occurred_event
                    ON ${names.runtimeEvents}(occurred_at DESC, event_id DESC)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_executions_order_occurred
                    ON ${names.executions}(order_id, occurred_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_buy_order_occurred
                    ON ${names.trades}(buy_order_id, occurred_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_sell_order_occurred
                    ON ${names.trades}(sell_order_id, occurred_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.submitResults} (
                      command_id TEXT PRIMARY KEY,
                      result_type TEXT NOT NULL,
                      event_id TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      engine_order_id TEXT NOT NULL,
                      code TEXT NOT NULL,
                      reason TEXT NOT NULL,
                      occurred_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalCommandResults} (
                      command_id TEXT PRIMARY KEY,
                      run_id TEXT NOT NULL,
                      venue_session_id TEXT NOT NULL,
                      partition_id INTEGER NOT NULL,
                      partition_seq BIGINT NOT NULL,
                      stream_name TEXT NOT NULL,
                      stream_seq BIGINT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      payload_hash TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      command_type TEXT NOT NULL,
                      result_status TEXT NOT NULL,
                      reject_code TEXT NOT NULL,
                      accepted_at TEXT NOT NULL,
                      completed_at TEXT NOT NULL,
                      engine_shard_id TEXT NOT NULL,
                      result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      UNIQUE (stream_name, stream_seq)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_results_partition_seq
                    ON ${names.canonicalCommandResults}(partition_id, partition_seq)
                    """.trimIndent()
                )
                if (streamAckCanonicalQueryIndexesEnabled) {
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_canonical_command_results_run_session
                        ON ${names.canonicalCommandResults}(run_id, venue_session_id, partition_id, partition_seq)
                        """.trimIndent()
                    )
                }
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalVenueEvents} (
                      event_id TEXT PRIMARY KEY,
                      run_id TEXT NOT NULL,
                      venue_session_id TEXT NOT NULL,
                      partition_id INTEGER NOT NULL,
                      partition_seq BIGINT NOT NULL,
                      event_seq BIGINT NOT NULL,
                      command_id TEXT NOT NULL,
                      event_type TEXT NOT NULL,
                      aggregate_type TEXT NOT NULL,
                      aggregate_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      deterministic_event_index INTEGER NOT NULL,
                      payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                      emitted_at TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                if (streamAckCanonicalEventRowsEnabled && streamAckCanonicalQueryIndexesEnabled) {
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_canonical_venue_events_partition_seq
                        ON ${names.canonicalVenueEvents}(partition_id, event_seq)
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_canonical_venue_events_run_session
                        ON ${names.canonicalVenueEvents}(run_id, venue_session_id, partition_id, event_seq)
                        """.trimIndent()
                    )
                }
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalVenueEventBatches} (
                      batch_id TEXT PRIMARY KEY,
                      shard_id TEXT NOT NULL,
                      partition_id INTEGER NOT NULL,
                      command_stream TEXT NOT NULL,
                      event_stream TEXT NOT NULL,
                      first_sequence BIGINT NOT NULL,
                      last_sequence BIGINT NOT NULL,
                      command_count INTEGER NOT NULL,
                      payload_checksum TEXT NOT NULL,
                      payload_format TEXT NOT NULL DEFAULT 'venue-event-batch-json',
                      payload_version TEXT NOT NULL DEFAULT 'v1',
                      payload_json JSONB NOT NULL,
                      created_at TEXT NOT NULL,
                      materialized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      UNIQUE (event_stream, partition_id, first_sequence, last_sequence)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_partition_seq
                    ON ${names.canonicalVenueEventBatches}(partition_id, first_sequence, last_sequence)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalCommandOutcomes} (
                      command_id TEXT PRIMARY KEY,
                      batch_id TEXT NOT NULL,
                      shard_id TEXT NOT NULL,
                      partition_id INTEGER NOT NULL,
                      command_stream TEXT NOT NULL,
                      event_stream TEXT NOT NULL,
                      stream_sequence BIGINT NOT NULL,
                      delivered_count BIGINT NOT NULL,
                      command_type TEXT NOT NULL,
                      payload_hash TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      result_status TEXT NOT NULL,
                      reject_code TEXT NOT NULL,
                      result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                      materialized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      UNIQUE (batch_id, stream_sequence)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_batch_seq
                    ON ${names.canonicalCommandOutcomes}(batch_id, stream_sequence)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_partition_seq
                    ON ${names.canonicalCommandOutcomes}(partition_id, stream_sequence)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.projectionWatermarks} (
                      projection_name TEXT NOT NULL,
                      partition_id INTEGER NOT NULL,
                      last_partition_seq BIGINT NOT NULL DEFAULT 0,
                      last_projected_at TIMESTAMPTZ,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      last_error TEXT NOT NULL DEFAULT '',
                      PRIMARY KEY (projection_name, partition_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.validateReferenceDataFunction}(
                      p_instrument_id TEXT,
                      p_participant_id TEXT,
                      p_account_id TEXT
                    )
                    RETURNS TABLE(
                      instrument_exists BOOLEAN,
                      participant_exists BOOLEAN,
                      account_exists BOOLEAN,
                      account_belongs_to_participant BOOLEAN
                    )
                    LANGUAGE SQL
                    STABLE
                    AS $$
                      SELECT
                        EXISTS(SELECT 1 FROM ${names.referenceInstruments} WHERE instrument_id = p_instrument_id),
                        EXISTS(SELECT 1 FROM ${names.referenceParticipants} WHERE participant_id = p_participant_id),
                        EXISTS(SELECT 1 FROM ${names.referenceAccounts} WHERE account_id = p_account_id),
                        EXISTS(SELECT 1 FROM ${names.referenceAccounts} WHERE account_id = p_account_id AND participant_id = p_participant_id)
                    $$;
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.appendCanonicalSubmitOutcomesFunction}(
                      p_outcomes JSONB
                    )
                    RETURNS BIGINT
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                      appended_count BIGINT := 0;
                    BEGIN
                      IF p_outcomes IS NULL THEN
                        RETURN 0;
                      END IF;

                      IF jsonb_typeof(p_outcomes) <> 'array' THEN
                        RAISE EXCEPTION 'canonical submit outcomes payload must be a JSON array';
                      END IF;

                      WITH outcomes AS (
                        SELECT outcome, ordinality::BIGINT AS outcome_ordinality
                        FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
                      ),
                      insert_results AS (
                        INSERT INTO ${names.canonicalCommandResults}(
                          command_id,
                          run_id,
                          venue_session_id,
                          partition_id,
                          partition_seq,
                          stream_name,
                          stream_seq,
                          idempotency_key,
                          payload_hash,
                          instrument_id,
                          command_type,
                          result_status,
                          reject_code,
                          accepted_at,
                          completed_at,
                          engine_shard_id,
                          result_payload
                        )
                        SELECT
                          outcome->>'commandId',
                          COALESCE(outcome->>'runId', ''),
                          COALESCE(outcome->>'venueSessionId', ''),
                          COALESCE((outcome->>'partitionId')::INTEGER, -1),
                          COALESCE((outcome->>'partitionSequence')::BIGINT, 0),
                          COALESCE(outcome->>'streamName', ''),
                          COALESCE((outcome->>'streamSequence')::BIGINT, 0),
                          COALESCE(outcome->>'idempotencyKey', ''),
                          COALESCE(outcome->>'payloadHash', ''),
                          COALESCE(outcome->>'instrumentId', ''),
                          COALESCE(outcome->>'commandType', ''),
                          COALESCE(outcome->>'resultStatus', ''),
                          COALESCE(outcome->>'rejectCode', ''),
                          COALESCE(outcome->>'acceptedAt', ''),
                          COALESCE(outcome->>'completedAt', ''),
                          COALESCE(outcome->>'engineShardId', ''),
                          COALESCE(outcome->'resultPayload', '{}'::jsonb)
                        FROM outcomes
                        ON CONFLICT (command_id) DO NOTHING
                        RETURNING 1
                      ),
                      parsed_events AS (
                        SELECT
                          outcome,
                          event,
                          event_ordinality::INTEGER AS deterministic_event_index
                        FROM outcomes
                        CROSS JOIN LATERAL jsonb_array_elements(
                          CASE
                            WHEN COALESCE(NULLIF(current_setting('reef.stream_ack_canonical_event_rows_enabled', TRUE), ''), 'true')::BOOLEAN
                              AND jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
                            ELSE '[]'::jsonb
                          END
                        ) WITH ORDINALITY AS event_rows(event, event_ordinality)
                      ),
                      insert_events AS (
                        INSERT INTO ${names.canonicalVenueEvents}(
                          event_id,
                          run_id,
                          venue_session_id,
                          partition_id,
                          partition_seq,
                          event_seq,
                          command_id,
                          event_type,
                          aggregate_type,
                          aggregate_id,
                          instrument_id,
                          deterministic_event_index,
                          payload,
                          emitted_at
                        )
                        SELECT
                          event->>'eventId',
                          COALESCE(outcome->>'runId', ''),
                          COALESCE(outcome->>'venueSessionId', ''),
                          COALESCE((outcome->>'partitionId')::INTEGER, -1),
                          COALESCE((outcome->>'partitionSequence')::BIGINT, 0),
                          COALESCE((outcome->>'partitionSequence')::BIGINT, 0) * 1000 + deterministic_event_index,
                          outcome->>'commandId',
                          COALESCE(event->>'eventType', ''),
                          CASE
                            WHEN event->>'eventType' = 'ExecutionCreated' THEN 'execution'
                            WHEN event->>'eventType' = 'TradeCreated' THEN 'trade'
                            ELSE 'order'
                          END,
                          COALESCE(event->>'orderId', event->>'eventId', ''),
                          COALESCE(outcome->>'instrumentId', ''),
                          deterministic_event_index,
                          event,
                          COALESCE(event->>'occurredAt', outcome->>'completedAt', '')
                        FROM parsed_events
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING 1
                      )
                      SELECT COUNT(*) INTO appended_count FROM outcomes;

                      RETURN appended_count;
                    END;
                    $$;
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.materializeVenueEventBatchFunction}(
                      p_batch JSONB
                    )
                    RETURNS BIGINT
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                      v_batch_id TEXT;
                      v_payload_checksum TEXT;
                      v_existing_checksum TEXT;
                      inserted_count BIGINT := 0;
                    BEGIN
                      IF p_batch IS NULL OR jsonb_typeof(p_batch) <> 'object' THEN
                        RAISE EXCEPTION 'venue event batch payload must be a JSON object';
                      END IF;

                      v_batch_id := COALESCE(p_batch->>'batchId', '');
                      v_payload_checksum := COALESCE(p_batch->>'payloadChecksum', '');

                      IF v_batch_id = '' THEN
                        RAISE EXCEPTION 'venue event batch payload missing batchId';
                      END IF;

                      SELECT payload_checksum
                        INTO v_existing_checksum
                        FROM ${names.canonicalVenueEventBatches}
                       WHERE batch_id = v_batch_id;

                      IF FOUND THEN
                        IF v_existing_checksum <> v_payload_checksum THEN
                          RAISE EXCEPTION 'venue event batch checksum conflict for batchId %', v_batch_id;
                        END IF;
                        RETURN 0;
                      END IF;

                      INSERT INTO ${names.canonicalVenueEventBatches}(
                        batch_id,
                        shard_id,
                        partition_id,
                        command_stream,
                        event_stream,
                        first_sequence,
                        last_sequence,
                        command_count,
                        payload_checksum,
                        payload_format,
                        payload_version,
                        payload_json,
                        created_at
                      )
                      VALUES (
                        v_batch_id,
                        COALESCE(p_batch->>'shardId', ''),
                        COALESCE((p_batch->>'partition')::INTEGER, -1),
                        COALESCE(p_batch->>'commandStream', ''),
                        COALESCE(p_batch->>'eventStream', ''),
                        COALESCE((p_batch->>'firstSequence')::BIGINT, 0),
                        COALESCE((p_batch->>'lastSequence')::BIGINT, 0),
                        COALESCE((p_batch->>'commandCount')::INTEGER, 0),
                        v_payload_checksum,
                        COALESCE(NULLIF(p_batch->>'payloadFormat', ''), 'venue-event-batch-json'),
                        COALESCE(NULLIF(p_batch->>'payloadVersion', ''), 'v1'),
                        p_batch,
                        COALESCE(p_batch->>'createdAt', '')
                      );

                      WITH outcomes AS (
                        SELECT outcome
                        FROM jsonb_array_elements(
                          CASE
                            WHEN jsonb_typeof(p_batch->'outcomes') = 'array' THEN p_batch->'outcomes'
                            ELSE '[]'::jsonb
                          END
                        ) AS outcome
                      ),
                      inserted AS (
                        INSERT INTO ${names.canonicalCommandOutcomes}(
                          command_id,
                          batch_id,
                          shard_id,
                          partition_id,
                          command_stream,
                          event_stream,
                          stream_sequence,
                          delivered_count,
                          command_type,
                          payload_hash,
                          instrument_id,
                          order_id,
                          result_status,
                          reject_code,
                          result_payload
                        )
                        SELECT
                          outcome->>'commandId',
                          v_batch_id,
                          COALESCE(p_batch->>'shardId', ''),
                          COALESCE((p_batch->>'partition')::INTEGER, -1),
                          COALESCE(p_batch->>'commandStream', ''),
                          COALESCE(p_batch->>'eventStream', ''),
                          COALESCE((outcome->>'streamSequence')::BIGINT, 0),
                          COALESCE((outcome->>'deliveredCount')::BIGINT, 0),
                          COALESCE(outcome->>'commandType', ''),
                          COALESCE(outcome->>'payloadHash', ''),
                          COALESCE(outcome->>'instrumentId', ''),
                          COALESCE(outcome->>'orderId', ''),
                          COALESCE(outcome->>'status', ''),
                          COALESCE(outcome->>'rejectCode', outcome#>>'{result,rejected,code}', ''),
                          COALESCE(outcome->'result', '{}'::jsonb)
                        FROM outcomes
                        ON CONFLICT (command_id) DO NOTHING
                        RETURNING 1
                      )
                      SELECT COUNT(*) INTO inserted_count FROM inserted;

                      RETURN inserted_count;
                    END;
                    $$;
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.persistSubmitOutcomeFunction}(
                      p_command_id TEXT,
                      p_result_type TEXT,
                      p_result_event_id TEXT,
                      p_result_order_id TEXT,
                      p_result_engine_order_id TEXT,
                      p_result_code TEXT,
                      p_result_reason TEXT,
                      p_result_occurred_at TEXT,
                      p_accepted_order JSONB,
                      p_executions JSONB,
                      p_trades JSONB,
                      p_events JSONB
                    )
                    RETURNS VOID
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                      INSERT INTO ${names.submitResults}(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
                      VALUES (
                        p_command_id,
                        p_result_type,
                        p_result_event_id,
                        p_result_order_id,
                        p_result_engine_order_id,
                        p_result_code,
                        p_result_reason,
                        p_result_occurred_at
                      )
                      ON CONFLICT (command_id) DO UPDATE SET
                        result_type = EXCLUDED.result_type,
                        event_id = EXCLUDED.event_id,
                        order_id = EXCLUDED.order_id,
                        engine_order_id = EXCLUDED.engine_order_id,
                        code = EXCLUDED.code,
                        reason = EXCLUDED.reason,
                        occurred_at = EXCLUDED.occurred_at;

                      IF p_accepted_order IS NOT NULL THEN
                        INSERT INTO ${names.orders}(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at)
                        VALUES (
                          p_accepted_order->>'orderId',
                          p_accepted_order->>'engineOrderId',
                          p_accepted_order->>'instrumentId',
                          p_accepted_order->>'participantId',
                          p_accepted_order->>'accountId',
                          p_accepted_order->>'side',
                          p_accepted_order->>'orderType',
                          p_accepted_order->>'quantityUnits',
                          p_accepted_order->>'limitPrice',
                          p_accepted_order->>'currency',
                          p_accepted_order->>'timeInForce',
                          p_accepted_order->>'acceptedAt'
                        )
                        ON CONFLICT (order_id) DO UPDATE SET
                          engine_order_id = EXCLUDED.engine_order_id,
                          instrument_id = EXCLUDED.instrument_id,
                          participant_id = EXCLUDED.participant_id,
                          account_id = EXCLUDED.account_id,
                          side = EXCLUDED.side,
                          order_type = EXCLUDED.order_type,
                          quantity_units = EXCLUDED.quantity_units,
                          limit_price = EXCLUDED.limit_price,
                          currency = EXCLUDED.currency,
                          time_in_force = EXCLUDED.time_in_force,
                          accepted_at = EXCLUDED.accepted_at;
                      END IF;

                      IF p_executions IS NOT NULL AND jsonb_array_length(p_executions) > 0 THEN
                        INSERT INTO ${names.executions}(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at)
                        SELECT
                          execution->>'eventId',
                          execution->>'executionId',
                          execution->>'orderId',
                          execution->>'instrumentId',
                          execution->>'quantityUnits',
                          execution->>'executionPrice',
                          execution->>'currency',
                          execution->>'occurredAt'
                        FROM jsonb_array_elements(p_executions) AS execution
                        ON CONFLICT (event_id) DO NOTHING;
                      END IF;

                      IF p_trades IS NOT NULL AND jsonb_array_length(p_trades) > 0 THEN
                        INSERT INTO ${names.trades}(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at)
                        SELECT
                          trade->>'eventId',
                          trade->>'tradeId',
                          trade->>'executionId',
                          trade->>'buyOrderId',
                          trade->>'sellOrderId',
                          trade->>'instrumentId',
                          trade->>'quantityUnits',
                          trade->>'price',
                          trade->>'currency',
                          trade->>'occurredAt'
                        FROM jsonb_array_elements(p_trades) AS trade
                        ON CONFLICT (event_id) DO NOTHING;
                      END IF;

                      IF p_events IS NULL OR jsonb_array_length(p_events) = 0 THEN
                        RETURN;
                      END IF;

                      WITH parsed_events AS (
                        SELECT event, ordinality
                        FROM jsonb_array_elements(p_events) WITH ORDINALITY AS event_rows(event, ordinality)
                      ),
                      trace_counts AS (
                        SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
                        FROM parsed_events
                        GROUP BY event->>'traceId'
                      ),
                      trace_allocations AS (
                        INSERT INTO ${names.runtimeTraceSequences} AS trace_sequence(trace_id, next_sequence)
                        SELECT trace_id, event_count FROM trace_counts
                        ON CONFLICT (trace_id) DO UPDATE SET next_sequence = trace_sequence.next_sequence + EXCLUDED.next_sequence
                        RETURNING trace_id, next_sequence
                      ),
                      trace_starts AS (
                        SELECT
                          counts.trace_id,
                          allocations.next_sequence - counts.event_count + 1 AS start_sequence
                        FROM trace_counts counts
                        JOIN trace_allocations allocations ON allocations.trace_id = counts.trace_id
                      ),
                      ordered_events AS (
                        SELECT
                          parsed.event,
                          parsed.ordinality,
                          row_number() OVER (
                            PARTITION BY parsed.event->>'traceId'
                            ORDER BY parsed.ordinality
                          ) - 1 AS trace_offset
                        FROM parsed_events parsed
                      )
                      INSERT INTO ${names.runtimeEvents}(event_id, event_type, order_id, trace_id, causation_id, correlation_id, actor_id, producer, schema_version, sequence_number, payload_json, occurred_at)
                      SELECT
                        event->>'eventId',
                        event->>'eventType',
                        event->>'orderId',
                        event->>'traceId',
                        event->>'causationId',
                        event->>'correlationId',
                        COALESCE(event->>'actorId', ''),
                        event->>'producer',
                        event->>'schemaVersion',
                        trace_starts.start_sequence + ordered_events.trace_offset,
                        COALESCE(event->'payloadJson', '{}'::jsonb),
                        event->>'occurredAt'
                      FROM ordered_events
                      JOIN trace_starts ON trace_starts.trace_id = ordered_events.event->>'traceId'
                      ORDER BY ordered_events.ordinality
                      ON CONFLICT (event_id) DO NOTHING;
                    END;
                    $$;
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.persistSubmitOutcomesFunction}(
                      p_outcomes JSONB
                    )
                    RETURNS BIGINT
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                      persisted_count BIGINT := 0;
                    BEGIN
                      IF p_outcomes IS NULL THEN
                        RETURN 0;
                      END IF;

                      IF jsonb_typeof(p_outcomes) <> 'array' THEN
                        RAISE EXCEPTION 'runtime submit outcomes payload must be a JSON array';
                      END IF;

                      WITH outcomes AS (
                        SELECT outcome, ordinality::BIGINT AS outcome_ordinality
                        FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
                      ),
                      upsert_results AS (
                        INSERT INTO ${names.submitResults}(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
                        SELECT
                          outcome->>'commandId',
                          outcome->>'resultType',
                          outcome->>'eventId',
                          outcome->>'orderId',
                          outcome->>'engineOrderId',
                          outcome->>'code',
                          outcome->>'reason',
                          outcome->>'occurredAt'
                        FROM outcomes
                        ON CONFLICT (command_id) DO UPDATE SET
                          result_type = EXCLUDED.result_type,
                          event_id = EXCLUDED.event_id,
                          order_id = EXCLUDED.order_id,
                          engine_order_id = EXCLUDED.engine_order_id,
                          code = EXCLUDED.code,
                          reason = EXCLUDED.reason,
                          occurred_at = EXCLUDED.occurred_at
                        RETURNING 1
                      ),
                      accepted_orders AS (
                        SELECT NULLIF(outcome->'acceptedOrder', 'null'::jsonb) AS accepted_order
                        FROM outcomes
                      ),
                      upsert_orders AS (
                        INSERT INTO ${names.orders}(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at)
                        SELECT
                          accepted_order->>'orderId',
                          accepted_order->>'engineOrderId',
                          accepted_order->>'instrumentId',
                          accepted_order->>'participantId',
                          accepted_order->>'accountId',
                          accepted_order->>'side',
                          accepted_order->>'orderType',
                          accepted_order->>'quantityUnits',
                          accepted_order->>'limitPrice',
                          accepted_order->>'currency',
                          accepted_order->>'timeInForce',
                          accepted_order->>'acceptedAt'
                        FROM accepted_orders
                        WHERE accepted_order IS NOT NULL
                          AND jsonb_typeof(accepted_order) = 'object'
                        ON CONFLICT (order_id) DO UPDATE SET
                          engine_order_id = EXCLUDED.engine_order_id,
                          instrument_id = EXCLUDED.instrument_id,
                          participant_id = EXCLUDED.participant_id,
                          account_id = EXCLUDED.account_id,
                          side = EXCLUDED.side,
                          order_type = EXCLUDED.order_type,
                          quantity_units = EXCLUDED.quantity_units,
                          limit_price = EXCLUDED.limit_price,
                          currency = EXCLUDED.currency,
                          time_in_force = EXCLUDED.time_in_force,
                          accepted_at = EXCLUDED.accepted_at
                        RETURNING 1
                      ),
                      insert_executions AS (
                        INSERT INTO ${names.executions}(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at)
                        SELECT
                          execution->>'eventId',
                          execution->>'executionId',
                          execution->>'orderId',
                          execution->>'instrumentId',
                          execution->>'quantityUnits',
                          execution->>'executionPrice',
                          execution->>'currency',
                          execution->>'occurredAt'
                        FROM outcomes
                        CROSS JOIN LATERAL jsonb_array_elements(
                          CASE
                            WHEN jsonb_typeof(outcome->'executions') = 'array' THEN outcome->'executions'
                            ELSE '[]'::jsonb
                          END
                        ) AS execution
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING 1
                      ),
                      insert_trades AS (
                        INSERT INTO ${names.trades}(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at)
                        SELECT
                          trade->>'eventId',
                          trade->>'tradeId',
                          trade->>'executionId',
                          trade->>'buyOrderId',
                          trade->>'sellOrderId',
                          trade->>'instrumentId',
                          trade->>'quantityUnits',
                          trade->>'price',
                          trade->>'currency',
                          trade->>'occurredAt'
                        FROM outcomes
                        CROSS JOIN LATERAL jsonb_array_elements(
                          CASE
                            WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades'
                            ELSE '[]'::jsonb
                          END
                        ) AS trade
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING 1
                      ),
                      parsed_events AS (
                        SELECT
                          event,
                          outcomes.outcome_ordinality,
                          event_ordinality::BIGINT AS event_ordinality
                        FROM outcomes
                        CROSS JOIN LATERAL jsonb_array_elements(
                          CASE
                            WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
                            ELSE '[]'::jsonb
                          END
                        ) WITH ORDINALITY AS event_rows(event, event_ordinality)
                      ),
                      trace_counts AS (
                        SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
                        FROM parsed_events
                        GROUP BY event->>'traceId'
                      ),
                      trace_allocations AS (
                        INSERT INTO ${names.runtimeTraceSequences} AS trace_sequence(trace_id, next_sequence)
                        SELECT trace_id, event_count FROM trace_counts
                        ON CONFLICT (trace_id) DO UPDATE SET next_sequence = trace_sequence.next_sequence + EXCLUDED.next_sequence
                        RETURNING trace_id, next_sequence
                      ),
                      trace_starts AS (
                        SELECT
                          counts.trace_id,
                          allocations.next_sequence - counts.event_count + 1 AS start_sequence
                        FROM trace_counts counts
                        JOIN trace_allocations allocations ON allocations.trace_id = counts.trace_id
                      ),
                      ordered_events AS (
                        SELECT
                          parsed.event,
                          parsed.outcome_ordinality,
                          parsed.event_ordinality,
                          row_number() OVER (
                            PARTITION BY parsed.event->>'traceId'
                            ORDER BY parsed.outcome_ordinality, parsed.event_ordinality
                          ) - 1 AS trace_offset
                        FROM parsed_events parsed
                      ),
                      insert_events AS (
                        INSERT INTO ${names.runtimeEvents}(event_id, event_type, order_id, trace_id, causation_id, correlation_id, actor_id, producer, schema_version, sequence_number, payload_json, occurred_at)
                        SELECT
                          event->>'eventId',
                          event->>'eventType',
                          event->>'orderId',
                          event->>'traceId',
                          event->>'causationId',
                          event->>'correlationId',
                          COALESCE(event->>'actorId', ''),
                          event->>'producer',
                          event->>'schemaVersion',
                          trace_starts.start_sequence + ordered_events.trace_offset,
                          COALESCE(event->'payloadJson', '{}'::jsonb),
                          event->>'occurredAt'
                        FROM ordered_events
                        JOIN trace_starts ON trace_starts.trace_id = ordered_events.event->>'traceId'
                        ORDER BY ordered_events.outcome_ordinality, ordered_events.event_ordinality
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING 1
                      )
                      SELECT COUNT(*) INTO persisted_count FROM outcomes;

                      RETURN persisted_count;
                    END;
                    $$;
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.projectCanonicalSubmitOutcomesFunction}(
                      p_projection_name TEXT,
                      p_batch_size INTEGER
                    )
                    RETURNS BIGINT
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                      projected_count BIGINT := 0;
                    BEGIN
                      IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
                        RETURN 0;
                      END IF;

                      WITH eligible AS (
                        SELECT
                          canonical.partition_id,
                          canonical.partition_seq,
                          canonical.result_payload
                        FROM ${names.canonicalCommandResults} canonical
                        LEFT JOIN ${names.projectionWatermarks} watermark
                          ON watermark.projection_name = p_projection_name
                         AND watermark.partition_id = canonical.partition_id
                        WHERE canonical.partition_seq > COALESCE(watermark.last_partition_seq, 0)
                        ORDER BY canonical.partition_id, canonical.partition_seq
                        LIMIT p_batch_size
                      ),
                      projected AS (
                        SELECT ${names.persistSubmitOutcomesFunction}(
                          COALESCE(
                            jsonb_agg(result_payload ORDER BY partition_id, partition_seq),
                            '[]'::jsonb
                          )
                        ) AS count
                        FROM eligible
                      ),
                      partition_max AS (
                        SELECT partition_id, MAX(partition_seq) AS last_partition_seq
                        FROM eligible
                        GROUP BY partition_id
                      ),
                      upsert_watermarks AS (
                        INSERT INTO ${names.projectionWatermarks}(
                          projection_name,
                          partition_id,
                          last_partition_seq,
                          last_projected_at,
                          updated_at,
                          last_error
                        )
                        SELECT
                          p_projection_name,
                          partition_id,
                          last_partition_seq,
                          now(),
                          now(),
                          ''
                        FROM partition_max
                        ON CONFLICT (projection_name, partition_id) DO UPDATE SET
                          last_partition_seq = GREATEST(
                            ${names.projectionWatermarks}.last_partition_seq,
                            EXCLUDED.last_partition_seq
                          ),
                          last_projected_at = EXCLUDED.last_projected_at,
                          updated_at = EXCLUDED.updated_at,
                          last_error = ''
                        RETURNING 1
                      )
                      SELECT COALESCE(MAX(count), 0) INTO projected_count FROM projected;

                      RETURN projected_count;
                    EXCEPTION WHEN OTHERS THEN
                      INSERT INTO ${names.projectionWatermarks}(
                        projection_name,
                        partition_id,
                        last_partition_seq,
                        last_projected_at,
                        updated_at,
                        last_error
                      )
                      VALUES (p_projection_name, -1, 0, NULL, now(), SQLERRM)
                      ON CONFLICT (projection_name, partition_id) DO UPDATE SET
                        updated_at = EXCLUDED.updated_at,
                        last_error = EXCLUDED.last_error;
                      RAISE;
                    END;
                    $$;
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    DROP FUNCTION IF EXISTS ${names.projectCanonicalSubmitOutcomesFunction}(TEXT, INTEGER)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.projectCanonicalSubmitOutcomesFunction}(
                      p_projection_name TEXT,
                      p_batch_size INTEGER,
                      p_partitions INTEGER[] DEFAULT NULL
                    )
                    RETURNS BIGINT
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                      projected_count BIGINT := 0;
                    BEGIN
                      IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
                        RETURN 0;
                      END IF;

                      WITH selected_partitions AS (
                        SELECT DISTINCT partition_id
                        FROM (
                          SELECT unnest(p_partitions) AS partition_id
                          WHERE p_partitions IS NOT NULL AND cardinality(p_partitions) > 0
                          UNION ALL
                          SELECT DISTINCT partition_id
                          FROM ${names.canonicalCommandResults}
                          WHERE p_partitions IS NULL OR cardinality(p_partitions) = 0
                        ) partitions
                      ),
                      partition_budget AS (
                        SELECT GREATEST(
                          1,
                          CEIL(p_batch_size::NUMERIC / GREATEST((SELECT COUNT(*) FROM selected_partitions), 1))::INTEGER
                        ) AS per_partition_limit
                      ),
                      ranked AS (
                        SELECT
                          canonical.partition_id,
                          canonical.partition_seq,
                          canonical.result_payload,
                          row_number() OVER (
                            PARTITION BY canonical.partition_id
                            ORDER BY canonical.partition_seq
                          ) AS partition_row
                        FROM ${names.canonicalCommandResults} canonical
                        JOIN selected_partitions selected
                          ON selected.partition_id = canonical.partition_id
                        LEFT JOIN ${names.projectionWatermarks} watermark
                          ON watermark.projection_name = p_projection_name
                         AND watermark.partition_id = canonical.partition_id
                        WHERE canonical.partition_seq > COALESCE(watermark.last_partition_seq, 0)
                      ),
                      eligible AS (
                        SELECT partition_id, partition_seq, result_payload
                        FROM ranked
                        CROSS JOIN partition_budget
                        WHERE partition_row <= partition_budget.per_partition_limit
                        ORDER BY partition_row, partition_id, partition_seq
                        LIMIT p_batch_size
                      ),
                      projected AS (
                        SELECT ${names.persistSubmitOutcomesFunction}(
                          COALESCE(
                            jsonb_agg(result_payload ORDER BY partition_seq, partition_id),
                            '[]'::jsonb
                          )
                        ) AS count
                        FROM eligible
                      ),
                      partition_max AS (
                        SELECT partition_id, MAX(partition_seq) AS last_partition_seq
                        FROM eligible
                        GROUP BY partition_id
                      ),
                      upsert_watermarks AS (
                        INSERT INTO ${names.projectionWatermarks}(
                          projection_name,
                          partition_id,
                          last_partition_seq,
                          last_projected_at,
                          updated_at,
                          last_error
                        )
                        SELECT
                          p_projection_name,
                          partition_id,
                          last_partition_seq,
                          now(),
                          now(),
                          ''
                        FROM partition_max
                        ON CONFLICT (projection_name, partition_id) DO UPDATE SET
                          last_partition_seq = GREATEST(
                            ${names.projectionWatermarks}.last_partition_seq,
                            EXCLUDED.last_partition_seq
                          ),
                          last_projected_at = EXCLUDED.last_projected_at,
                          updated_at = EXCLUDED.updated_at,
                          last_error = ''
                        RETURNING 1
                      )
                      SELECT COALESCE(MAX(count), 0) INTO projected_count FROM projected;

                      RETURN projected_count;
                    EXCEPTION WHEN OTHERS THEN
                      INSERT INTO ${names.projectionWatermarks}(
                        projection_name,
                        partition_id,
                        last_partition_seq,
                        last_projected_at,
                        updated_at,
                        last_error
                      )
                      VALUES (p_projection_name, -1, 0, NULL, now(), SQLERRM)
                      ON CONFLICT (projection_name, partition_id) DO UPDATE SET
                        updated_at = EXCLUDED.updated_at,
                        last_error = EXCLUDED.last_error;
                      RAISE;
                    END;
                    $$;
                    """.trimIndent()
                )
            }
        }
        if (bootstrapMode == PostgresBootstrapMode.Validate && projectionStoreSeparated()) {
            projectionConnection().use { conn ->
                PostgresSchemaValidator.validate(conn, PostgresSchemaRequirements.runtime(names))
            }
        }
    }

    override fun saveSubmitResult(commandId: String, result: SubmitOrderResult) {
        val accepted = result.accepted
        val rejected = result.rejected
        val resultType = if (accepted != null) "accepted" else "rejected"
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.submitResults}(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (command_id) DO UPDATE SET
                  result_type = EXCLUDED.result_type,
                  event_id = EXCLUDED.event_id,
                  order_id = EXCLUDED.order_id,
                  engine_order_id = EXCLUDED.engine_order_id,
                  code = EXCLUDED.code,
                  reason = EXCLUDED.reason,
                  occurred_at = EXCLUDED.occurred_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setString(2, resultType)
                ps.setString(3, accepted?.eventId ?: rejected?.eventId.orEmpty())
                ps.setString(4, accepted?.orderId ?: rejected?.orderId.orEmpty())
                ps.setString(5, accepted?.engineOrderId.orEmpty())
                ps.setString(6, rejected?.code.orEmpty())
                ps.setString(7, rejected?.reason.orEmpty())
                ps.setString(8, accepted?.occurredAt ?: rejected?.occurredAt.orEmpty())
                ps.executeUpdate()
            }
        }
    }

    override fun submitResult(commandId: String): SubmitOrderResult? {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                "SELECT result_type, event_id, order_id, engine_order_id, code, reason, occurred_at FROM ${names.submitResults} WHERE command_id = ?"
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return canonicalSubmitResult(commandId)
                    val resultType = rs.getString("result_type")
                    val orderId = rs.getString("order_id")
                    return if (resultType == "accepted") {
                        SubmitOrderResult(
                            accepted = EngineOrderAccepted(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                engineOrderId = rs.getString("engine_order_id"),
                                occurredAt = rs.getString("occurred_at")
                            ),
                            executions = executionsForOrder(orderId),
                            trades = tradesForOrder(orderId)
                        )
                    } else {
                        SubmitOrderResult(
                            rejected = EngineOrderRejected(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                code = rs.getString("code"),
                                reason = rs.getString("reason"),
                                occurredAt = rs.getString("occurred_at")
                            )
                        )
                    }
                }
            }
        }
    }

    private fun canonicalSubmitResult(commandId: String): SubmitOrderResult? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  result_payload->>'resultType' AS result_type,
                  result_payload->>'eventId' AS event_id,
                  result_payload->>'orderId' AS order_id,
                  result_payload->>'engineOrderId' AS engine_order_id,
                  result_payload->>'code' AS code,
                  result_payload->>'reason' AS reason,
                  result_payload->>'occurredAt' AS occurred_at
                FROM ${names.canonicalCommandResults}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val resultType = rs.getString("result_type")
                    val orderId = rs.getString("order_id")
                    return if (resultType == "accepted") {
                        SubmitOrderResult(
                            accepted = EngineOrderAccepted(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                engineOrderId = rs.getString("engine_order_id"),
                                occurredAt = rs.getString("occurred_at")
                            )
                        )
                    } else {
                        SubmitOrderResult(
                            rejected = EngineOrderRejected(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                code = rs.getString("code"),
                                reason = rs.getString("reason"),
                                occurredAt = rs.getString("occurred_at")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun saveInstrument(instrument: Instrument) {
        upsert("${names.referenceInstruments}", "instrument_id", instrument.instrumentId, instrument.symbol)
    }

    override fun saveParticipant(participant: Participant) {
        upsert("${names.referenceParticipants}", "participant_id", participant.participantId, participant.name)
    }

    override fun saveAccount(account: Account) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.referenceAccounts}(account_id, participant_id)
                VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET participant_id = EXCLUDED.participant_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, account.accountId)
                ps.setString(2, account.participantId)
                ps.executeUpdate()
            }
        }
    }

    override fun saveRole(role: RoleDefinition) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.authRoles}(role_id, permissions)
                VALUES (?, ?)
                ON CONFLICT (role_id) DO UPDATE SET permissions = EXCLUDED.permissions
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, role.roleId)
                ps.setString(2, role.permissions.joinToString(","))
                ps.executeUpdate()
            }
        }
    }

    override fun saveActorRoleBinding(binding: ActorRoleBinding) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.authActorRoles}(actor_id, role_id)
                VALUES (?, ?)
                ON CONFLICT (actor_id, role_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, binding.actorId)
                ps.setString(2, binding.roleId)
                ps.executeUpdate()
            }
        }
    }

    override fun instruments(): List<Instrument> = queryList("SELECT instrument_id, symbol FROM ${names.referenceInstruments}") {
        Instrument(getString("instrument_id"), getString("symbol"))
    }

    override fun participants(): List<Participant> = queryList("SELECT participant_id, name FROM ${names.referenceParticipants}") {
        Participant(getString("participant_id"), getString("name"))
    }

    override fun accounts(): List<Account> = queryList("SELECT account_id, participant_id FROM ${names.referenceAccounts}") {
        Account(getString("account_id"), getString("participant_id"))
    }

    override fun roles(): List<RoleDefinition> = queryList("SELECT role_id, permissions FROM ${names.authRoles}") {
        RoleDefinition(
            roleId = getString("role_id"),
            permissions = getString("permissions").split(",").filter { it.isNotBlank() }
        )
    }

    override fun actorRoleBindings(actorId: String): List<ActorRoleBinding> {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT actor_id, role_id FROM ${names.authActorRoles} WHERE actor_id = ?"
            ).use { ps ->
                ps.setString(1, actorId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ActorRoleBinding>()
                    while (rs.next()) {
                        out.add(
                            ActorRoleBinding(
                                actorId = rs.getString("actor_id"),
                                roleId = rs.getString("role_id")
                            )
                        )
                    }
                    return out
                }
            }
        }
    }

    override fun hasInstrument(instrumentId: String): Boolean = exists("${names.referenceInstruments}", "instrument_id", instrumentId)

    override fun hasParticipant(participantId: String): Boolean = exists("${names.referenceParticipants}", "participant_id", participantId)

    override fun hasAccount(accountId: String): Boolean = exists("${names.referenceAccounts}", "account_id", accountId)

    override fun validateReferenceData(instrumentId: String, participantId: String, accountId: String): ReferenceDataValidation {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT instrument_exists, participant_exists, account_exists, account_belongs_to_participant
                FROM ${names.validateReferenceDataFunction}(?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.setString(2, participantId)
                ps.setString(3, accountId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return ReferenceDataValidation(
                        instrumentExists = rs.getBoolean("instrument_exists"),
                        participantExists = rs.getBoolean("participant_exists"),
                        accountExists = rs.getBoolean("account_exists"),
                        accountBelongsToParticipant = rs.getBoolean("account_belongs_to_participant")
                    )
                }
            }
        }
    }

    override fun persistSubmitOutcome(
        commandId: String,
        result: SubmitOrderResult,
        acceptedOrder: PersistedOrder?,
        lifecycleEvents: List<RuntimeEvent>
    ) {
        val accepted = result.accepted
        val rejected = result.rejected
        val resultType = if (accepted != null) "accepted" else "rejected"

        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.persistSubmitOutcomeFunction}(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb
                )
                """.trimIndent()
            ).use { ps ->
                ps.bindSubmitOutcome(commandId, resultType, accepted, rejected, acceptedOrder, result, lifecycleEvents)
                ps.execute()
            }
        }
    }

    override fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {
        if (outcomes.isEmpty()) return
        projectionConnection().use { conn ->
            persistSubmitOutcomePayloads(conn, outcomes.toJsonArray { it.toJsonObject() }, outcomes.size)
        }
    }

    override fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        if (outcomes.isEmpty()) return
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                "SELECT set_config('reef.stream_ack_canonical_event_rows_enabled', ?, FALSE)"
            ).use { ps ->
                ps.setString(1, streamAckCanonicalEventRowsEnabled.toString())
                ps.execute()
            }
            conn.prepareStatement(
                """
                SELECT ${names.appendCanonicalSubmitOutcomesFunction}(?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, outcomes.toJsonArray { it.toJsonObject() })
                ps.executeQuery().use { rs ->
                    rs.next()
                    check(rs.getLong(1) == outcomes.size.toLong()) {
                        "Appended canonical submit outcome count did not match requested batch size"
                    }
                }
            }
        }
    }

    override fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int>): Long {
        if (batchSize <= 0) return 0
        if (projectionStoreSeparated()) {
            return projectCanonicalSubmitOutcomesAcrossStores(projectionName, batchSize, partitions)
        }
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectCanonicalSubmitOutcomesFunction}(?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setInt(2, batchSize)
                ps.setArray(3, conn.createArrayOf("integer", partitions.toTypedArray()))
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun projectCanonicalSubmitOutcomesAcrossStores(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>
    ): Long {
        val ownedPartitions = ownedProjectionPartitions(partitions)
        if (ownedPartitions.isEmpty()) return 0
        val watermarks = projectionWatermarkMap(projectionName, ownedPartitions)
        val perPartitionLimit = ((batchSize + ownedPartitions.size - 1) / ownedPartitions.size).coerceAtLeast(1)
        val candidates = ownedPartitions
            .flatMap { partitionId ->
                canonicalProjectionCandidates(
                    partitionId = partitionId,
                    afterPartitionSequence = watermarks[partitionId] ?: 0L,
                    limit = perPartitionLimit
                ).mapIndexed { index, candidate -> candidate.copy(partitionRow = index + 1) }
            }
            .sortedWith(
                compareBy<ProjectionCandidate> { it.partitionRow }
                    .thenBy { it.partitionId }
                    .thenBy { it.partitionSequence }
            )
            .take(batchSize)
        if (candidates.isEmpty()) return 0

        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val payloadJson = candidates.joinToString(prefix = "[", postfix = "]") { it.resultPayload }
                persistSubmitOutcomePayloads(conn, payloadJson, candidates.size)
                updateProjectionWatermarks(conn, projectionName, candidates)
                conn.commit()
                return candidates.size.toLong()
            } catch (ex: Exception) {
                conn.rollback()
                recordProjectionFailure(conn, projectionName, ex.message ?: ex::class.simpleName ?: "unknown")
                conn.commit()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun ownedProjectionPartitions(partitions: List<Int>): List<Int> {
        if (partitions.isNotEmpty()) return partitions.distinct().sorted()
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT partition_id
                FROM ${names.canonicalCommandResults}
                ORDER BY partition_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Int>()
                    while (rs.next()) out.add(rs.getInt("partition_id"))
                    return out
                }
            }
        }
    }

    private fun projectionWatermarkMap(projectionName: String, partitions: List<Int>): Map<Int, Long> {
        if (partitions.isEmpty()) return emptyMap()
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT partition_id, last_partition_seq
                FROM ${names.projectionWatermarks}
                WHERE projection_name = ?
                  AND partition_id = ANY(?::INTEGER[])
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setArray(2, conn.createArrayOf("integer", partitions.toTypedArray()))
                ps.executeQuery().use { rs ->
                    val out = mutableMapOf<Int, Long>()
                    while (rs.next()) out[rs.getInt("partition_id")] = rs.getLong("last_partition_seq")
                    return out
                }
            }
        }
    }

    private fun canonicalProjectionCandidates(
        partitionId: Int,
        afterPartitionSequence: Long,
        limit: Int
    ): List<ProjectionCandidate> {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT partition_id, partition_seq, result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandResults}
                WHERE partition_id = ?
                  AND partition_seq > ?
                ORDER BY partition_seq
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, partitionId)
                ps.setLong(2, afterPartitionSequence)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ProjectionCandidate>()
                    while (rs.next()) {
                        out.add(
                            ProjectionCandidate(
                                partitionId = rs.getInt("partition_id"),
                                partitionSequence = rs.getLong("partition_seq"),
                                resultPayload = rs.getString("result_payload")
                            )
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun persistSubmitOutcomePayloads(conn: Connection, payloadJson: String, expectedCount: Int) {
        conn.prepareStatement(
            """
            SELECT ${names.persistSubmitOutcomesFunction}(?::jsonb)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, payloadJson)
            ps.executeQuery().use { rs ->
                rs.next()
                check(rs.getLong(1) == expectedCount.toLong()) {
                    "Persisted submit outcome count did not match requested batch size"
                }
            }
        }
    }

    private fun updateProjectionWatermarks(
        conn: Connection,
        projectionName: String,
        candidates: List<ProjectionCandidate>
    ) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.projectionWatermarks}(
              projection_name,
              partition_id,
              last_partition_seq,
              last_projected_at,
              updated_at,
              last_error
            )
            VALUES (?, ?, ?, now(), now(), '')
            ON CONFLICT (projection_name, partition_id) DO UPDATE SET
              last_partition_seq = GREATEST(
                ${names.projectionWatermarks}.last_partition_seq,
                EXCLUDED.last_partition_seq
              ),
              last_projected_at = EXCLUDED.last_projected_at,
              updated_at = EXCLUDED.updated_at,
              last_error = ''
            """.trimIndent()
        ).use { ps ->
            candidates
                .groupBy { it.partitionId }
                .forEach { (partitionId, partitionCandidates) ->
                    ps.setString(1, projectionName)
                    ps.setInt(2, partitionId)
                    ps.setLong(3, partitionCandidates.maxOf { it.partitionSequence })
                    ps.addBatch()
                }
            ps.executeBatch()
        }
    }

    private fun recordProjectionFailure(conn: Connection, projectionName: String, error: String) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.projectionWatermarks}(
              projection_name,
              partition_id,
              last_partition_seq,
              last_projected_at,
              updated_at,
              last_error
            )
            VALUES (?, -1, 0, NULL, now(), ?)
            ON CONFLICT (projection_name, partition_id) DO UPDATE SET
              updated_at = EXCLUDED.updated_at,
              last_error = EXCLUDED.last_error
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, projectionName)
            ps.setString(2, error)
            ps.executeUpdate()
        }
    }

    override fun projectionStatus(projectionName: String, partitions: List<Int>): ProjectionStatus {
        if (projectionStoreSeparated()) {
            return projectionStatusAcrossStores(projectionName, partitions)
        }
        canonicalConnection().use { conn ->
            val watermarks = conn.prepareStatement(
                """
                WITH requested_partitions AS (
                  SELECT unnest(?::INTEGER[]) AS partition_id
                ),
                watermark_partitions AS (
                  SELECT
                    projection_name,
                    partition_id,
                    last_partition_seq,
                    updated_at,
                    last_error
                  FROM ${names.projectionWatermarks}
                  WHERE projection_name = ?
                    AND (
                      cardinality(?::INTEGER[]) = 0
                      OR partition_id IN (SELECT partition_id FROM requested_partitions)
                      OR partition_id = -1
                    )
                ),
                canonical_partitions AS (
                  SELECT
                    canonical.partition_id,
                    MAX(canonical.partition_seq) AS canonical_max_partition_seq,
                    COUNT(*) FILTER (
                      WHERE canonical.partition_seq > COALESCE(watermark_partitions.last_partition_seq, 0)
                    ) AS lag
                  FROM ${names.canonicalCommandResults} canonical
                  LEFT JOIN watermark_partitions
                    ON watermark_partitions.partition_id = canonical.partition_id
                  WHERE cardinality(?::INTEGER[]) = 0 OR canonical.partition_id IN (SELECT partition_id FROM requested_partitions)
                  GROUP BY canonical.partition_id
                ),
                owned_partitions AS (
                  SELECT partition_id FROM requested_partitions
                  UNION
                  SELECT partition_id FROM canonical_partitions
                  UNION
                  SELECT partition_id FROM watermark_partitions WHERE partition_id >= 0
                )
                SELECT
                  COALESCE(watermark_partitions.projection_name, ?) AS projection_name,
                  owned_partitions.partition_id,
                  COALESCE(watermark_partitions.last_partition_seq, 0) AS last_partition_seq,
                  COALESCE(canonical_partitions.canonical_max_partition_seq, 0) AS canonical_max_partition_seq,
                  COALESCE(canonical_partitions.lag, 0) AS lag,
                  COALESCE(watermark_partitions.updated_at::TEXT, '') AS updated_at,
                  COALESCE(watermark_partitions.last_error, '') AS last_error
                FROM owned_partitions
                LEFT JOIN canonical_partitions
                  ON canonical_partitions.partition_id = owned_partitions.partition_id
                LEFT JOIN watermark_partitions
                  ON watermark_partitions.partition_id = owned_partitions.partition_id
                UNION ALL
                SELECT
                  watermark_partitions.projection_name,
                  watermark_partitions.partition_id,
                  watermark_partitions.last_partition_seq,
                  0,
                  0,
                  watermark_partitions.updated_at::TEXT,
                  watermark_partitions.last_error
                FROM watermark_partitions
                WHERE watermark_partitions.partition_id = -1
                ORDER BY partition_id
                """.trimIndent()
            ).use { ps ->
                val partitionArray = conn.createArrayOf("integer", partitions.toTypedArray())
                ps.setArray(1, partitionArray)
                ps.setString(2, projectionName)
                ps.setArray(3, partitionArray)
                ps.setArray(4, partitionArray)
                ps.setString(5, projectionName)
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<ProjectionWatermark>()
                    while (rs.next()) {
                        rows.add(
                            ProjectionWatermark(
                                projectionName = rs.getString("projection_name"),
                                partitionId = rs.getInt("partition_id"),
                                lastPartitionSequence = rs.getLong("last_partition_seq"),
                                canonicalMaxPartitionSequence = rs.getLong("canonical_max_partition_seq"),
                                lag = rs.getLong("lag"),
                                updatedAt = rs.getString("updated_at"),
                                lastError = rs.getString("last_error")
                            )
                        )
                    }
                    rows
                }
            }
            val projectedCount = conn.prepareStatement(
                "SELECT COUNT(*) FROM ${names.submitResults}"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            return ProjectionStatus(
                projectionName = projectionName,
                projectedCount = projectedCount,
                lag = watermarks.sumOf { it.lag },
                watermarks = watermarks
            )
        }
    }

    private fun projectionStatusAcrossStores(projectionName: String, partitions: List<Int>): ProjectionStatus {
        val watermarkRows = projectionWatermarkRows(projectionName, partitions)
        val canonicalStatsByPartition = canonicalPartitionStats(partitions, watermarkRows)
        val partitionIds = (partitions + canonicalStatsByPartition.keys + watermarkRows.keys)
            .filter { it >= 0 }
            .distinct()
            .sorted()
        val watermarks = partitionIds.map { partitionId ->
            val watermark = watermarkRows[partitionId]
            val canonicalStats = canonicalStatsByPartition[partitionId]
            val projected = watermark?.lastPartitionSequence ?: 0L
            ProjectionWatermark(
                projectionName = watermark?.projectionName ?: projectionName,
                partitionId = partitionId,
                lastPartitionSequence = projected,
                canonicalMaxPartitionSequence = canonicalStats?.maxPartitionSequence ?: 0L,
                lag = canonicalStats?.backlogCount ?: 0L,
                updatedAt = watermark?.updatedAt.orEmpty(),
                lastError = watermark?.lastError.orEmpty()
            )
        } + watermarkRows.values.filter { it.partitionId == -1 }
        val projectedCount = projectionConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM ${names.submitResults}").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
        return ProjectionStatus(
            projectionName = projectionName,
            projectedCount = projectedCount,
            lag = watermarks.sumOf { it.lag },
            watermarks = watermarks.sortedBy { it.partitionId }
        )
    }

    override fun materializeVenueEventBatch(batch: VenueEventBatchFact): Long {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.materializeVenueEventBatchFunction}(?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, batch.toJsonObject())
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun canonicalCommandOutcome(commandId: String): CanonicalCommandOutcome? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  command_id,
                  batch_id,
                  shard_id,
                  partition_id,
                  command_stream,
                  event_stream,
                  stream_sequence,
                  delivered_count,
                  command_type,
                  payload_hash,
                  instrument_id,
                  order_id,
                  result_status,
                  reject_code,
                  result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return CanonicalCommandOutcome(
                        commandId = rs.getString("command_id"),
                        batchId = rs.getString("batch_id"),
                        shardId = rs.getString("shard_id"),
                        partition = rs.getInt("partition_id"),
                        commandStream = rs.getString("command_stream"),
                        eventStream = rs.getString("event_stream"),
                        streamSequence = rs.getLong("stream_sequence"),
                        deliveredCount = rs.getLong("delivered_count"),
                        commandType = rs.getString("command_type"),
                        payloadHash = rs.getString("payload_hash"),
                        instrumentId = rs.getString("instrument_id"),
                        orderId = rs.getString("order_id"),
                        resultStatus = rs.getString("result_status"),
                        rejectCode = rs.getString("reject_code"),
                        resultPayloadJson = rs.getString("result_payload")
                    )
                }
            }
        }
    }

    private data class CanonicalPartitionStats(val maxPartitionSequence: Long, val backlogCount: Long)

    private fun canonicalPartitionStats(
        partitions: List<Int>,
        watermarks: Map<Int, ProjectionWatermark>
    ): Map<Int, CanonicalPartitionStats> {
        canonicalConnection().use { conn ->
            val watermarkPartitions = watermarks.keys.filter { it >= 0 }.sorted()
            val watermarkSequences = watermarkPartitions.map { watermarks.getValue(it).lastPartitionSequence }
            conn.prepareStatement(
                """
                WITH watermark_partitions AS (
                  SELECT *
                  FROM unnest(?::INTEGER[], ?::BIGINT[]) AS watermark(partition_id, last_partition_seq)
                )
                SELECT
                  canonical.partition_id,
                  MAX(canonical.partition_seq) AS canonical_max_partition_seq,
                  COUNT(*) FILTER (
                    WHERE canonical.partition_seq > COALESCE(watermark_partitions.last_partition_seq, 0)
                  ) AS lag
                FROM ${names.canonicalCommandResults} canonical
                LEFT JOIN watermark_partitions
                  ON watermark_partitions.partition_id = canonical.partition_id
                WHERE cardinality(?::INTEGER[]) = 0 OR canonical.partition_id = ANY(?::INTEGER[])
                GROUP BY canonical.partition_id
                """.trimIndent()
            ).use { ps ->
                val partitionArray = conn.createArrayOf("integer", partitions.toTypedArray())
                ps.setArray(1, conn.createArrayOf("integer", watermarkPartitions.toTypedArray()))
                ps.setArray(2, conn.createArrayOf("bigint", watermarkSequences.toTypedArray()))
                ps.setArray(3, partitionArray)
                ps.setArray(4, partitionArray)
                ps.executeQuery().use { rs ->
                    val out = mutableMapOf<Int, CanonicalPartitionStats>()
                    while (rs.next()) {
                        out[rs.getInt("partition_id")] = CanonicalPartitionStats(
                            maxPartitionSequence = rs.getLong("canonical_max_partition_seq"),
                            backlogCount = rs.getLong("lag")
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun projectionWatermarkRows(
        projectionName: String,
        partitions: List<Int>
    ): Map<Int, ProjectionWatermark> {
        projectionConnection().use { conn ->
            val sql = if (partitions.isEmpty()) {
                """
                SELECT projection_name, partition_id, last_partition_seq, updated_at::TEXT AS updated_at, last_error
                FROM ${names.projectionWatermarks}
                WHERE projection_name = ?
                """.trimIndent()
            } else {
                """
                SELECT projection_name, partition_id, last_partition_seq, updated_at::TEXT AS updated_at, last_error
                FROM ${names.projectionWatermarks}
                WHERE projection_name = ?
                  AND (partition_id = ANY(?::INTEGER[]) OR partition_id = -1)
                """.trimIndent()
            }
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, projectionName)
                if (partitions.isNotEmpty()) {
                    ps.setArray(2, conn.createArrayOf("integer", partitions.toTypedArray()))
                }
                ps.executeQuery().use { rs ->
                    val out = mutableMapOf<Int, ProjectionWatermark>()
                    while (rs.next()) {
                        val partitionId = rs.getInt("partition_id")
                        out[partitionId] = ProjectionWatermark(
                            projectionName = rs.getString("projection_name"),
                            partitionId = partitionId,
                            lastPartitionSequence = rs.getLong("last_partition_seq"),
                            canonicalMaxPartitionSequence = 0,
                            lag = 0,
                            updatedAt = rs.getString("updated_at"),
                            lastError = rs.getString("last_error")
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun PreparedStatement.bindSubmitOutcome(
        commandId: String,
        resultType: String,
        accepted: EngineOrderAccepted?,
        rejected: EngineOrderRejected?,
        acceptedOrder: PersistedOrder?,
        result: SubmitOrderResult,
        lifecycleEvents: List<RuntimeEvent>
    ) {
        setString(1, commandId)
        setString(2, resultType)
        setString(3, accepted?.eventId ?: rejected?.eventId.orEmpty())
        setString(4, accepted?.orderId ?: rejected?.orderId.orEmpty())
        setString(5, accepted?.engineOrderId.orEmpty())
        setString(6, rejected?.code.orEmpty())
        setString(7, rejected?.reason.orEmpty())
        setString(8, accepted?.occurredAt ?: rejected?.occurredAt.orEmpty())
        setString(9, acceptedOrder?.toJsonObject())
        setString(10, result.executions.toJsonArray { it.toJsonObject() })
        setString(11, result.trades.toJsonArray { it.toJsonObject() })
        setString(12, lifecycleEvents.toJsonArray { it.toJsonObject() })
    }

    override fun saveAcceptedOrder(order: PersistedOrder) {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.orders}(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET
                  engine_order_id = EXCLUDED.engine_order_id,
                  instrument_id = EXCLUDED.instrument_id,
                  participant_id = EXCLUDED.participant_id,
                  account_id = EXCLUDED.account_id,
                  side = EXCLUDED.side,
                  order_type = EXCLUDED.order_type,
                  quantity_units = EXCLUDED.quantity_units,
                  limit_price = EXCLUDED.limit_price,
                  currency = EXCLUDED.currency,
                  time_in_force = EXCLUDED.time_in_force,
                  accepted_at = EXCLUDED.accepted_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, order.orderId)
                ps.setString(2, order.engineOrderId)
                ps.setString(3, order.instrumentId)
                ps.setString(4, order.participantId)
                ps.setString(5, order.accountId)
                ps.setString(6, order.side)
                ps.setString(7, order.orderType)
                ps.setString(8, order.quantityUnits)
                ps.setString(9, order.limitPrice)
                ps.setString(10, order.currency)
                ps.setString(11, order.timeInForce)
                ps.setString(12, order.acceptedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun saveExecutions(executions: List<ExecutionCreated>) {
        if (executions.isEmpty()) return
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.executions}(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                executions.forEach { execution ->
                    ps.setString(1, execution.eventId)
                    ps.setString(2, execution.executionId)
                    ps.setString(3, execution.orderId)
                    ps.setString(4, execution.instrumentId)
                    ps.setString(5, execution.quantityUnits)
                    ps.setString(6, execution.executionPrice)
                    ps.setString(7, execution.currency)
                    ps.setString(8, execution.occurredAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        if (trades.isEmpty()) return
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.trades}(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                trades.forEach { trade ->
                    ps.setString(1, trade.eventId)
                    ps.setString(2, trade.tradeId)
                    ps.setString(3, trade.executionId)
                    ps.setString(4, trade.buyOrderId)
                    ps.setString(5, trade.sellOrderId)
                    ps.setString(6, trade.instrumentId)
                    ps.setString(7, trade.quantityUnits)
                    ps.setString(8, trade.price)
                    ps.setString(9, trade.currency)
                    ps.setString(10, trade.occurredAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun saveEvent(event: RuntimeEvent) {
        saveEvents(listOf(event))
    }

    override fun saveEvents(events: List<RuntimeEvent>) {
        if (events.isEmpty()) return
        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val startByTrace = mutableMapOf<String, Long>()
                events.groupBy { it.traceId }.forEach { (traceId, traceEvents) ->
                    conn.prepareStatement(
                        """
                        INSERT INTO ${names.runtimeTraceSequences} AS trace_sequence(trace_id, next_sequence)
                        VALUES (?, ?)
                        ON CONFLICT (trace_id) DO UPDATE SET next_sequence = trace_sequence.next_sequence + EXCLUDED.next_sequence
                        RETURNING next_sequence
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, traceId)
                        ps.setLong(2, traceEvents.size.toLong())
                        ps.executeQuery().use { rs ->
                            rs.next()
                            val sequenceHigh = rs.getLong("next_sequence")
                            startByTrace[traceId] = sequenceHigh - traceEvents.size + 1
                        }
                    }
                }

                val nextByTrace = startByTrace.toMutableMap()
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.runtimeEvents}(event_id, event_type, order_id, trace_id, causation_id, correlation_id, actor_id, producer, schema_version, sequence_number, payload_json, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """.trimIndent()
                ).use { ps ->
                    events.forEach { event ->
                        val sequence = nextByTrace.getValue(event.traceId)
                        nextByTrace[event.traceId] = sequence + 1
                        ps.setString(1, event.eventId)
                        ps.setString(2, event.eventType)
                        ps.setString(3, event.orderId)
                        ps.setString(4, event.traceId)
                        ps.setString(5, event.causationId)
                        ps.setString(6, event.correlationId)
                        ps.setString(7, event.actorId)
                        ps.setString(8, event.producer)
                        ps.setString(9, event.schemaVersion)
                        ps.setLong(10, sequence)
                        ps.setString(11, event.payloadJson)
                        ps.setString(12, event.occurredAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at
                FROM ${names.orders} WHERE order_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, orderId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return PersistedOrder(
                        orderId = rs.getString("order_id"),
                        engineOrderId = rs.getString("engine_order_id"),
                        instrumentId = rs.getString("instrument_id"),
                        participantId = rs.getString("participant_id"),
                        accountId = rs.getString("account_id"),
                        side = rs.getString("side"),
                        orderType = rs.getString("order_type"),
                        quantityUnits = rs.getString("quantity_units"),
                        limitPrice = rs.getString("limit_price"),
                        currency = rs.getString("currency"),
                        timeInForce = rs.getString("time_in_force"),
                        acceptedAt = rs.getString("accepted_at")
                    )
                }
            }
        }
    }

    override fun acceptedOrders(): List<PersistedOrder> = projectionQueryList(
        """
        SELECT order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at
        FROM ${names.orders} ORDER BY accepted_at
        """.trimIndent()
    ) {
        PersistedOrder(
            orderId = getString("order_id"),
            engineOrderId = getString("engine_order_id"),
            instrumentId = getString("instrument_id"),
            participantId = getString("participant_id"),
            accountId = getString("account_id"),
            side = getString("side"),
            orderType = getString("order_type"),
            quantityUnits = getString("quantity_units"),
            limitPrice = getString("limit_price"),
            currency = getString("currency"),
            timeInForce = getString("time_in_force"),
            acceptedAt = getString("accepted_at")
        )
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> = projectionQueryList(
        "SELECT event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at FROM ${names.executions} WHERE order_id = ? ORDER BY occurred_at",
        orderId
    ) {
        ExecutionCreated(
            eventId = getString("event_id"),
            executionId = getString("execution_id"),
            orderId = getString("order_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            executionPrice = getString("execution_price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at")
        )
    }

    override fun trades(): List<TradeCreated> = projectionQueryList(
        "SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM ${names.trades} ORDER BY occurred_at"
    ) {
        TradeCreated(
            eventId = getString("event_id"),
            tradeId = getString("trade_id"),
            executionId = getString("execution_id"),
            buyOrderId = getString("buy_order_id"),
            sellOrderId = getString("sell_order_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            price = getString("price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at")
        )
    }

    override fun recentTrades(limit: Int): List<TradeCreated> = projectionQueryList(
        "SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM ${names.trades} ORDER BY occurred_at DESC LIMIT ?",
        limit.coerceAtLeast(0).toString()
    ) {
        TradeCreated(
            eventId = getString("event_id"),
            tradeId = getString("trade_id"),
            executionId = getString("execution_id"),
            buyOrderId = getString("buy_order_id"),
            sellOrderId = getString("sell_order_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            price = getString("price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at")
        )
    }.asReversed()

    override fun tradesForOrder(orderId: String): List<TradeCreated> = projectionQueryList(
        """
        SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at
        FROM ${names.trades} WHERE buy_order_id = ? OR sell_order_id = ? ORDER BY occurred_at
        """.trimIndent(),
        orderId,
        orderId
    ) {
        TradeCreated(
            eventId = getString("event_id"),
            tradeId = getString("trade_id"),
            executionId = getString("execution_id"),
            buyOrderId = getString("buy_order_id"),
            sellOrderId = getString("sell_order_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            price = getString("price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at")
        )
    }

    override fun eventsForOrder(orderId: String): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM ${names.runtimeEvents} WHERE order_id = ? ORDER BY trace_id, sequence_number",
        orderId
    )

    override fun eventsForTrace(traceId: String): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM ${names.runtimeEvents} WHERE trace_id = ? ORDER BY sequence_number",
        traceId
    )

    override fun events(): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM ${names.runtimeEvents} ORDER BY trace_id, sequence_number"
    )

    override fun recentEvents(limit: Int): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM ${names.runtimeEvents} ORDER BY occurred_at DESC, event_id DESC LIMIT ?",
        limit.coerceAtLeast(0).toString()
    ).asReversed()

    private fun queryEvents(sql: String, vararg params: String): List<RuntimeEvent> = projectionQueryList(sql, *params) {
        RuntimeEvent(
            eventId = getString("event_id"),
            eventType = getString("event_type"),
            orderId = getString("order_id"),
            traceId = getString("trace_id"),
            causationId = getString("causation_id"),
            correlationId = getString("correlation_id"),
            producer = getString("producer"),
            schemaVersion = getString("schema_version"),
            sequenceNumber = getLong("sequence_number"),
            occurredAt = getString("occurred_at"),
            actorId = getString("actor_id"),
            payloadJson = getString("payload_json")
        )
    }

    private fun upsert(table: String, idColumn: String, idValue: String, secondValue: String) {
        val secondColumn = if (idColumn == "instrument_id") "symbol" else "name"
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO $table($idColumn, $secondColumn)
                VALUES (?, ?)
                ON CONFLICT ($idColumn) DO UPDATE SET $secondColumn = EXCLUDED.$secondColumn
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, idValue)
                ps.setString(2, secondValue)
                ps.executeUpdate()
            }
        }
    }

    private fun exists(table: String, idColumn: String, id: String): Boolean {
        connection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM $table WHERE $idColumn = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    private fun <T> queryList(sql: String, vararg params: String, map: java.sql.ResultSet.() -> T): List<T> {
        canonicalConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { idx, value -> ps.setString(idx + 1, value) }
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<T>()
                    while (rs.next()) rows.add(rs.map())
                    return rows
                }
            }
        }
    }

    private fun <T> projectionQueryList(sql: String, vararg params: String, map: java.sql.ResultSet.() -> T): List<T> {
        projectionConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { idx, value -> ps.setString(idx + 1, value) }
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<T>()
                    while (rs.next()) rows.add(rs.map())
                    return rows
                }
            }
        }
    }

    private fun PersistedOrder.toJsonObject(): String = jsonObject(
        "orderId" to orderId,
        "engineOrderId" to engineOrderId,
        "instrumentId" to instrumentId,
        "participantId" to participantId,
        "accountId" to accountId,
        "side" to side,
        "orderType" to orderType,
        "quantityUnits" to quantityUnits,
        "limitPrice" to limitPrice,
        "currency" to currency,
        "timeInForce" to timeInForce,
        "acceptedAt" to acceptedAt
    )

    private fun ExecutionCreated.toJsonObject(): String = jsonObject(
        "eventId" to eventId,
        "executionId" to executionId,
        "orderId" to orderId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "executionPrice" to executionPrice,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun TradeCreated.toJsonObject(): String = jsonObject(
        "eventId" to eventId,
        "tradeId" to tradeId,
        "executionId" to executionId,
        "buyOrderId" to buyOrderId,
        "sellOrderId" to sellOrderId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "price" to price,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun RuntimeEvent.toJsonObject(): String {
        val stringFields = listOf(
            "eventId" to eventId,
            "eventType" to eventType,
            "orderId" to orderId,
            "traceId" to traceId,
            "causationId" to causationId,
            "correlationId" to correlationId,
            "actorId" to actorId,
            "producer" to producer,
            "schemaVersion" to schemaVersion,
            "occurredAt" to occurredAt
        ).joinToString(",") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
        return "{$stringFields,\"payloadJson\":${payloadJson.ifBlank { "{}" }}}"
    }

    private fun PersistableSubmitOutcome.toJsonObject(): String {
        val accepted = result.accepted
        val rejected = result.rejected
        val resultType = if (accepted != null) "accepted" else "rejected"
        val fields = listOf(
            "commandId" to commandId,
            "resultType" to resultType,
            "eventId" to (accepted?.eventId ?: rejected?.eventId.orEmpty()),
            "orderId" to (accepted?.orderId ?: rejected?.orderId.orEmpty()),
            "engineOrderId" to accepted?.engineOrderId.orEmpty(),
            "code" to rejected?.code.orEmpty(),
            "reason" to rejected?.reason.orEmpty(),
            "occurredAt" to (accepted?.occurredAt ?: rejected?.occurredAt.orEmpty())
        ).joinToString(",") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
        return "{$fields," +
            "\"acceptedOrder\":${acceptedOrder?.toJsonObject() ?: "null"}," +
            "\"executions\":${result.executions.toJsonArray { it.toJsonObject() }}," +
            "\"trades\":${result.trades.toJsonArray { it.toJsonObject() }}," +
            "\"events\":${lifecycleEvents.toJsonArray { it.toJsonObject() }}}"
    }

    private fun CanonicalSubmitOutcome.toJsonObject(): String {
        val fields = listOf(
            "runId" to runId,
            "venueSessionId" to venueSessionId,
            "partitionId" to partitionId.toString(),
            "partitionSequence" to partitionSequence.toString(),
            "streamName" to streamName,
            "streamSequence" to streamSequence.toString(),
            "commandId" to commandId,
            "idempotencyKey" to idempotencyKey,
            "payloadHash" to payloadHash,
            "instrumentId" to instrumentId,
            "commandType" to commandType,
            "resultStatus" to resultStatus,
            "rejectCode" to rejectCode,
            "acceptedAt" to acceptedAt,
            "completedAt" to completedAt,
            "engineShardId" to engineShardId
        ).joinToString(",") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
        return "{$fields," +
            "\"resultPayload\":${outcome.toJsonObject()}," +
            "\"events\":${outcome.lifecycleEvents.toJsonArray { it.toJsonObject() }}}"
    }

    private fun VenueEventBatchFact.toJsonObject(): String {
        val fields = listOf(
            "batchId" to batchId,
            "shardId" to shardId,
            "partition" to partition.toString(),
            "commandStream" to commandStream,
            "eventStream" to eventStream,
            "firstSequence" to firstSequence.toString(),
            "lastSequence" to lastSequence.toString(),
            "commandCount" to commandCount.toString(),
            "createdAt" to createdAt,
            "payloadChecksum" to payloadChecksum,
            "payloadFormat" to payloadFormat,
            "payloadVersion" to payloadVersion
        ).joinToString(",") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
        return "{$fields,\"outcomes\":${outcomes.toJsonArray { it.toJsonObject() }}}"
    }

    private fun VenueCommandOutcomeFact.toJsonObject(): String {
        val fields = listOf(
            "commandId" to commandId,
            "commandType" to commandType,
            "streamSequence" to streamSequence.toString(),
            "deliveredCount" to deliveredCount.toString(),
            "payloadHash" to payloadHash,
            "instrumentId" to instrumentId,
            "orderId" to orderId,
            "status" to resultStatus,
            "rejectCode" to rejectCode
        ).joinToString(",") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
        return "{$fields,\"result\":${resultPayloadJson.ifBlank { "{}" }}}"
    }

    private fun <T> List<T>.toJsonArray(toObject: (T) -> String): String {
        if (isEmpty()) return "[]"
        return joinToString(prefix = "[", postfix = "]") { toObject(it) }
    }

    private fun jsonObject(vararg fields: Pair<String, String>): String {
        return fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }

    private fun projectionStoreSeparated(): Boolean = projectionDataSource !== dataSource

    private fun connection(): Connection = canonicalConnection()

    private fun canonicalConnection(): Connection = dataSource.connection

    private fun projectionConnection(): Connection = projectionDataSource.connection
}
