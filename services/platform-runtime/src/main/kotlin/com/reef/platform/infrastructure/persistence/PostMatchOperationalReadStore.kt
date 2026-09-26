package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.OwnExecutionView
import com.reef.platform.domain.OwnOrderView
import java.sql.Connection
import javax.sql.DataSource

/** Bounded, participant-scoped reads from the isolated live store. No legacy projection joins. */
class PostMatchOperationalReadStore(private val dataSource: DataSource) {
    data class Snapshot<T>(val rows: List<T>, val frontiers: Map<Int, Long>)

    fun ordersForParticipant(
        eventStream: String, sourceGeneration: String, participantId: String,
        openOnly: Boolean, instrumentId: String, limit: Int
    ): Snapshot<OwnOrderView> = readSnapshot(eventStream, sourceGeneration) { connection ->
        require(participantId.isNotBlank() && limit in 1..500)
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND directory.instrument_id = ?"
        val statusFilter = if (openOnly) "AND state.status IN ('ACCEPTED', 'PARTIALLY_FILLED')" else ""
        connection.prepareStatement(
            """
            SELECT directory.order_id, directory.instrument_id, directory.side,
                   directory.quantity_units, state.remaining_quantity::TEXT AS remaining_quantity,
                   state.limit_price::TEXT AS limit_price, state.status
            FROM postmatch.canonical_order_directory directory
            JOIN postmatch.live_order_state state
              ON state.event_stream = directory.event_stream
             AND state.source_generation = directory.source_generation
             AND state.order_id = directory.order_id
            WHERE directory.event_stream = ? AND directory.source_generation = ?
              AND directory.participant_id = ?
              $instrumentFilter
              $statusFilter
            ORDER BY directory.accepted_at, directory.order_id
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, sourceGeneration)
            statement.setString(3, participantId)
            var index = 4
            if (instrumentId.isNotBlank()) statement.setString(index++, instrumentId)
            statement.setInt(index, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(OwnOrderView(
                        orderId = rows.getString("order_id"),
                        instrumentId = rows.getString("instrument_id"),
                        side = rows.getString("side"),
                        quantityUnits = rows.getString("quantity_units"),
                        remainingQuantityUnits = rows.getString("remaining_quantity"),
                        limitPrice = rows.getString("limit_price"),
                        status = if (rows.getString("status") == "ACCEPTED") "OPEN" else rows.getString("status")
                    ))
                }
            }
        }
    }

    fun executionsForParticipant(
        eventStream: String, sourceGeneration: String, participantId: String,
        instrumentId: String, runId: String, limit: Int
    ): Snapshot<OwnExecutionView> = readSnapshot(eventStream, sourceGeneration) { connection ->
        require(participantId.isNotBlank() && limit in 1..500)
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND execution.instrument_id = ?"
        val runFilter = if (runId.isBlank()) "" else "AND directory.run_id = ?"
        connection.prepareStatement(
            """
            SELECT execution.execution_id, execution.order_id, execution.instrument_id,
                   directory.side, execution.quantity_units::TEXT AS quantity_units,
                   execution.execution_price::TEXT AS execution_price, execution.currency,
                   execution.occurred_at, execution.liquidity_role
            FROM postmatch.live_execution_facts execution
            JOIN postmatch.canonical_order_directory directory
              ON directory.event_stream = execution.event_stream
             AND directory.source_generation = execution.source_generation
             AND directory.order_id = execution.order_id
            WHERE execution.event_stream = ? AND execution.source_generation = ?
              AND directory.participant_id = ?
              $instrumentFilter
              $runFilter
            ORDER BY execution.occurred_at, execution.execution_id
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, sourceGeneration)
            statement.setString(3, participantId)
            var index = 4
            if (instrumentId.isNotBlank()) statement.setString(index++, instrumentId)
            if (runId.isNotBlank()) statement.setString(index++, runId)
            statement.setInt(index, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(OwnExecutionView(
                        executionId = rows.getString("execution_id"),
                        orderId = rows.getString("order_id"),
                        instrumentId = rows.getString("instrument_id"),
                        side = rows.getString("side"),
                        quantityUnits = rows.getString("quantity_units"),
                        executionPrice = rows.getString("execution_price"),
                        currency = rows.getString("currency"),
                        occurredAt = rows.getTimestamp("occurred_at").toInstant().toString(),
                        liquidityRole = rows.getString("liquidity_role")
                    ))
                }
            }
        }
    }

    private fun <T> readSnapshot(
        eventStream: String, sourceGeneration: String, query: (Connection) -> List<T>
    ): Snapshot<T> {
        require(eventStream.isNotBlank() && sourceGeneration.isNotBlank())
        return dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val frontiers = connection.prepareStatement(
                    """
                    SELECT partition_id, last_stream_sequence
                    FROM postmatch.consumer_frontiers
                    WHERE consumer_name = 'live-v1' AND event_stream = ? AND source_generation = ?
                    ORDER BY partition_id
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, eventStream)
                    statement.setString(2, sourceGeneration)
                    statement.executeQuery().use { rows ->
                        buildMap {
                            while (rows.next()) put(rows.getInt(1), rows.getLong(2))
                        }
                    }
                }
                val result = Snapshot(query(connection), frontiers)
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }
}
