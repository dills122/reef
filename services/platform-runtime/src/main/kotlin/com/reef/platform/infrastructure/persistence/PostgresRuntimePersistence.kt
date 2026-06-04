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
import java.sql.Connection
import javax.sql.DataSource

class PostgresRuntimePersistence(
    private val dataSource: DataSource,
    private val names: PostgresRuntimeSqlNames = PostgresRuntimeSqlNames()
) : RuntimePersistence {
    init {
        connection().use { conn ->
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
                      producer TEXT NOT NULL,
                      schema_version TEXT NOT NULL,
                      sequence_number BIGINT NOT NULL,
                      occurred_at TEXT NOT NULL
                    )
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
                    CREATE OR REPLACE FUNCTION ${names.validateReferenceDataFunction}(
                      p_instrument_id TEXT,
                      p_participant_id TEXT,
                      p_account_id TEXT
                    )
                    RETURNS TABLE(
                      instrument_exists BOOLEAN,
                      participant_exists BOOLEAN,
                      account_exists BOOLEAN
                    )
                    LANGUAGE SQL
                    STABLE
                    AS $$
                      SELECT
                        EXISTS(SELECT 1 FROM ${names.referenceInstruments} WHERE instrument_id = p_instrument_id),
                        EXISTS(SELECT 1 FROM ${names.referenceParticipants} WHERE participant_id = p_participant_id),
                        EXISTS(SELECT 1 FROM ${names.referenceAccounts} WHERE account_id = p_account_id)
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
                      INSERT INTO ${names.runtimeEvents}(event_id, event_type, order_id, trace_id, causation_id, correlation_id, producer, schema_version, sequence_number, occurred_at)
                      SELECT
                        event->>'eventId',
                        event->>'eventType',
                        event->>'orderId',
                        event->>'traceId',
                        event->>'causationId',
                        event->>'correlationId',
                        event->>'producer',
                        event->>'schemaVersion',
                        trace_starts.start_sequence + ordered_events.trace_offset,
                        event->>'occurredAt'
                      FROM ordered_events
                      JOIN trace_starts ON trace_starts.trace_id = ordered_events.event->>'traceId'
                      ORDER BY ordered_events.ordinality
                      ON CONFLICT (event_id) DO NOTHING;
                    END;
                    $$;
                    """.trimIndent()
                )
            }
        }
    }

    override fun saveSubmitResult(commandId: String, result: SubmitOrderResult) {
        val accepted = result.accepted
        val rejected = result.rejected
        val resultType = if (accepted != null) "accepted" else "rejected"
        connection().use { conn ->
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
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT result_type, event_id, order_id, engine_order_id, code, reason, occurred_at FROM ${names.submitResults} WHERE command_id = ?"
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
                SELECT instrument_exists, participant_exists, account_exists
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
                        accountExists = rs.getBoolean("account_exists")
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

        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.persistSubmitOutcomeFunction}(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb
                )
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
                ps.setString(9, acceptedOrder?.toJsonObject())
                ps.setString(10, result.executions.toJsonArray { it.toJsonObject() })
                ps.setString(11, result.trades.toJsonArray { it.toJsonObject() })
                ps.setString(12, lifecycleEvents.toJsonArray { it.toJsonObject() })
                ps.execute()
            }
        }
    }

    override fun saveAcceptedOrder(order: PersistedOrder) {
        connection().use { conn ->
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
        connection().use { conn ->
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
        connection().use { conn ->
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
        connection().use { conn ->
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
                    INSERT INTO ${names.runtimeEvents}(event_id, event_type, order_id, trace_id, causation_id, correlation_id, producer, schema_version, sequence_number, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        ps.setString(7, event.producer)
                        ps.setString(8, event.schemaVersion)
                        ps.setLong(9, sequence)
                        ps.setString(10, event.occurredAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        connection().use { conn ->
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

    override fun acceptedOrders(): List<PersistedOrder> = queryList(
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

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> = queryList(
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

    override fun trades(): List<TradeCreated> = queryList(
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

    override fun recentTrades(limit: Int): List<TradeCreated> = queryList(
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

    override fun tradesForOrder(orderId: String): List<TradeCreated> = queryList(
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

    private fun queryEvents(sql: String, vararg params: String): List<RuntimeEvent> = queryList(sql, *params) {
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
            occurredAt = getString("occurred_at")
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
        connection().use { conn ->
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

    private fun RuntimeEvent.toJsonObject(): String = jsonObject(
        "eventId" to eventId,
        "eventType" to eventType,
        "orderId" to orderId,
        "traceId" to traceId,
        "causationId" to causationId,
        "correlationId" to correlationId,
        "producer" to producer,
        "schemaVersion" to schemaVersion,
        "occurredAt" to occurredAt
    )

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

    private fun connection(): Connection = dataSource.connection
}
