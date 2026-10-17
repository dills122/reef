package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.validQuoteCurrency
import java.sql.Connection
import javax.sql.DataSource

internal const val CanonicalCommandOutcomeProjectionMaxBatchSize = 5000

/** Compat-mode schema bootstrap for [PostgresRuntimePersistence]: creates/alters tables when bootstrap mode is not Validate. */
internal object PostgresRuntimeCompatBootstrap {
    fun run(
        dataSource: DataSource,
        projectionDataSource: DataSource,
        names: PostgresRuntimeSqlNames,
        bootstrapMode: PostgresBootstrapMode,
        streamAckCanonicalQueryIndexesEnabled: Boolean,
        streamAckCanonicalEventRowsEnabled: Boolean
    ) {
        fun canonicalConnection(): Connection = dataSource.connection
        fun connection(): Connection = canonicalConnection()
        fun projectionConnection(): Connection = projectionDataSource.connection
        fun projectionStoreSeparated(): Boolean = projectionDataSource !== dataSource

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
                      symbol TEXT NOT NULL,
                      quote_currency TEXT NOT NULL DEFAULT 'USD'
                    )
                    """.trimIndent()
                )
                stmt.execute("ALTER TABLE ${names.referenceInstruments} ADD COLUMN IF NOT EXISTS quote_currency TEXT NOT NULL DEFAULT 'USD'")
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
                      liquidity_role TEXT NOT NULL DEFAULT 'UNSPECIFIED',
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
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ,
                    ADD COLUMN IF NOT EXISTS liquidity_role TEXT NOT NULL DEFAULT 'UNSPECIFIED'
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
                      modify_quantity_units TEXT NOT NULL DEFAULT '',
                      modify_limit_price TEXT NOT NULL DEFAULT '',
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
                    ADD COLUMN IF NOT EXISTS modify_quantity_units TEXT NOT NULL DEFAULT '',
                    ADD COLUMN IF NOT EXISTS modify_limit_price TEXT NOT NULL DEFAULT ''
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
                    CREATE TABLE IF NOT EXISTS ${names.runtimeEventPayloads} (
                      event_id TEXT PRIMARY KEY,
                      payload_json JSONB NOT NULL DEFAULT '{}'::jsonb
                    )
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
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_occurred_typed
                    ON ${names.runtimeEvents}(occurred_at_ts DESC, event_id_uuid DESC)
                    WHERE occurred_at_ts IS NOT NULL
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_runtime_events_order_modified_lifecycle
                    ON ${names.runtimeEvents}(
                      order_id,
                      occurred_at_ts DESC NULLS LAST,
                      occurred_at DESC,
                      sequence_number DESC,
                      event_id_uuid DESC NULLS LAST,
                      event_id DESC
                    )
                    WHERE event_type = 'OrderModified'
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
                    ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ,
                    ADD COLUMN IF NOT EXISTS cancelled JSONB,
                    ADD COLUMN IF NOT EXISTS matching_facts JSONB
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
                backfillLegacyMatchingResultFacts(stmt, names)
                ProjectionBatchClaimBootstrap.install(stmt, names)
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
                      v_event_stream TEXT;
                      v_payload_checksum TEXT;
                      v_existing_checksum TEXT;
                      v_header_inserted BOOLEAN := FALSE;
                      v_outcome_count BIGINT := 0;
                      inserted_count BIGINT := 0;
                    BEGIN
                      IF p_batch IS NULL OR jsonb_typeof(p_batch) <> 'object' THEN
                        RAISE EXCEPTION 'venue event batch payload must be a JSON object';
                      END IF;

                      v_batch_id := COALESCE(p_batch->>'batchId', '');
                      v_event_stream := COALESCE(p_batch->>'eventStream', '');
                      v_payload_checksum := COALESCE(p_batch->>'payloadChecksum', '');

                      IF v_batch_id = '' THEN
                        RAISE EXCEPTION 'venue event batch payload missing batchId';
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
                        v_event_stream,
                        COALESCE((p_batch->>'firstSequence')::BIGINT, 0),
                        COALESCE((p_batch->>'lastSequence')::BIGINT, 0),
                        COALESCE((p_batch->>'commandCount')::INTEGER, 0),
                        v_payload_checksum,
                        COALESCE(NULLIF(p_batch->>'payloadFormat', ''), 'venue-event-batch-json'),
                        COALESCE(NULLIF(p_batch->>'payloadVersion', ''), 'v1'),
                        p_batch,
                        COALESCE(p_batch->>'createdAt', '')
                      )
                      ON CONFLICT (event_stream, batch_id) DO NOTHING
                      RETURNING TRUE INTO v_header_inserted;

                      IF NOT COALESCE(v_header_inserted, FALSE) THEN
                        SELECT payload_checksum
                          INTO v_existing_checksum
                          FROM ${names.canonicalVenueEventBatches}
                         WHERE event_stream = v_event_stream
                           AND batch_id = v_batch_id;

                        IF NOT FOUND THEN
                          RAISE EXCEPTION 'venue event batch identity conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
                        END IF;
                        IF v_existing_checksum <> v_payload_checksum THEN
                          RAISE EXCEPTION 'venue event batch checksum conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
                        END IF;
                        RETURN 0;
                      END IF;

                      v_outcome_count := jsonb_array_length(
                        CASE
                          WHEN jsonb_typeof(p_batch->'outcomes') = 'array' THEN p_batch->'outcomes'
                          ELSE '[]'::jsonb
                        END
                      );

                      IF v_outcome_count <> COALESCE((p_batch->>'commandCount')::BIGINT, 0) THEN
                        RAISE EXCEPTION 'venue event batch command count mismatch for eventStream %, batchId %', v_event_stream, v_batch_id;
                      END IF;

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
                          v_event_stream,
                          COALESCE((outcome->>'streamSequence')::BIGINT, 0),
                          COALESCE((outcome->>'deliveredCount')::BIGINT, 0),
                          COALESCE(outcome->>'commandType', ''),
                          COALESCE(outcome->>'payloadHash', ''),
                          COALESCE(outcome->>'instrumentId', ''),
                          COALESCE(outcome->>'orderId', ''),
                          COALESCE(outcome->>'status', ''),
                          COALESCE(outcome->>'rejectCode', outcome#>>'{result,rejected,code}', ''),
                          COALESCE(outcome->'result', '{}'::jsonb)
                      FROM jsonb_array_elements(
                        CASE
                          WHEN jsonb_typeof(p_batch->'outcomes') = 'array' THEN p_batch->'outcomes'
                          ELSE '[]'::jsonb
                        END
                      ) AS outcome
                      ON CONFLICT (command_id) DO NOTHING;

                      GET DIAGNOSTICS inserted_count = ROW_COUNT;
                      IF inserted_count <> v_outcome_count THEN
                        RAISE EXCEPTION 'canonical command outcome conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
                      END IF;

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
                        ON CONFLICT (run_id, order_id) DO UPDATE SET
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

                      INSERT INTO ${names.orderLifecycleDirty} AS dirty(order_id)
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
                      ORDER BY order_id
                      ON CONFLICT (run_id, order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at;

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
                    CREATE OR REPLACE FUNCTION ${names.rejectExecutionReplayConflictFunction}(
                      p_event_id TEXT
                    )
                    RETURNS TEXT
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                      RAISE EXCEPTION 'execution replay conflict for existing event_id %', p_event_id
                        USING ERRCODE = '23505';
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
                        INSERT INTO ${names.submitResults}(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at, cancelled, matching_facts)
                        SELECT
                          outcome->>'commandId',
                          outcome->>'resultType',
                          outcome->>'eventId',
                          outcome->>'orderId',
                          outcome->>'engineOrderId',
                          outcome->>'code',
                          outcome->>'reason',
                          outcome->>'occurredAt',
                          NULLIF(outcome->'cancelled', 'null'::jsonb),
                          COALESCE(outcome->'matchingFacts', jsonb_build_object('executions', COALESCE(outcome->'executions', '[]'::jsonb), 'trades', COALESCE(outcome->'trades', '[]'::jsonb)))
                        FROM outcomes
                        ON CONFLICT (command_id) DO UPDATE SET
                          command_id = ${names.submitResults}.command_id
                        WHERE ${names.submitResults}.result_type = EXCLUDED.result_type
                          AND ${names.submitResults}.event_id = EXCLUDED.event_id
                          AND ${names.submitResults}.order_id = EXCLUDED.order_id
                          AND ${names.submitResults}.engine_order_id = EXCLUDED.engine_order_id
                          AND ${names.submitResults}.code = EXCLUDED.code
                          AND ${names.submitResults}.reason = EXCLUDED.reason
                          AND ${names.submitResults}.occurred_at = EXCLUDED.occurred_at
                          AND ${names.submitResults}.cancelled IS NOT DISTINCT FROM EXCLUDED.cancelled
                          AND ${names.submitResults}.matching_facts IS NOT DISTINCT FROM EXCLUDED.matching_facts
                        RETURNING 1
                      ),
                      accepted_orders AS (
                        SELECT NULLIF(outcome->'acceptedOrder', 'null'::jsonb) AS accepted_order
                        FROM outcomes
                      ),
                      upsert_orders AS (
                        INSERT INTO ${names.orders}(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id)
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
                          accepted_order->>'acceptedAt',
                          COALESCE(accepted_order->>'clientOrderId', ''),
                          COALESCE(accepted_order->>'runId', ''),
                          COALESCE(accepted_order->>'venueSessionId', '')
                        FROM accepted_orders
                        WHERE accepted_order IS NOT NULL
                          AND jsonb_typeof(accepted_order) = 'object'
                        ON CONFLICT (run_id, order_id) DO UPDATE SET
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
                        RETURNING 1
                      ),
                      insert_executions AS (
                        INSERT INTO ${names.executions}(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at, liquidity_role)
                        SELECT
                          execution->>'eventId',
                          execution->>'executionId',
                          execution->>'orderId',
                          execution->>'instrumentId',
                          execution->>'quantityUnits',
                          execution->>'executionPrice',
                          execution->>'currency',
                          execution->>'occurredAt',
                          COALESCE(NULLIF(execution->>'liquidityRole', ''), 'UNSPECIFIED')
                        FROM outcomes
                        CROSS JOIN LATERAL jsonb_array_elements(
                          CASE
                            WHEN jsonb_typeof(outcome->'executions') = 'array' THEN outcome->'executions'
                            ELSE '[]'::jsonb
                          END
                        ) AS execution
                        ON CONFLICT (event_id) DO UPDATE SET
                          event_id = ${names.rejectExecutionReplayConflictFunction}(EXCLUDED.event_id)
                        WHERE ROW(
                          ${names.executions}.execution_id,
                          ${names.executions}.order_id,
                          ${names.executions}.instrument_id,
                          ${names.executions}.quantity_units,
                          ${names.executions}.execution_price,
                          ${names.executions}.currency,
                          ${names.executions}.occurred_at,
                          ${names.executions}.liquidity_role
                        ) IS DISTINCT FROM ROW(
                          EXCLUDED.execution_id,
                          EXCLUDED.order_id,
                          EXCLUDED.instrument_id,
                          EXCLUDED.quantity_units,
                          EXCLUDED.execution_price,
                          EXCLUDED.currency,
                          EXCLUDED.occurred_at,
                          EXCLUDED.liquidity_role
                        )
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
                        ORDER BY ordered_events.event->>'eventId'
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
                if (!migratedProjectionBatchClaimWrapperAvailable(conn, names)) {
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
                          COALESCE(payloads.payload_json, '{}'::jsonb) AS command_payload,
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
                            'cancelled', result_payload->'cancelled',
                            'matchingFacts', jsonb_build_object('executions', COALESCE(result_payload->'executions', '[]'::jsonb), 'trades', COALESCE(result_payload->'trades', '[]'::jsonb)),
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
                                'traceId', COALESCE(NULLIF(command_payload->>'traceId', ''), command_id),
                                'causationId', COALESCE(NULLIF(command_payload->>'causationId', ''), command_id),
                                'correlationId', COALESCE(NULLIF(command_payload->>'correlationId', ''), command_id),
                                'actorId', '',
                                'producer', 'venue-event-batch-projector',
                                'schemaVersion', 'v1',
                                'occurredAt', COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''), ''),
                                'payloadJson', result_payload
                              )
                            ) || CASE WHEN jsonb_typeof(result_payload->'cancelled') = 'object' THEN jsonb_build_array(jsonb_build_object(
                              'eventId', result_payload #>> '{cancelled,eventId}',
                              'eventType', 'OrderCancelled', 'orderId', result_payload #>> '{cancelled,orderId}',
                              'traceId', COALESCE(NULLIF(command_payload->>'traceId', ''), command_id),
                              'causationId', result_payload #>> '{accepted,eventId}',
                              'correlationId', COALESCE(NULLIF(command_payload->>'correlationId', ''), command_id),
                              'actorId', '', 'producer', 'venue-event-batch-projector', 'schemaVersion', 'v1',
                              'occurredAt', result_payload #>> '{cancelled,occurredAt}',
                              'payloadJson', jsonb_build_object('commandId', command_id,
                                'cancelledQuantityUnits', result_payload #>> '{cancelled,cancelledQuantityUnits}',
                                'reason', result_payload #>> '{cancelled,reason}')
                            )) ELSE '[]'::jsonb END
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
        }
        if (bootstrapMode == PostgresBootstrapMode.Compat) {
            canonicalConnection().use { conn ->
                val sql = listOf("0073_runtime_order_run_identity.sql", "0074_matching_ioc_cancellation.sql").joinToString("\n") { migration ->
                    requireNotNull(javaClass.getResourceAsStream("/db/$migration")) {
                        "missing runtime bootstrap migration: $migration"
                    }.bufferedReader().use { it.readText() }
                }
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.createStatement().use { statement ->
                        statement.execute(sql.replace("runtime.", "${names.runtimeSchemaName}.").replace("'runtime'", "'${names.runtimeSchemaName}'"))
                    }
                    conn.commit()
                } catch (error: Exception) {
                    conn.rollback()
                    throw error
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
            connection().use { conn -> conn.createStatement().use { sql ->
                sql.executeQuery("""
                    SELECT EXISTS(
                      SELECT 1 FROM ${names.orders} o
                      JOIN ${names.referenceInstruments} i USING (instrument_id)
                      WHERE o.currency <> i.quote_currency AND EXISTS (
                        SELECT 1 FROM ${names.submitResults} result
                        WHERE result.run_id = o.run_id AND result.order_id = o.order_id AND result.engine_order_id = o.engine_order_id
                          AND result.result_type = 'accepted'
                      )
                    )
                """.trimIndent()).use { rows ->
                    rows.next()
                    check(!rows.getBoolean(1)) { "retained order currencies contradict instrument quote specifications" }
                }
            } }
        }
        if (bootstrapMode == PostgresBootstrapMode.Validate && projectionStoreSeparated()) {
            projectionConnection().use { conn ->
                PostgresSchemaValidator.validate(conn, PostgresSchemaRequirements.runtime(names))
            }
        }
    }

    private fun migratedProjectionBatchClaimWrapperAvailable(conn: Connection, names: PostgresRuntimeSqlNames): Boolean {
        conn.prepareStatement("SELECT to_regprocedure(?) IS NOT NULL").use { ps ->
            ps.setString(
                1,
                "${names.runtimeSchemaName}.runtime_project_canonical_command_outcomes(text,integer,integer[],boolean,text,bigint)"
            )
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getBoolean(1)
            }
        }
    }
}
