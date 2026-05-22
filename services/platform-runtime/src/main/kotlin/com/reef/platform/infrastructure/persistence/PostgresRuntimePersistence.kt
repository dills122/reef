package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import java.sql.Connection
import java.sql.DriverManager

class PostgresRuntimePersistence(
    private val jdbcUrl: String,
    private val dbUser: String,
    private val dbPassword: String
) : RuntimePersistence {
    init {
        connection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reference_instruments (
                      instrument_id TEXT PRIMARY KEY,
                      symbol TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reference_participants (
                      participant_id TEXT PRIMARY KEY,
                      name TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reference_accounts (
                      account_id TEXT PRIMARY KEY,
                      participant_id TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS orders (
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
                    CREATE TABLE IF NOT EXISTS executions (
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
                    CREATE TABLE IF NOT EXISTS trades (
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
                    CREATE TABLE IF NOT EXISTS runtime_events (
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
                    CREATE TABLE IF NOT EXISTS submit_results (
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
                INSERT INTO submit_results(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
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
                "SELECT result_type, event_id, order_id, engine_order_id, code, reason, occurred_at FROM submit_results WHERE command_id = ?"
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
        upsert("reference_instruments", "instrument_id", instrument.instrumentId, instrument.symbol)
    }

    override fun saveParticipant(participant: Participant) {
        upsert("reference_participants", "participant_id", participant.participantId, participant.name)
    }

    override fun saveAccount(account: Account) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO reference_accounts(account_id, participant_id)
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

    override fun instruments(): List<Instrument> = queryList("SELECT instrument_id, symbol FROM reference_instruments") {
        Instrument(getString("instrument_id"), getString("symbol"))
    }

    override fun participants(): List<Participant> = queryList("SELECT participant_id, name FROM reference_participants") {
        Participant(getString("participant_id"), getString("name"))
    }

    override fun accounts(): List<Account> = queryList("SELECT account_id, participant_id FROM reference_accounts") {
        Account(getString("account_id"), getString("participant_id"))
    }

    override fun hasInstrument(instrumentId: String): Boolean = exists("reference_instruments", "instrument_id", instrumentId)

    override fun hasParticipant(participantId: String): Boolean = exists("reference_participants", "participant_id", participantId)

    override fun hasAccount(accountId: String): Boolean = exists("reference_accounts", "account_id", accountId)

    override fun saveAcceptedOrder(order: PersistedOrder) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO orders(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at)
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
        connection().use { conn ->
            executions.forEach { execution ->
                conn.prepareStatement(
                    """
                    INSERT INTO executions(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, execution.eventId)
                    ps.setString(2, execution.executionId)
                    ps.setString(3, execution.orderId)
                    ps.setString(4, execution.instrumentId)
                    ps.setString(5, execution.quantityUnits)
                    ps.setString(6, execution.executionPrice)
                    ps.setString(7, execution.currency)
                    ps.setString(8, execution.occurredAt)
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        connection().use { conn ->
            trades.forEach { trade ->
                conn.prepareStatement(
                    """
                    INSERT INTO trades(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """.trimIndent()
                ).use { ps ->
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
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun saveEvent(event: RuntimeEvent) {
        connection().use { conn ->
            val nextSequence = conn.prepareStatement(
                "SELECT COALESCE(MAX(sequence_number), 0) + 1 AS next_sequence FROM runtime_events WHERE trace_id = ?"
            ).use { ps ->
                ps.setString(1, event.traceId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong("next_sequence")
                }
            }

            conn.prepareStatement(
                """
                INSERT INTO runtime_events(event_id, event_type, order_id, trace_id, causation_id, correlation_id, producer, schema_version, sequence_number, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, event.eventId)
                ps.setString(2, event.eventType)
                ps.setString(3, event.orderId)
                ps.setString(4, event.traceId)
                ps.setString(5, event.causationId)
                ps.setString(6, event.correlationId)
                ps.setString(7, event.producer)
                ps.setString(8, event.schemaVersion)
                ps.setLong(9, nextSequence)
                ps.setString(10, event.occurredAt)
                ps.executeUpdate()
            }
        }
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at
                FROM orders WHERE order_id = ?
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
        FROM orders ORDER BY accepted_at
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
        "SELECT event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at FROM executions WHERE order_id = ? ORDER BY occurred_at",
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
        "SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM trades ORDER BY occurred_at"
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
        "SELECT event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM trades ORDER BY occurred_at DESC LIMIT ?",
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
        FROM trades WHERE buy_order_id = ? OR sell_order_id = ? ORDER BY occurred_at
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
        "SELECT * FROM runtime_events WHERE order_id = ? ORDER BY trace_id, sequence_number",
        orderId
    )

    override fun eventsForTrace(traceId: String): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM runtime_events WHERE trace_id = ? ORDER BY sequence_number",
        traceId
    )

    override fun events(): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM runtime_events ORDER BY trace_id, sequence_number"
    )

    override fun recentEvents(limit: Int): List<RuntimeEvent> = queryEvents(
        "SELECT * FROM runtime_events ORDER BY occurred_at DESC, event_id DESC LIMIT ?",
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

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)
}
