package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.Account
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.IntradayBar
import com.reef.platform.domain.NonLifecycleRejectCodes
import com.reef.platform.domain.OwnExecutionView
import com.reef.platform.domain.OwnOrderView
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PublicTradeTapeEntry
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.VenueSessionPostTradeProfile
import com.reef.platform.infrastructure.config.RuntimeEnv
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class PostgresRuntimePersistence(
    private val dataSource: DataSource,
    private val names: PostgresRuntimeSqlNames = PostgresRuntimeSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val projectionDataSource: DataSource = dataSource,
    private val envLookup: (String) -> String? = { key -> System.getenv(key) }
) : RuntimePersistence {
    private val intradayBarIntervalText = mapOf(
        "1m" to "1 minute",
        "5m" to "5 minutes",
        "15m" to "15 minutes",
        "1h" to "1 hour"
    )

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

    private data class CommandProjectionCandidate(
        val partitionId: Int,
        val partitionSequence: Long,
        val outcome: CanonicalCommandOutcome,
        val commandPayloadJson: String = "{}",
        val partitionRow: Int = 0
    )

    private companion object {
        const val CanonicalCommandOutcomeProjectionMaxBatchSize = 5000
        const val StreamSequencePartitionShift = 48
        val projectorRowsBeforeWatermarkFailureInjected = AtomicBoolean(false)
    }

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
                    CREATE SCHEMA IF NOT EXISTS ${names.adminSchemaName}
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
                    CREATE TABLE IF NOT EXISTS ${names.referenceScenarioRuns} (
                      scenario_run_id TEXT PRIMARY KEY,
                      post_trade_profile_id TEXT NOT NULL,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.referenceVenueSessions} (
                      venue_session_id TEXT PRIMARY KEY,
                      post_trade_profile_id TEXT NOT NULL,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
                    CREATE TABLE IF NOT EXISTS ${names.adminPostTradeProfiles} (
                      profile_id TEXT PRIMARY KEY,
                      mode TEXT NOT NULL,
                      settlement_cycle TEXT NOT NULL,
                      netting_mode TEXT NOT NULL,
                      ledger_posting_mode TEXT NOT NULL,
                      policy_version INTEGER NOT NULL CHECK (policy_version > 0),
                      active BOOLEAN NOT NULL DEFAULT false,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_post_trade_profiles_active_one
                    ON ${names.adminPostTradeProfiles}(active)
                    WHERE active
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
                      accepted_at TEXT NOT NULL,
                      quantity_units_num NUMERIC,
                      limit_price_num NUMERIC,
                      accepted_at_ts TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.orders}
                    ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC,
                    ADD COLUMN IF NOT EXISTS limit_price_num NUMERIC,
                    ADD COLUMN IF NOT EXISTS accepted_at_ts TIMESTAMPTZ
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_orders_participant_instrument_accepted
                    ON ${names.orders}(participant_id, instrument_id, accepted_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.orders}
                    ADD COLUMN IF NOT EXISTS client_order_id TEXT NOT NULL DEFAULT '',
                    ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '',
                    ADD COLUMN IF NOT EXISTS venue_session_id TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_orders_participant_client_order_id
                    ON ${names.orders}(participant_id, client_order_id)
                    WHERE client_order_id <> ''
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_orders_participant_instrument_accepted_typed
                    ON ${names.orders}(participant_id, instrument_id, accepted_at_ts)
                    WHERE accepted_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_orders_participant_client_order_accepted_typed
                    ON ${names.orders}(participant_id, client_order_id, accepted_at_ts DESC)
                    WHERE client_order_id <> ''
                      AND accepted_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_orders_accepted_typed
                    ON ${names.orders}(accepted_at_ts, order_id)
                    WHERE accepted_at_ts IS NOT NULL
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
                      occurred_at TEXT NOT NULL,
                      event_id_uuid UUID,
                      quantity_units_num NUMERIC,
                      execution_price_num NUMERIC,
                      occurred_at_ts TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.executions}
                    ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
                    ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC,
                    ADD COLUMN IF NOT EXISTS execution_price_num NUMERIC,
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ
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
                      occurred_at TEXT NOT NULL,
                      event_id_uuid UUID,
                      quantity_units_num NUMERIC,
                      price_num NUMERIC,
                      occurred_at_ts TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.trades}
                    ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
                    ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC,
                    ADD COLUMN IF NOT EXISTS price_num NUMERIC,
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.trades}
                    ADD COLUMN IF NOT EXISTS sequence BIGINT GENERATED ALWAYS AS IDENTITY
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_instrument_sequence
                    ON ${names.trades}(instrument_id, sequence DESC)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.tradesArchive} (
                      event_id TEXT NOT NULL,
                      trade_id TEXT NOT NULL,
                      execution_id TEXT NOT NULL,
                      buy_order_id TEXT NOT NULL,
                      sell_order_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      quantity_units TEXT NOT NULL,
                      price TEXT NOT NULL,
                      currency TEXT NOT NULL,
                      occurred_at TEXT NOT NULL,
                      event_id_uuid UUID,
                      quantity_units_num NUMERIC,
                      price_num NUMERIC,
                      occurred_at_ts TIMESTAMPTZ NOT NULL,
                      sequence BIGINT,
                      archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY (occurred_at_ts, event_id)
                    ) PARTITION BY RANGE (occurred_at_ts)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.tradesArchiveDefault}
                    PARTITION OF ${names.tradesArchive} DEFAULT
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_archive_instrument_occurred
                    ON ${names.tradesArchive}(instrument_id, occurred_at_ts, event_id)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_archive_sequence
                    ON ${names.tradesArchive}(instrument_id, sequence DESC)
                    WHERE sequence IS NOT NULL
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
                      occurred_at TEXT NOT NULL,
                      event_id_uuid UUID,
                      occurred_at_ts TIMESTAMPTZ
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
                    ALTER TABLE ${names.runtimeEvents}
                    ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runtimeEventsArchive} (
                      event_id TEXT NOT NULL,
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
                      occurred_at TEXT NOT NULL,
                      event_id_uuid UUID,
                      occurred_at_ts TIMESTAMPTZ NOT NULL,
                      archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY (occurred_at_ts, event_id)
                    ) PARTITION BY RANGE (occurred_at_ts)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runtimeEventsArchiveDefault}
                    PARTITION OF ${names.runtimeEventsArchive} DEFAULT
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_archive_trace_seq
                    ON ${names.runtimeEventsArchive}(trace_id, sequence_number)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_archive_order_occurred
                    ON ${names.runtimeEventsArchive}(order_id, occurred_at_ts, event_id)
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
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_occurred_typed
                    ON ${names.runtimeEvents}(occurred_at_ts DESC, event_id_uuid DESC)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_order_occurred_typed
                    ON ${names.runtimeEvents}(order_id, occurred_at_ts DESC, event_id_uuid DESC)
                    WHERE occurred_at_ts IS NOT NULL
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
                    CREATE INDEX IF NOT EXISTS idx_executions_order_occurred_typed
                    ON ${names.executions}(order_id, occurred_at_ts, event_id_uuid)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_buy_order_occurred_typed
                    ON ${names.trades}(buy_order_id, occurred_at_ts, event_id_uuid)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_sell_order_occurred_typed
                    ON ${names.trades}(sell_order_id, occurred_at_ts, event_id_uuid)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_trades_instrument_occurred_typed
                    ON ${names.trades}(instrument_id, occurred_at_ts, sequence)
                    INCLUDE (price_num, quantity_units_num)
                    WHERE occurred_at_ts IS NOT NULL
                      AND price_num IS NOT NULL
                      AND quantity_units_num IS NOT NULL
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
                      occurred_at TEXT NOT NULL,
                      event_id_uuid UUID,
                      occurred_at_ts TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.submitResults}
                    ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_submit_results_occurred_typed
                    ON ${names.submitResults}(occurred_at_ts DESC, command_id)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_submit_results_event_uuid
                    ON ${names.submitResults}(event_id_uuid)
                    WHERE event_id_uuid IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.orderLifecycleState} (
                      order_id TEXT PRIMARY KEY,
                      engine_order_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      side TEXT NOT NULL,
                      order_type TEXT NOT NULL,
                      original_quantity_units TEXT NOT NULL,
                      remaining_quantity_units TEXT NOT NULL,
                      filled_quantity_units TEXT NOT NULL,
                      limit_price TEXT NOT NULL,
                      original_quantity_units_num NUMERIC,
                      remaining_quantity_units_num NUMERIC,
                      filled_quantity_units_num NUMERIC,
                      limit_price_num NUMERIC,
                      currency TEXT NOT NULL,
                      time_in_force TEXT NOT NULL,
                      status TEXT NOT NULL,
                      accepted_at TEXT NOT NULL,
                      last_event_at TEXT NOT NULL,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.marketDataSnapshots} (
                      projection_name TEXT NOT NULL,
                      source_projection_name TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      best_bid_price TEXT NOT NULL DEFAULT '',
                      best_bid_quantity TEXT NOT NULL DEFAULT '',
                      best_ask_price TEXT NOT NULL DEFAULT '',
                      best_ask_quantity TEXT NOT NULL DEFAULT '',
                      best_bid_price_num NUMERIC,
                      best_bid_quantity_num NUMERIC,
                      best_ask_price_num NUMERIC,
                      best_ask_quantity_num NUMERIC,
                      currency TEXT NOT NULL DEFAULT '',
                      last_partition_seq BIGINT NOT NULL DEFAULT 0,
                      lag BIGINT NOT NULL DEFAULT 0,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY (projection_name, instrument_id)
                    )
                    """.trimIndent()
                )
                stmt.execute("ALTER TABLE ${names.orderLifecycleState} ADD COLUMN IF NOT EXISTS original_quantity_units_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.orderLifecycleState} ADD COLUMN IF NOT EXISTS remaining_quantity_units_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.orderLifecycleState} ADD COLUMN IF NOT EXISTS filled_quantity_units_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.orderLifecycleState} ADD COLUMN IF NOT EXISTS limit_price_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.marketDataSnapshots} ADD COLUMN IF NOT EXISTS best_bid_price_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.marketDataSnapshots} ADD COLUMN IF NOT EXISTS best_bid_quantity_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.marketDataSnapshots} ADD COLUMN IF NOT EXISTS best_ask_price_num NUMERIC")
                stmt.execute("ALTER TABLE ${names.marketDataSnapshots} ADD COLUMN IF NOT EXISTS best_ask_quantity_num NUMERIC")
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
                      accepted_at_ts TIMESTAMPTZ,
                      completed_at_ts TIMESTAMPTZ,
                      UNIQUE (stream_name, stream_seq)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.canonicalCommandResults}
                    ADD COLUMN IF NOT EXISTS accepted_at_ts TIMESTAMPTZ,
                    ADD COLUMN IF NOT EXISTS completed_at_ts TIMESTAMPTZ
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
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_results_completed_typed
                    ON ${names.canonicalCommandResults}(completed_at_ts, partition_id, partition_seq)
                    WHERE completed_at_ts IS NOT NULL
                    """.trimIndent()
                )
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
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      emitted_at_ts TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.canonicalVenueEvents}
                    ADD COLUMN IF NOT EXISTS emitted_at_ts TIMESTAMPTZ
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
                    CREATE INDEX IF NOT EXISTS idx_canonical_venue_events_emitted_typed
                    ON ${names.canonicalVenueEvents}(emitted_at_ts, partition_id, event_seq)
                    WHERE emitted_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalVenueEventBatches} (
                      batch_id TEXT NOT NULL,
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
                      created_at_ts TIMESTAMPTZ,
                      UNIQUE (event_stream, batch_id),
                      UNIQUE (event_stream, partition_id, first_sequence, last_sequence)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.canonicalVenueEventBatches}
                    ADD COLUMN IF NOT EXISTS created_at_ts TIMESTAMPTZ
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
                    CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_created_typed
                    ON ${names.canonicalVenueEventBatches}(created_at_ts, event_stream, batch_id)
                    WHERE created_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalVenueEventBatchesArchive} (
                      batch_id TEXT NOT NULL,
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
                      materialized_at TIMESTAMPTZ NOT NULL,
                      created_at_ts TIMESTAMPTZ,
                      archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY (materialized_at, event_stream, batch_id)
                    ) PARTITION BY RANGE (materialized_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalVenueEventBatchesArchiveDefault}
                    PARTITION OF ${names.canonicalVenueEventBatchesArchive} DEFAULT
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_archive_batch
                    ON ${names.canonicalVenueEventBatchesArchive}(event_stream, batch_id, materialized_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_archive_partition_seq
                    ON ${names.canonicalVenueEventBatchesArchive}(partition_id, first_sequence, last_sequence)
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
                      occurred_at_ts TIMESTAMPTZ,
                      UNIQUE (event_stream, batch_id, stream_sequence)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.canonicalCommandOutcomes}
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_batch_seq
                    ON ${names.canonicalCommandOutcomes}(event_stream, batch_id, stream_sequence)
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
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_occurred_typed
                    ON ${names.canonicalCommandOutcomes}(occurred_at_ts, partition_id, stream_sequence)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalCommandOutcomesArchive} (
                      command_id TEXT NOT NULL,
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
                      materialized_at TIMESTAMPTZ NOT NULL,
                      occurred_at_ts TIMESTAMPTZ,
                      archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY (materialized_at, command_id)
                    ) PARTITION BY RANGE (materialized_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.canonicalCommandOutcomesArchiveDefault}
                    PARTITION OF ${names.canonicalCommandOutcomesArchive} DEFAULT
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_archive_command
                    ON ${names.canonicalCommandOutcomesArchive}(command_id, materialized_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_archive_partition_seq
                    ON ${names.canonicalCommandOutcomesArchive}(partition_id, stream_sequence, materialized_at)
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
                       WHERE event_stream = COALESCE(p_batch->>'eventStream', '')
                         AND batch_id = v_batch_id;

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

                      INSERT INTO ${names.orderLifecycleDirty}(order_id)
                      SELECT DISTINCT order_id FROM (
                        SELECT p_result_order_id AS order_id
                        WHERE COALESCE(p_result_order_id, '') <> ''
                        UNION ALL
                        SELECT event->>'orderId' FROM jsonb_array_elements(COALESCE(p_events, '[]'::jsonb)) AS event
                        UNION ALL
                        SELECT trade->>'buyOrderId' FROM jsonb_array_elements(COALESCE(p_trades, '[]'::jsonb)) AS trade
                        UNION ALL
                        SELECT trade->>'sellOrderId' FROM jsonb_array_elements(COALESCE(p_trades, '[]'::jsonb)) AS trade
                      ) dirty_ids
                      WHERE COALESCE(order_id, '') <> ''
                      ON CONFLICT (order_id) DO UPDATE SET dirtied_at = now();

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
                      effective_batch_size INTEGER := 0;
                      projected_count BIGINT := 0;
                    BEGIN
                      IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
                        RETURN 0;
                      END IF;
                      effective_batch_size := LEAST(p_batch_size, ${CanonicalCommandOutcomeProjectionMaxBatchSize});

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
                          CEIL(effective_batch_size::NUMERIC / GREATEST((SELECT COUNT(*) FROM selected_partitions), 1))::INTEGER
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
                stmt.execute(
                    """
                    CREATE OR REPLACE FUNCTION ${names.projectCanonicalCommandOutcomesFunction}(
                      p_projection_name TEXT,
                      p_batch_size INTEGER,
                      p_partitions INTEGER[] DEFAULT NULL,
                      p_include_fills BOOLEAN DEFAULT TRUE,
                      p_event_stream TEXT DEFAULT NULL
                    )
                    RETURNS BIGINT
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                      projected_count BIGINT := 0;
                      effective_batch_size INTEGER := 0;
                    BEGIN
                      IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
                        RETURN 0;
                      END IF;

                      effective_batch_size := LEAST(p_batch_size, ${CanonicalCommandOutcomeProjectionMaxBatchSize});

                      WITH selected_partitions AS (
                        SELECT DISTINCT partition_id
                        FROM (
                          SELECT unnest(p_partitions) AS partition_id
                          WHERE p_partitions IS NOT NULL AND cardinality(p_partitions) > 0
                          UNION ALL
                          SELECT DISTINCT partition_id
                          FROM ${names.canonicalCommandOutcomes} canonical_partitions
                          WHERE (p_partitions IS NULL OR cardinality(p_partitions) = 0)
                            AND (p_event_stream IS NULL OR canonical_partitions.event_stream = p_event_stream)
                        ) partitions
                      ),
                      partition_budget AS (
                        SELECT GREATEST(
                          1,
                          CEIL(effective_batch_size::NUMERIC / GREATEST((SELECT COUNT(*) FROM selected_partitions), 1))::INTEGER
                        ) AS per_partition_limit
                      ),
                      ranked AS (
                        SELECT
                          canonical.partition_id,
                          canonical.stream_sequence,
                          canonical.command_id,
                          canonical.command_type,
                          canonical.order_id,
                          canonical.result_status,
                          canonical.reject_code,
                          canonical.result_payload,
                          COALESCE(canonical.result_payload->'acceptedOrder', payloads.payload_json) AS order_payload,
                          COALESCE(watermark.last_partition_seq, 0) AS previous_partition_seq,
                          row_number() OVER (
                            PARTITION BY canonical.partition_id
                            ORDER BY canonical.stream_sequence
                          ) AS partition_row
                        FROM ${names.canonicalCommandOutcomes} canonical
                        JOIN selected_partitions selected
                          ON selected.partition_id = canonical.partition_id
                        LEFT JOIN ${names.projectionWatermarks} watermark
                          ON watermark.projection_name = p_projection_name
                         AND watermark.partition_id = canonical.partition_id
                        LEFT JOIN command_log.command_payloads payloads
                          ON payloads.command_id = canonical.command_id
                        WHERE canonical.command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                          AND canonical.stream_sequence > COALESCE(watermark.last_partition_seq, 0)
                          AND (p_event_stream IS NULL OR canonical.event_stream = p_event_stream)
                      ),
                      eligible AS (
                        SELECT *
                        FROM ranked
                        CROSS JOIN partition_budget
                        WHERE partition_row <= partition_budget.per_partition_limit
                          AND (
                            NOT (
                              partition_id = 0
                              OR (stream_sequence / 281474976710656)::INTEGER = partition_id
                            )
                            OR stream_sequence = CASE
                              WHEN previous_partition_seq > 0 THEN previous_partition_seq + partition_row
                              ELSE (partition_id::BIGINT * 281474976710656) + partition_row
                            END
                          )
                        ORDER BY partition_row, partition_id, stream_sequence
                        LIMIT effective_batch_size
                      ),
                      shaped AS (
                        SELECT
                          partition_id,
                          stream_sequence,
                          jsonb_build_object(
                            'commandId', command_id,
                            'resultType', result_status,
                            'eventId', COALESCE(NULLIF(result_payload #>> '{accepted,eventId}', ''), NULLIF(result_payload #>> '{rejected,eventId}', ''), 'evt-' || command_id),
                            'orderId', order_id,
                            'engineOrderId', COALESCE(result_payload #>> '{accepted,engineOrderId}', ''),
                            'code', COALESCE(NULLIF(reject_code, ''), result_payload #>> '{rejected,code}', ''),
                            'reason', COALESCE(result_payload #>> '{rejected,reason}', ''),
                            'occurredAt', COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''), ''),
                            'acceptedOrder', CASE
                              WHEN command_type = 'SubmitOrder'
                               AND order_payload IS NOT NULL
                               AND COALESCE(order_payload->>'instrumentId', '') <> ''
                               AND COALESCE(order_payload->>'participantId', '') <> ''
                               AND COALESCE(order_payload->>'accountId', '') <> ''
                               AND (
                                 result_status <> 'rejected'
                                 OR COALESCE(NULLIF(reject_code, ''), result_payload #>> '{rejected,code}', '') NOT IN ('AUTHORIZATION_ERROR', 'REFERENCE_DATA_ERROR')
                               )
                              THEN jsonb_build_object(
                                'orderId', order_id,
                                'engineOrderId', CASE WHEN result_status = 'rejected' THEN '' ELSE COALESCE(result_payload #>> '{accepted,engineOrderId}', order_payload->>'engineOrderId', '') END,
                                'instrumentId', COALESCE(order_payload->>'instrumentId', ''),
                                'participantId', COALESCE(order_payload->>'participantId', ''),
                                'accountId', COALESCE(order_payload->>'accountId', ''),
                                'side', COALESCE(order_payload->>'side', ''),
                                'orderType', COALESCE(order_payload->>'orderType', ''),
                                'quantityUnits', COALESCE(order_payload->>'quantityUnits', ''),
                                'limitPrice', COALESCE(order_payload->>'limitPrice', ''),
                                'currency', COALESCE(order_payload->>'currency', ''),
                                'timeInForce', COALESCE(order_payload->>'timeInForce', ''),
                                'acceptedAt', COALESCE(
                                  NULLIF(result_payload #>> '{accepted,occurredAt}', ''),
                                  NULLIF(result_payload #>> '{rejected,occurredAt}', ''),
                                  NULLIF(order_payload->>'acceptedAt', ''),
                                  ''
                                )
                              )
                              ELSE NULL
                            END,
                            'executions', CASE WHEN p_include_fills THEN COALESCE(result_payload->'executions', '[]'::jsonb) ELSE '[]'::jsonb END,
                            'trades', CASE WHEN p_include_fills THEN COALESCE(result_payload->'trades', '[]'::jsonb) ELSE '[]'::jsonb END,
                            'events', jsonb_build_array(
                              jsonb_build_object(
                                'eventId', COALESCE(NULLIF(result_payload #>> '{accepted,eventId}', ''), NULLIF(result_payload #>> '{rejected,eventId}', ''), 'evt-' || command_id),
                                'eventType', CASE
                                  WHEN result_status = 'rejected' THEN 'OrderRejected'
                                  WHEN command_type = 'CancelOrder' THEN 'OrderCancelled'
                                  WHEN command_type = 'ModifyOrder' THEN 'OrderModified'
                                  ELSE 'OrderAccepted'
                                END,
                                'orderId', order_id,
                                'traceId', command_id,
                                'causationId', command_id,
                                'correlationId', command_id,
                                'actorId', '',
                                'producer', 'venue-event-batch-projector',
                                'schemaVersion', 'v1',
                                'occurredAt', COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''), ''),
                                'payloadJson', result_payload
                              )
                            )
                          ) AS result_payload
                        FROM eligible
                      ),
                      projected AS (
                        SELECT ${names.persistSubmitOutcomesFunction}(
                          COALESCE(
                            jsonb_agg(result_payload ORDER BY stream_sequence, partition_id),
                            '[]'::jsonb
                          )
                        ) AS count
                        FROM shaped
                      ),
                      partition_max AS (
                        SELECT partition_id, MAX(stream_sequence) AS last_partition_seq
                        FROM shaped
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

    override fun savePostTradeProfile(profile: PostTradeProfile) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                if (profile.active) {
                    conn.prepareStatement("UPDATE ${names.adminPostTradeProfiles} SET active = false WHERE active").use { it.executeUpdate() }
                }
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.adminPostTradeProfiles}(
                      profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode, policy_version, active, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (profile_id) DO UPDATE SET
                      mode = EXCLUDED.mode,
                      settlement_cycle = EXCLUDED.settlement_cycle,
                      netting_mode = EXCLUDED.netting_mode,
                      ledger_posting_mode = EXCLUDED.ledger_posting_mode,
                      policy_version = EXCLUDED.policy_version,
                      active = EXCLUDED.active,
                      updated_at = now()
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, profile.profileId)
                    ps.setString(2, profile.mode)
                    ps.setString(3, profile.settlementCycle)
                    ps.setString(4, profile.nettingMode)
                    ps.setString(5, profile.ledgerPostingMode)
                    ps.setInt(6, profile.policyVersion)
                    ps.setBoolean(7, profile.active)
                    ps.executeUpdate()
                }
                if (!profile.active && !hasActivePostTradeProfile(conn)) {
                    conn.prepareStatement(
                        "UPDATE ${names.adminPostTradeProfiles} SET active = true WHERE profile_id = ?"
                    ).use { ps ->
                        ps.setString(1, profile.profileId)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun postTradeProfiles(): List<PostTradeProfile> {
        return queryList(
            """
            SELECT profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode, policy_version, active
            FROM ${names.adminPostTradeProfiles}
            ORDER BY profile_id
            """.trimIndent()
        ) {
            toPostTradeProfile()
        }
    }

    override fun activePostTradeProfile(): PostTradeProfile {
        return postTradeProfiles().firstOrNull { it.active }
            ?: throw IllegalArgumentException("no active post-trade profile")
    }

    override fun activatePostTradeProfile(profileId: String): PostTradeProfile {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val profile = queryPostTradeProfile(conn, profileId)
                    ?: throw IllegalArgumentException("unknown post-trade profile '$profileId'")
                conn.prepareStatement("UPDATE ${names.adminPostTradeProfiles} SET active = false WHERE active").use { it.executeUpdate() }
                conn.prepareStatement(
                    "UPDATE ${names.adminPostTradeProfiles} SET active = true, updated_at = now() WHERE profile_id = ?"
                ).use { ps ->
                    ps.setString(1, profileId)
                    ps.executeUpdate()
                }
                conn.commit()
                return profile.copy(active = true)
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun saveScenarioRunPostTradeProfile(config: ScenarioRunPostTradeProfile) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.referenceScenarioRuns}(scenario_run_id, post_trade_profile_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (scenario_run_id) DO UPDATE SET
                  post_trade_profile_id = EXCLUDED.post_trade_profile_id,
                  updated_at = now()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, config.scenarioRunId)
                ps.setString(2, config.postTradeProfileId)
                ps.executeUpdate()
            }
        }
    }

    override fun scenarioRunPostTradeProfileId(scenarioRunId: String): String? {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT post_trade_profile_id FROM ${names.referenceScenarioRuns} WHERE scenario_run_id = ?"
            ).use { ps ->
                ps.setString(1, scenarioRunId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("post_trade_profile_id") else null
                }
            }
        }
    }

    override fun scenarioRunPostTradeProfiles(): List<ScenarioRunPostTradeProfile> {
        return queryList(
            """
            SELECT scenario_run_id, post_trade_profile_id
            FROM ${names.referenceScenarioRuns}
            ORDER BY scenario_run_id
            """.trimIndent()
        ) {
            ScenarioRunPostTradeProfile(
                scenarioRunId = getString("scenario_run_id"),
                postTradeProfileId = getString("post_trade_profile_id")
            )
        }
    }

    override fun saveVenueSessionPostTradeProfile(config: VenueSessionPostTradeProfile) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.referenceVenueSessions}(venue_session_id, post_trade_profile_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (venue_session_id) DO UPDATE SET
                  post_trade_profile_id = EXCLUDED.post_trade_profile_id,
                  updated_at = now()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, config.venueSessionId)
                ps.setString(2, config.postTradeProfileId)
                ps.executeUpdate()
            }
        }
    }

    override fun venueSessionPostTradeProfileId(venueSessionId: String): String? {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT post_trade_profile_id FROM ${names.referenceVenueSessions} WHERE venue_session_id = ?"
            ).use { ps ->
                ps.setString(1, venueSessionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("post_trade_profile_id") else null
                }
            }
        }
    }

    override fun venueSessionPostTradeProfiles(): List<VenueSessionPostTradeProfile> {
        return queryList(
            """
            SELECT venue_session_id, post_trade_profile_id
            FROM ${names.referenceVenueSessions}
            ORDER BY venue_session_id
            """.trimIndent()
        ) {
            VenueSessionPostTradeProfile(
                venueSessionId = getString("venue_session_id"),
                postTradeProfileId = getString("post_trade_profile_id")
            )
        }
    }

    private fun queryPostTradeProfile(conn: Connection, profileId: String): PostTradeProfile? {
        conn.prepareStatement(
            """
            SELECT profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode, policy_version, active
            FROM ${names.adminPostTradeProfiles}
            WHERE profile_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, profileId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toPostTradeProfile() else null
            }
        }
    }

    private fun hasActivePostTradeProfile(conn: Connection): Boolean {
        conn.prepareStatement("SELECT 1 FROM ${names.adminPostTradeProfiles} WHERE active LIMIT 1").use { ps ->
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun ResultSet.toPostTradeProfile(): PostTradeProfile {
        return PostTradeProfile(
            profileId = getString("profile_id"),
            mode = getString("mode"),
            settlementCycle = getString("settlement_cycle"),
            nettingMode = getString("netting_mode"),
            ledgerPostingMode = getString("ledger_posting_mode"),
            policyVersion = getInt("policy_version"),
            active = getBoolean("active")
        )
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

    override fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String
    ): Long {
        if (batchSize <= 0) return 0
        if (projectionStoreSeparated()) {
            return projectCanonicalCommandOutcomesAcrossStores(projectionName, batchSize, partitions, includeFills, eventStream)
        }
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectCanonicalCommandOutcomesFunction}(?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setInt(2, batchSize)
                ps.setArray(3, conn.createArrayOf("integer", partitions.toTypedArray()))
                ps.setBoolean(4, includeFills)
                ps.setString(5, eventStream.trim().ifBlank { null })
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
                maybeFailAfterProjectorRowsBeforeWatermark(conn)
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

    private fun projectCanonicalCommandOutcomesAcrossStores(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String
    ): Long {
        val effectiveBatchSize = batchSize.coerceAtMost(CanonicalCommandOutcomeProjectionMaxBatchSize)
        val scopedEventStream = eventStream.trim()
        val ownedPartitions = ownedCommandProjectionPartitions(partitions, scopedEventStream)
        if (ownedPartitions.isEmpty()) return 0
        val watermarks = projectionWatermarkMap(projectionName, ownedPartitions)
        val perPartitionLimit = ((effectiveBatchSize + ownedPartitions.size - 1) / ownedPartitions.size).coerceAtLeast(1)
        val includeCommandPayload = commandPayloadSideTableAvailable()
        val candidates = ownedPartitions
            .flatMap { partitionId ->
                contiguousCommandProjectionCandidates(
                    partitionId = partitionId,
                    afterPartitionSequence = watermarks[partitionId] ?: 0L,
                    candidates = canonicalCommandProjectionCandidates(
                        partitionId = partitionId,
                        afterPartitionSequence = watermarks[partitionId] ?: 0L,
                        limit = perPartitionLimit,
                        includeCommandPayload = includeCommandPayload,
                        eventStream = scopedEventStream
                    )
                ).mapIndexed { index, candidate -> candidate.copy(partitionRow = index + 1) }
            }
            .sortedWith(
                compareBy<CommandProjectionCandidate> { it.partitionRow }
                    .thenBy { it.partitionId }
                    .thenBy { it.partitionSequence }
            )
            .take(effectiveBatchSize)
        if (candidates.isEmpty()) return 0

        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val payloadJson = candidates.toJsonArray { it.outcome.toPersistableSubmitOutcome(it.commandPayloadJson, includeFills).toJsonObject() }
                persistSubmitOutcomePayloads(conn, payloadJson, candidates.size)
                maybeFailAfterProjectorRowsBeforeWatermark(conn)
                updateProjectionWatermarks(
                    conn,
                    projectionName,
                    candidates.map {
                        ProjectionCandidate(
                            partitionId = it.partitionId,
                            partitionSequence = it.partitionSequence,
                            resultPayload = ""
                        )
                    }
                )
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

    private fun maybeFailAfterProjectorRowsBeforeWatermark(conn: Connection) {
        if (!RuntimeEnv.bool("STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE", false, envLookup)) return
        val internalMode = RuntimeEnv.string("PLATFORM_INTERNAL_HTTP_MODE", "local", envLookup)
        require(internalMode == "enabled") {
            "STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE requires PLATFORM_INTERNAL_HTTP_MODE=enabled"
        }
        if (!projectorRowsBeforeWatermarkFailureInjected.compareAndSet(false, true)) return
        conn.commit()
        error("injected projector failure after read-model rows before watermark")
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

    private fun ownedCommandProjectionPartitions(partitions: List<Int>, eventStream: String = ""): List<Int> {
        if (partitions.isNotEmpty()) return partitions.distinct().sorted()
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT partition_id
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                  AND (? = '' OR event_stream = ?)
                ORDER BY partition_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, eventStream)
                ps.setString(2, eventStream)
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

    private fun canonicalCommandProjectionCandidates(
        partitionId: Int,
        afterPartitionSequence: Long,
        limit: Int,
        includeCommandPayload: Boolean,
        eventStream: String = ""
    ): List<CommandProjectionCandidate> {
        canonicalConnection().use { conn ->
            val payloadSelect = if (includeCommandPayload) {
                "COALESCE(payloads.payload_json::TEXT, '{}') AS command_payload"
            } else {
                "'{}' AS command_payload"
            }
            val payloadJoin = if (includeCommandPayload) {
                """
                LEFT JOIN command_log.command_payloads payloads
                  ON payloads.command_id = canonical.command_id
                """.trimIndent()
            } else {
                ""
            }
            conn.prepareStatement(
                """
                SELECT
                  canonical.command_id,
                  canonical.batch_id,
                  canonical.shard_id,
                  canonical.partition_id,
                  canonical.command_stream,
                  canonical.event_stream,
                  canonical.stream_sequence,
                  canonical.delivered_count,
                  canonical.command_type,
                  canonical.payload_hash,
                  canonical.instrument_id,
                  canonical.order_id,
                  canonical.result_status,
                  canonical.reject_code,
                  canonical.result_payload::TEXT AS result_payload,
                  $payloadSelect
                FROM ${names.canonicalCommandOutcomes} canonical
                $payloadJoin
                WHERE canonical.partition_id = ?
                  AND canonical.stream_sequence > ?
                  AND canonical.command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                  AND (? = '' OR canonical.event_stream = ?)
                ORDER BY stream_sequence
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, partitionId)
                ps.setLong(2, afterPartitionSequence)
                ps.setString(3, eventStream)
                ps.setString(4, eventStream)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<CommandProjectionCandidate>()
                    while (rs.next()) {
                        val outcome = CanonicalCommandOutcome(
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
                        out.add(
                            CommandProjectionCandidate(
                                partitionId = outcome.partition,
                                partitionSequence = outcome.streamSequence,
                                outcome = outcome,
                                commandPayloadJson = rs.getString("command_payload")
                            )
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun contiguousCommandProjectionCandidates(
        partitionId: Int,
        afterPartitionSequence: Long,
        candidates: List<CommandProjectionCandidate>
    ): List<CommandProjectionCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val firstSequence = candidates.first().partitionSequence
        if (!usesEncodedPartitionSequence(partitionId, firstSequence)) {
            return candidates
        }
        var expected = if (afterPartitionSequence > 0L) {
            afterPartitionSequence + 1L
        } else {
            encodedPartitionBase(partitionId) + 1L
        }
        val out = mutableListOf<CommandProjectionCandidate>()
        for (candidate in candidates.sortedBy { it.partitionSequence }) {
            when {
                candidate.partitionSequence < expected -> continue
                candidate.partitionSequence == expected -> {
                    out.add(candidate)
                    expected += 1L
                }
                else -> break
            }
        }
        return out
    }

    private fun usesEncodedPartitionSequence(partitionId: Int, streamSequence: Long): Boolean {
        if (partitionId == 0) return true
        return (streamSequence ushr StreamSequencePartitionShift).toInt() == partitionId
    }

    private fun encodedPartitionBase(partitionId: Int): Long {
        return partitionId.toLong() shl StreamSequencePartitionShift
    }

    private fun commandPayloadSideTableAvailable(): Boolean {
        canonicalConnection().use { conn ->
            conn.prepareStatement("SELECT to_regclass('command_log.command_payloads') IS NOT NULL").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getBoolean(1)
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

    override fun projectionStatus(projectionName: String, partitions: List<Int>, source: String): ProjectionStatus {
        if (projectionStoreSeparated()) {
            return projectionStatusAcrossStores(projectionName, partitions, source)
        }
        canonicalConnection().use { conn ->
            val canonicalRowsSql = canonicalProjectionRowsSql(source)
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
                canonical_rows AS (
                  $canonicalRowsSql
                ),
                canonical_partitions AS (
                  SELECT
                    canonical.partition_id,
                    MAX(canonical.partition_seq) AS canonical_max_partition_seq,
                    COUNT(*) FILTER (
                      WHERE canonical.partition_seq > COALESCE(watermark_partitions.last_partition_seq, 0)
                    ) AS lag
                  FROM canonical_rows canonical
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

    private fun projectionStatusAcrossStores(
        projectionName: String,
        partitions: List<Int>,
        source: String
    ): ProjectionStatus {
        val watermarkRows = projectionWatermarkRows(projectionName, partitions)
        val canonicalStatsByPartition = canonicalPartitionStats(partitions, watermarkRows, source)
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

    override fun canonicalCommandResult(commandId: String): CanonicalCommandResult? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  command_id,
                  partition_id,
                  stream_name,
                  stream_seq,
                  command_type,
                  payload_hash,
                  instrument_id,
                  result_status,
                  reject_code,
                  engine_shard_id,
                  result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandResults}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return CanonicalCommandResult(
                        commandId = rs.getString("command_id"),
                        partition = rs.getInt("partition_id"),
                        commandStream = rs.getString("stream_name"),
                        streamSequence = rs.getLong("stream_seq"),
                        commandType = rs.getString("command_type"),
                        payloadHash = rs.getString("payload_hash"),
                        instrumentId = rs.getString("instrument_id"),
                        resultStatus = rs.getString("result_status"),
                        rejectCode = rs.getString("reject_code"),
                        engineShardId = rs.getString("engine_shard_id"),
                        resultPayloadJson = rs.getString("result_payload")
                    )
                }
            }
        }
    }

    override fun venueEventBatchCommandReference(commandId: String): VenueEventBatchCommandReference? {
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
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return VenueEventBatchCommandReference(
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

    override fun rebuildOrderLifecycleState(): Long {
        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM ${names.orderLifecycleState}").use { ps ->
                    ps.executeUpdate()
                }
                val inserted = conn.prepareStatement(
                    """
                    WITH execution_totals AS (
                      SELECT
                        order_id,
                        SUM(quantity_units::NUMERIC) AS filled_quantity_units
                      FROM ${names.executions}
                      WHERE quantity_units ~ '^[0-9]+(\.[0-9]+)?$'
                      GROUP BY order_id
                    ),
                    latest_modify AS (
                      SELECT DISTINCT ON (order_id)
                        order_id,
                        COALESCE(NULLIF(payload_json->>'quantityUnits', ''), '') AS modified_quantity_units,
                        COALESCE(NULLIF(payload_json->>'limitPrice', ''), '') AS modified_limit_price,
                        occurred_at
                      FROM ${names.runtimeEvents}
                      WHERE event_type = 'OrderModified'
                      ORDER BY order_id, occurred_at DESC, sequence_number DESC, event_id DESC
                    ),
                    order_event_state AS (
                      SELECT
                        order_id,
                        BOOL_OR(event_type = 'OrderCancelled') AS cancelled,
                        BOOL_OR(event_type = 'OrderRejected') AS rejected,
                        COALESCE(MAX(NULLIF(occurred_at, '')), '') AS last_event_at
                      FROM ${names.runtimeEvents}
                      GROUP BY order_id
                    ),
                    shaped AS (
                      SELECT
                        orders.order_id,
                        orders.engine_order_id,
                        orders.instrument_id,
                        orders.participant_id,
                        orders.account_id,
                        orders.side,
                        orders.order_type,
                        orders.quantity_units AS original_quantity_units,
                        COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) AS current_quantity_units,
                        COALESCE(NULLIF(latest_modify.modified_limit_price, ''), orders.limit_price) AS current_limit_price,
                        orders.currency,
                        orders.time_in_force,
                        orders.accepted_at,
                        COALESCE(execution_totals.filled_quantity_units, 0) AS filled_quantity_units,
                        COALESCE(order_event_state.cancelled, FALSE) AS cancelled,
                        COALESCE(order_event_state.rejected, FALSE) AS rejected,
                        COALESCE(NULLIF(order_event_state.last_event_at, ''), orders.accepted_at) AS last_event_at
                      FROM ${names.orders} orders
                      LEFT JOIN execution_totals ON execution_totals.order_id = orders.order_id
                      LEFT JOIN latest_modify ON latest_modify.order_id = orders.order_id
                      LEFT JOIN order_event_state ON order_event_state.order_id = orders.order_id
                      WHERE COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) ~ '^[0-9]+(\.[0-9]+)?$'
                    ),
                    calculated AS (
                      SELECT
                        *,
                        GREATEST(current_quantity_units::NUMERIC - filled_quantity_units, 0) AS remaining_quantity_units,
                        CASE
                          WHEN current_limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN current_limit_price::NUMERIC
                          ELSE NULL
                        END AS current_limit_price_num
                      FROM shaped
                    )
                    INSERT INTO ${names.orderLifecycleState}(
                      order_id,
                      engine_order_id,
                      instrument_id,
                      participant_id,
                      account_id,
                      side,
                      order_type,
                      original_quantity_units,
                      remaining_quantity_units,
                      filled_quantity_units,
                      limit_price,
                      currency,
                      time_in_force,
                      status,
                      accepted_at,
                      last_event_at,
                      updated_at,
                      original_quantity_units_num,
                      remaining_quantity_units_num,
                      filled_quantity_units_num,
                      limit_price_num
                    )
                    SELECT
                      order_id,
                      engine_order_id,
                      instrument_id,
                      participant_id,
                      account_id,
                      side,
                      order_type,
                      original_quantity_units,
                      CASE WHEN cancelled OR rejected THEN '0' ELSE remaining_quantity_units::TEXT END,
                      filled_quantity_units::TEXT,
                      current_limit_price,
                      currency,
                      time_in_force,
                      CASE
                        WHEN rejected THEN 'REJECTED'
                        WHEN cancelled THEN 'CANCELLED'
                        WHEN current_quantity_units::NUMERIC > 0 AND remaining_quantity_units = 0 THEN 'FILLED'
                        WHEN filled_quantity_units > 0 THEN 'PARTIALLY_FILLED'
                        ELSE 'OPEN'
                      END,
                      accepted_at,
                      last_event_at,
                      now(),
                      original_quantity_units::NUMERIC,
                      CASE WHEN cancelled OR rejected THEN 0 ELSE remaining_quantity_units END,
                      filled_quantity_units,
                      current_limit_price_num
                    FROM calculated
                    ORDER BY order_id
                    """.trimIndent()
                ).use { ps ->
                    ps.executeUpdate().toLong()
                }
                conn.commit()
                return inserted
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun projectOrderLifecycleState(batchSize: Int): Long {
        if (batchSize <= 0) return 0
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectOrderLifecycleStateFunction}(?)
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, batchSize)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun orderLifecycleState(orderId: String): OrderLifecycleState? {
        return projectionQueryList(
            """
            SELECT
              order_id,
              engine_order_id,
              instrument_id,
              participant_id,
              account_id,
              side,
              order_type,
              original_quantity_units,
              remaining_quantity_units,
              filled_quantity_units,
              limit_price,
              currency,
              time_in_force,
              status,
              accepted_at,
              last_event_at,
              updated_at::TEXT AS updated_at
            FROM ${names.orderLifecycleState}
            WHERE order_id = ?
            """.trimIndent(),
            orderId
        ) {
            OrderLifecycleState(
                orderId = getString("order_id"),
                engineOrderId = getString("engine_order_id"),
                instrumentId = getString("instrument_id"),
                participantId = getString("participant_id"),
                accountId = getString("account_id"),
                side = getString("side"),
                orderType = getString("order_type"),
                originalQuantityUnits = getString("original_quantity_units"),
                remainingQuantityUnits = getString("remaining_quantity_units"),
                filledQuantityUnits = getString("filled_quantity_units"),
                limitPrice = getString("limit_price"),
                currency = getString("currency"),
                timeInForce = getString("time_in_force"),
                status = getString("status"),
                acceptedAt = getString("accepted_at"),
                lastEventAt = getString("last_event_at"),
                updatedAt = getString("updated_at")
            )
        }.firstOrNull()
    }

    override fun refreshMarketDataSnapshots(projectionName: String, sourceProjectionName: String): Long {
        // See marketDataDepthSnapshot: order_lifecycle_state is already kept
        // current by the incremental OrderLifecycleProjectionWorker, so a
        // synchronous full-venue rebuild here is redundant.
        val sourceStatus = projectionStatus(sourceProjectionName, source = "venue-event-batch")
        val lastPartitionSequence = sourceStatus.watermarks
            .filter { it.partitionId >= 0 }
            .maxOfOrNull { it.lastPartitionSequence } ?: 0L
        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM ${names.marketDataSnapshots} WHERE projection_name = ?").use { ps ->
                    ps.setString(1, projectionName)
                    ps.executeUpdate()
                }
                val inserted = conn.prepareStatement(
                    """
                    WITH priced_orders AS (
                      SELECT
                        instrument_id,
                        side,
                        currency,
                        limit_price_num AS price_num,
                        remaining_quantity_units_num AS quantity_num
                      FROM ${names.orderLifecycleState}
                      WHERE order_type = 'LIMIT'
                        AND status IN ('OPEN', 'PARTIALLY_FILLED')
                        AND limit_price_num IS NOT NULL
                        AND remaining_quantity_units_num > 0
                    ),
                    bid_prices AS (
                      SELECT instrument_id, MAX(price_num) AS best_bid_price
                      FROM priced_orders
                      WHERE side = 'BUY'
                      GROUP BY instrument_id
                    ),
                    ask_prices AS (
                      SELECT instrument_id, MIN(price_num) AS best_ask_price
                      FROM priced_orders
                      WHERE side = 'SELL'
                      GROUP BY instrument_id
                    ),
                    bid_totals AS (
                      SELECT priced.instrument_id, SUM(priced.quantity_num) AS best_bid_quantity
                      FROM priced_orders priced
                      JOIN bid_prices best
                        ON best.instrument_id = priced.instrument_id
                       AND best.best_bid_price = priced.price_num
                      WHERE priced.side = 'BUY'
                      GROUP BY priced.instrument_id
                    ),
                    ask_totals AS (
                      SELECT priced.instrument_id, SUM(priced.quantity_num) AS best_ask_quantity
                      FROM priced_orders priced
                      JOIN ask_prices best
                        ON best.instrument_id = priced.instrument_id
                       AND best.best_ask_price = priced.price_num
                      WHERE priced.side = 'SELL'
                      GROUP BY priced.instrument_id
                    ),
                    instruments AS (
                      SELECT instrument_id, MAX(currency) AS currency
                      FROM priced_orders
                      GROUP BY instrument_id
                    )
                    INSERT INTO ${names.marketDataSnapshots}(
                      projection_name,
                      source_projection_name,
                      instrument_id,
                      best_bid_price,
                      best_bid_quantity,
                      best_ask_price,
                      best_ask_quantity,
                      currency,
                      last_partition_seq,
                      lag,
                      updated_at,
                      best_bid_price_num,
                      best_bid_quantity_num,
                      best_ask_price_num,
                      best_ask_quantity_num
                    )
                    SELECT
                      ?,
                      ?,
                      instruments.instrument_id,
                      COALESCE(bid_prices.best_bid_price::TEXT, ''),
                      COALESCE(bid_totals.best_bid_quantity::TEXT, ''),
                      COALESCE(ask_prices.best_ask_price::TEXT, ''),
                      COALESCE(ask_totals.best_ask_quantity::TEXT, ''),
                      COALESCE(instruments.currency, ''),
                      ?,
                      ?,
                      now(),
                      bid_prices.best_bid_price,
                      bid_totals.best_bid_quantity,
                      ask_prices.best_ask_price,
                      ask_totals.best_ask_quantity
                    FROM instruments
                    LEFT JOIN bid_prices ON bid_prices.instrument_id = instruments.instrument_id
                    LEFT JOIN bid_totals ON bid_totals.instrument_id = instruments.instrument_id
                    LEFT JOIN ask_prices ON ask_prices.instrument_id = instruments.instrument_id
                    LEFT JOIN ask_totals ON ask_totals.instrument_id = instruments.instrument_id
                    ORDER BY instruments.instrument_id
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, projectionName)
                    ps.setString(2, sourceProjectionName)
                    ps.setLong(3, lastPartitionSequence)
                    ps.setLong(4, sourceStatus.lag)
                    ps.executeUpdate().toLong()
                }
                conn.commit()
                return inserted
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun projectMarketDataSnapshots(
        projectionName: String,
        sourceProjectionName: String,
        batchSize: Int
    ): Long {
        if (batchSize <= 0) return 0
        projectOrderLifecycleState(batchSize)
        val sourceStatus = projectionStatus(sourceProjectionName, source = "venue-event-batch")
        val lastPartitionSequence = sourceStatus.watermarks
            .filter { it.partitionId >= 0 }
            .maxOfOrNull { it.lastPartitionSequence } ?: 0L
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectMarketDataSnapshotsFunction}(?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setString(2, sourceProjectionName)
                ps.setLong(3, lastPartitionSequence)
                ps.setLong(4, sourceStatus.lag)
                ps.setInt(5, batchSize)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun marketDataSnapshot(instrumentId: String, projectionName: String): MarketDataSnapshot? {
        return projectionQueryList(
            """
            SELECT
              projection_name,
              source_projection_name,
              instrument_id,
              best_bid_price,
              best_bid_quantity,
              best_ask_price,
              best_ask_quantity,
              currency,
              last_partition_seq,
              lag,
              updated_at::TEXT AS updated_at
            FROM ${names.marketDataSnapshots}
            WHERE projection_name = ? AND instrument_id = ?
            """.trimIndent(),
            projectionName,
            instrumentId
        ) {
            MarketDataSnapshot(
                projectionName = getString("projection_name"),
                sourceProjectionName = getString("source_projection_name"),
                instrumentId = getString("instrument_id"),
                bestBidPrice = getString("best_bid_price"),
                bestBidQuantity = getString("best_bid_quantity"),
                bestAskPrice = getString("best_ask_price"),
                bestAskQuantity = getString("best_ask_quantity"),
                currency = getString("currency"),
                lastPartitionSequence = getLong("last_partition_seq"),
                lag = getLong("lag"),
                updatedAt = getString("updated_at")
            )
        }.firstOrNull()
    }

    override fun marketDataDepthSnapshot(
        instrumentId: String,
        levels: Int,
        projectionName: String,
        sourceProjectionName: String
    ): MarketDataDepthSnapshot? {
        val boundedLevels = levels.coerceIn(1, 50)
        // order_lifecycle_state is kept current by the incremental
        // OrderLifecycleProjectionWorker (250ms cadence). Do not force a
        // synchronous full-venue rebuildOrderLifecycleState() here: it was
        // redundant with that worker and, worse, scaled with total venue
        // order history rather than the single requested instrument -
        // every depth-book read paid for a full DELETE+rebuild across every
        // order/execution/event in the system. Callers that need a forced
        // full rebuild use the dedicated rebuildOrderLifecycleState route.
        val sourceStatus = projectionStatus(sourceProjectionName, source = "venue-event-batch")
        val lastPartitionSequence = sourceStatus.watermarks
            .filter { it.partitionId >= 0 }
            .maxOfOrNull { it.lastPartitionSequence } ?: 0L
        val bidLevels = depthLevels(instrumentId, "BUY", "DESC", boundedLevels)
        val askLevels = depthLevels(instrumentId, "SELL", "ASC", boundedLevels)
        if (bidLevels.isEmpty() && askLevels.isEmpty()) return null
        val currency = projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT COALESCE(MAX(currency), '') AS currency
                FROM ${names.orderLifecycleState}
                WHERE instrument_id = ?
                  AND status IN ('OPEN', 'PARTIALLY_FILLED')
                  AND remaining_quantity_units_num > 0
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("currency")
                }
            }
        }
        return MarketDataDepthSnapshot(
            projectionName = projectionName,
            sourceProjectionName = sourceProjectionName,
            instrumentId = instrumentId,
            bidLevels = bidLevels,
            askLevels = askLevels,
            currency = currency,
            levels = boundedLevels,
            lastPartitionSequence = lastPartitionSequence,
            lag = sourceStatus.lag,
            updatedAt = java.time.Instant.now().toString()
        )
    }

    private fun depthLevels(
        instrumentId: String,
        side: String,
        direction: String,
        levels: Int
    ): List<MarketDataDepthLevel> {
        require(direction == "ASC" || direction == "DESC") { "unsupported depth sort direction" }
        return projectionQueryList(
            """
            SELECT
              price_num::TEXT AS price,
              SUM(remaining_quantity_units_num)::TEXT AS quantity
            FROM (
              SELECT
                limit_price_num AS price_num,
                remaining_quantity_units_num
              FROM ${names.orderLifecycleState}
              WHERE instrument_id = ?
                AND side = ?
                AND order_type = 'LIMIT'
                AND status IN ('OPEN', 'PARTIALLY_FILLED')
                AND limit_price_num IS NOT NULL
                AND remaining_quantity_units_num > 0
            ) priced
            GROUP BY price_num
            ORDER BY price_num $direction
            LIMIT ?::INTEGER
            """.trimIndent(),
            instrumentId,
            side,
            levels.toString()
        ) {
            MarketDataDepthLevel(
                price = getString("price"),
                quantity = getString("quantity")
            )
        }
    }

    private data class CanonicalPartitionStats(val maxPartitionSequence: Long, val backlogCount: Long)

    private fun canonicalPartitionStats(
        partitions: List<Int>,
        watermarks: Map<Int, ProjectionWatermark>,
        source: String
    ): Map<Int, CanonicalPartitionStats> {
        canonicalConnection().use { conn ->
            val canonicalRowsSql = canonicalProjectionRowsSql(source)
            val watermarkPartitions = watermarks.keys.filter { it >= 0 }.sorted()
            val watermarkSequences = watermarkPartitions.map { watermarks.getValue(it).lastPartitionSequence }
            conn.prepareStatement(
                """
                WITH watermark_partitions AS (
                  SELECT *
                  FROM unnest(?::INTEGER[], ?::BIGINT[]) AS watermark(partition_id, last_partition_seq)
                ),
                canonical_rows AS (
                  $canonicalRowsSql
                )
                SELECT
                  canonical.partition_id,
                  MAX(canonical.partition_seq) AS canonical_max_partition_seq,
                  COUNT(*) FILTER (
                    WHERE canonical.partition_seq > COALESCE(watermark_partitions.last_partition_seq, 0)
                  ) AS lag
                FROM canonical_rows canonical
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

    private fun canonicalProjectionRowsSql(source: String): String {
        return when (source.trim().lowercase()) {
            "venue-event-batch", "event-batch", "venue-events", "canonical-command-outcomes" ->
                """
                SELECT partition_id, stream_sequence AS partition_seq
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                """.trimIndent()
            else ->
                """
                SELECT partition_id, partition_seq
                FROM ${names.canonicalCommandResults}
                """.trimIndent()
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
                INSERT INTO ${names.orders}(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                  accepted_at = EXCLUDED.accepted_at,
                  client_order_id = EXCLUDED.client_order_id,
                  run_id = EXCLUDED.run_id,
                  venue_session_id = EXCLUDED.venue_session_id
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
                ps.setString(13, order.clientOrderId)
                ps.setString(14, order.runId)
                ps.setString(15, order.venueSessionId)
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
                SELECT order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id
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
                        acceptedAt = rs.getString("accepted_at"),
                        clientOrderId = rs.getString("client_order_id"),
                        runId = rs.getString("run_id"),
                        venueSessionId = rs.getString("venue_session_id")
                    )
                }
            }
        }
    }

    override fun findOrderByClientOrderId(participantId: String, clientOrderId: String): PersistedOrder? {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id
                FROM ${names.orders} WHERE participant_id = ? AND client_order_id = ?
                ORDER BY accepted_at_ts DESC NULLS LAST, accepted_at DESC, order_id DESC
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, participantId)
                ps.setString(2, clientOrderId)
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
                        acceptedAt = rs.getString("accepted_at"),
                        clientOrderId = rs.getString("client_order_id"),
                        runId = rs.getString("run_id"),
                        venueSessionId = rs.getString("venue_session_id")
                    )
                }
            }
        }
    }

    override fun acceptedOrders(): List<PersistedOrder> = projectionQueryList(
        """
        SELECT order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id
        FROM ${names.orders} ORDER BY accepted_at_ts NULLS LAST, accepted_at, order_id
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
            acceptedAt = getString("accepted_at"),
            clientOrderId = getString("client_order_id"),
            runId = getString("run_id"),
            venueSessionId = getString("venue_session_id")
        )
    }

    override fun ordersForParticipant(
        participantId: String,
        openOnly: Boolean,
        instrumentId: String,
        limit: Int
    ): List<OwnOrderView> {
        val statusFilter = if (openOnly) "AND ols.status IN ('OPEN', 'PARTIALLY_FILLED')" else ""
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND ols.instrument_id = ?"
        val boundedLimit = limit.coerceIn(0, 500)
        val limitClause = if (boundedLimit > 0) "LIMIT ?::integer" else ""
        val params = buildList {
            add(participantId)
            if (instrumentId.isNotBlank()) add(instrumentId)
            if (boundedLimit > 0) add(boundedLimit.toString())
        }
        val lifecycleOrders = projectionQueryList(
            """
            SELECT ols.order_id, ols.instrument_id, ols.side, ols.original_quantity_units, ols.remaining_quantity_units, ols.limit_price, ols.status
            FROM ${names.orderLifecycleState} ols
            WHERE ols.participant_id = ?
            $instrumentFilter
            $statusFilter
            ORDER BY ols.accepted_at
            $limitClause
            """.trimIndent(),
            *params.toTypedArray()
        ) {
            OwnOrderView(
                orderId = getString("order_id"),
                instrumentId = getString("instrument_id"),
                side = getString("side"),
                quantityUnits = getString("original_quantity_units"),
                remainingQuantityUnits = getString("remaining_quantity_units"),
                limitPrice = getString("limit_price"),
                status = getString("status")
            )
        }
        if (lifecycleOrders.isNotEmpty()) return lifecycleOrders
        return acceptedOrdersForParticipantFallback(participantId, instrumentId, boundedLimit)
    }

    private fun acceptedOrdersForParticipantFallback(
        participantId: String,
        instrumentId: String,
        boundedLimit: Int
    ): List<OwnOrderView> {
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND instrument_id = ?"
        val limitClause = if (boundedLimit > 0) "LIMIT ?::integer" else ""
        val params = buildList {
            add(participantId)
            if (instrumentId.isNotBlank()) add(instrumentId)
            if (boundedLimit > 0) add(boundedLimit.toString())
        }
        val sql = """
            SELECT order_id, instrument_id, side, quantity_units, limit_price
            FROM ${names.orders}
            WHERE participant_id = ?
            $instrumentFilter
            ORDER BY accepted_at_ts DESC NULLS LAST, accepted_at DESC, order_id DESC
            $limitClause
            """.trimIndent()
        val mapOrder: java.sql.ResultSet.() -> OwnOrderView = {
            OwnOrderView(
                orderId = getString("order_id"),
                instrumentId = getString("instrument_id"),
                side = getString("side"),
                quantityUnits = getString("quantity_units"),
                remainingQuantityUnits = getString("quantity_units"),
                limitPrice = getString("limit_price"),
                status = "OPEN"
            )
        }
        val projectionOrders = projectionQueryList(sql, *params.toTypedArray(), map = mapOrder)
        if (projectionOrders.isNotEmpty() || !projectionStoreSeparated()) return projectionOrders
        return queryList(sql, *params.toTypedArray(), map = mapOrder)
    }

    override fun executionsForParticipant(
        participantId: String,
        instrumentId: String,
        limit: Int
    ): List<OwnExecutionView> {
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND e.instrument_id = ?"
        val boundedLimit = limit.coerceIn(0, 500)
        val limitClause = if (boundedLimit > 0) "LIMIT ?::integer" else ""
        val params = buildList {
            add(participantId)
            if (instrumentId.isNotBlank()) add(instrumentId)
            if (boundedLimit > 0) add(boundedLimit.toString())
        }
        return projectionQueryList(
            """
            SELECT e.execution_id, e.order_id, e.instrument_id, o.side, e.quantity_units, e.execution_price, e.currency, e.occurred_at
            FROM ${names.executions} e
            JOIN ${names.orders} o ON o.order_id = e.order_id
            WHERE o.participant_id = ?
            $instrumentFilter
            ORDER BY e.occurred_at_ts NULLS LAST, e.occurred_at, e.execution_id
            $limitClause
            """.trimIndent(),
            *params.toTypedArray()
        ) {
            OwnExecutionView(
                executionId = getString("execution_id"),
                orderId = getString("order_id"),
                instrumentId = getString("instrument_id"),
                side = getString("side"),
                quantityUnits = getString("quantity_units"),
                executionPrice = getString("execution_price"),
                currency = getString("currency"),
                occurredAt = getString("occurred_at")
            )
        }
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> = projectionQueryList(
        "SELECT event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at FROM ${names.executions} WHERE order_id = ? ORDER BY occurred_at_ts NULLS LAST, occurred_at, event_id",
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
        "SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM ${names.trades} ORDER BY occurred_at_ts NULLS LAST, occurred_at, event_id"
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
        "SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM ${names.trades} ORDER BY occurred_at_ts DESC NULLS LAST, occurred_at DESC, event_id DESC LIMIT ?::integer",
        limit.coerceIn(0, 500).toString()
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
        FROM ${names.trades} WHERE buy_order_id = ? OR sell_order_id = ? ORDER BY occurred_at_ts NULLS LAST, occurred_at, event_id
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

    override fun tradeTape(instrumentId: String, limit: Int, beforeSequence: Long?): List<PublicTradeTapeEntry> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return if (beforeSequence != null) {
            projectionQueryList(
                """
                SELECT sequence, trade_id, instrument_id, quantity_units, price, currency, occurred_at
                FROM ${names.trades}
                WHERE instrument_id = ? AND sequence < ?::bigint
                ORDER BY sequence DESC
                LIMIT ?::integer
                """.trimIndent(),
                instrumentId,
                beforeSequence.toString(),
                effectiveLimit.toString()
            ) { toPublicTradeTapeEntry() }
        } else {
            projectionQueryList(
                """
                SELECT sequence, trade_id, instrument_id, quantity_units, price, currency, occurred_at
                FROM ${names.trades}
                WHERE instrument_id = ?
                ORDER BY sequence DESC
                LIMIT ?::integer
                """.trimIndent(),
                instrumentId,
                effectiveLimit.toString()
            ) { toPublicTradeTapeEntry() }
        }
    }

    override fun intradayBars(instrumentId: String, interval: String, start: String, end: String): List<IntradayBar> {
        val intervalText = intradayBarIntervalText[interval] ?: return emptyList()
        return projectionQueryList(
            """
            WITH bucketed AS (
              SELECT
                date_bin(?::interval, occurred_at_ts, TIMESTAMPTZ '2000-01-01') AS bucket_start,
                price_num,
                quantity_units_num AS qty_num,
                sequence
              FROM ${names.trades}
              WHERE instrument_id = ?
                AND occurred_at_ts >= ?::timestamptz
                AND occurred_at_ts < ?::timestamptz
                AND price_num IS NOT NULL
                AND quantity_units_num IS NOT NULL
            )
            SELECT
              bucket_start::text AS bucket_start,
              (bucket_start + ?::interval)::text AS bucket_end,
              (array_agg(price_num ORDER BY sequence ASC))[1] AS open,
              MAX(price_num) AS high,
              MIN(price_num) AS low,
              (array_agg(price_num ORDER BY sequence DESC))[1] AS close,
              SUM(qty_num) AS volume
            FROM bucketed
            GROUP BY bucket_start
            ORDER BY bucket_start
            """.trimIndent(),
            intervalText,
            instrumentId,
            start,
            end,
            intervalText
        ) {
            IntradayBar(
                instrumentId = instrumentId,
                start = getString("bucket_start"),
                end = getString("bucket_end"),
                open = getString("open"),
                high = getString("high"),
                low = getString("low"),
                close = getString("close"),
                volume = getString("volume")
            )
        }
    }

    private fun java.sql.ResultSet.toPublicTradeTapeEntry(): PublicTradeTapeEntry {
        return PublicTradeTapeEntry(
            sequence = getLong("sequence"),
            tradeId = getString("trade_id"),
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
        "SELECT * FROM ${names.runtimeEvents} ORDER BY occurred_at_ts DESC NULLS LAST, occurred_at DESC, event_id DESC LIMIT ?::integer",
        limit.coerceIn(0, 500).toString()
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

    private fun CanonicalCommandOutcome.toPersistableSubmitOutcome(
        commandPayloadJson: String = "{}",
        includeFills: Boolean = true
    ): PersistableSubmitOutcome {
        val resultPayload = JsonCodec.parseObjectOrEmpty(resultPayloadJson)
        val embeddedAcceptedOrder = resultPayload.obj("acceptedOrder")
        val commandPayload = JsonCodec.parseObjectOrEmpty(commandPayloadJson)
        val eventId = jsonString(resultPayloadJson, "eventId").ifBlank { "evt-$commandId" }
        val occurredAt = jsonString(resultPayloadJson, "occurredAt")
        val rejected = resultStatus == "rejected" || resultStatus == "failed"
        val result = if (rejected) {
            SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = eventId,
                    orderId = orderId,
                    code = rejectCode.ifBlank { jsonString(resultPayloadJson, "code") },
                    reason = jsonString(resultPayloadJson, "reason"),
                    occurredAt = occurredAt
                ),
                executions = if (includeFills) executionsFromResultPayload(resultPayloadJson) else emptyList(),
                trades = if (includeFills) tradesFromResultPayload(resultPayloadJson) else emptyList()
            )
        } else {
            SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = eventId,
                    orderId = orderId,
                    engineOrderId = jsonString(resultPayloadJson, "engineOrderId"),
                    occurredAt = occurredAt
                ),
                executions = if (includeFills) executionsFromResultPayload(resultPayloadJson) else emptyList(),
                trades = if (includeFills) tradesFromResultPayload(resultPayloadJson) else emptyList()
            )
        }
        val rejectCodeValue = rejectCode.ifBlank { jsonString(resultPayloadJson, "code") }
        val acceptedOrder = if (
            commandType == "SubmitOrder" &&
            (!rejected || rejectCodeValue !in NonLifecycleRejectCodes)
        ) {
            fun orderField(key: String): String {
                return embeddedAcceptedOrder.string(key).ifBlank { commandPayload.string(key) }
            }
            PersistedOrder(
                orderId = orderField("orderId").ifBlank { orderId },
                engineOrderId = if (rejected) "" else orderField("engineOrderId").ifBlank { jsonString(resultPayloadJson, "engineOrderId") },
                instrumentId = orderField("instrumentId"),
                participantId = orderField("participantId"),
                accountId = orderField("accountId"),
                side = orderField("side"),
                orderType = orderField("orderType"),
                quantityUnits = orderField("quantityUnits"),
                limitPrice = orderField("limitPrice"),
                currency = orderField("currency"),
                timeInForce = orderField("timeInForce"),
                acceptedAt = orderField("acceptedAt").ifBlank { occurredAt },
                clientOrderId = orderField("clientOrderId"),
                runId = orderField("runId"),
                venueSessionId = orderField("venueSessionId")
            ).takeIf {
                it.orderId.isNotBlank() &&
                it.instrumentId.isNotBlank() &&
                    it.participantId.isNotBlank() &&
                    it.accountId.isNotBlank()
            }
        } else {
            null
        }
        return PersistableSubmitOutcome(
            commandId = commandId,
            result = result,
            acceptedOrder = acceptedOrder,
            lifecycleEvents = listOf(
                RuntimeEvent(
                    eventId = eventId,
                    eventType = lifecycleEventType(commandType, rejected),
                    orderId = orderId,
                    traceId = commandId,
                    causationId = commandId,
                    correlationId = commandId,
                    actorId = "",
                    producer = "venue-event-batch-projector",
                    schemaVersion = "v1",
                    occurredAt = occurredAt,
                    payloadJson = resultPayloadJson.ifBlank { "{}" }
                )
            )
        )
    }

    private fun lifecycleEventType(commandType: String, rejected: Boolean): String {
        if (rejected) return "OrderRejected"
        return when (commandType) {
            "CancelOrder" -> "OrderCancelled"
            "ModifyOrder" -> "OrderModified"
            else -> "OrderAccepted"
        }
    }

    private fun executionsFromResultPayload(json: String): List<ExecutionCreated> {
        return JsonCodec.parseObjectOrEmpty(json).objectDocuments("executions").map { execution ->
            ExecutionCreated(
                eventId = execution.string("eventId"),
                executionId = execution.string("executionId"),
                orderId = execution.string("orderId"),
                instrumentId = execution.string("instrumentId"),
                quantityUnits = execution.string("quantityUnits"),
                executionPrice = execution.string("executionPrice"),
                currency = execution.string("currency"),
                occurredAt = execution.string("occurredAt")
            )
        }
    }

    private fun tradesFromResultPayload(json: String): List<TradeCreated> {
        return JsonCodec.parseObjectOrEmpty(json).objectDocuments("trades").map { trade ->
            TradeCreated(
                eventId = trade.string("eventId"),
                tradeId = trade.string("tradeId"),
                executionId = trade.string("executionId"),
                buyOrderId = trade.string("buyOrderId"),
                sellOrderId = trade.string("sellOrderId"),
                instrumentId = trade.string("instrumentId"),
                quantityUnits = trade.string("quantityUnits"),
                price = trade.string("price"),
                currency = trade.string("currency"),
                occurredAt = trade.string("occurredAt")
            )
        }
    }

    private fun jsonString(json: String, key: String): String {
        val document = JsonCodec.parseObjectOrEmpty(json)
        return document.string(key)
            .ifBlank { document.obj("accepted").string(key) }
            .ifBlank { document.obj("rejected").string(key) }
    }

    private fun projectionStoreSeparated(): Boolean = projectionDataSource !== dataSource

    private fun connection(): Connection = canonicalConnection()

    private fun canonicalConnection(): Connection = dataSource.connection

    private fun projectionConnection(): Connection = projectionDataSource.connection
}
