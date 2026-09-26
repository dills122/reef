package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectEnvelope
import com.reef.platform.application.postmatch.LiveEffectBatchPlanner
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/** Applies bounded live facts inside the same transaction as coverage and frontier progress. */
class PostMatchLiveEffectWriter(private val planner: LiveEffectBatchPlanner = LiveEffectBatchPlanner()) {
    fun apply(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        val effects = window.outcomes.flatMap { it.effects }
        val plan = planner.plan(effects)
        val states = plan.finalOrderStates.associateBy { (it.effect as CanonicalEffect.OrderStateChanged).orderId }
        val referenced = linkedSetOf<String>()
        referenced += states.keys
        plan.executions.forEach { referenced += (it.effect as CanonicalEffect.Execution).orderId }
        plan.trades.forEach { envelope ->
            val trade = envelope.effect as CanonicalEffect.Trade
            referenced += trade.buyOrderId
            referenced += trade.sellOrderId
        }
        if (referenced.isEmpty()) return
        val identities = loadIdentities(connection, window, referenced)
        check(identities.keys == referenced) { "live effects reference an order without canonical identity" }
        val prior = loadPriorStates(connection, window, states.keys)
        val newOrders = effects.mapNotNull { (it.effect as? CanonicalEffect.Accepted)?.newOrder?.orderId }.toSet()
        check(states.keys.all { it in prior || it in newOrders }) { "live order state starts without an accepted submit" }
        check(plan.executions.all { (it.effect as CanonicalEffect.Execution).orderId in states }) {
            "execution lacks changed order state"
        }
        check(plan.trades.all { envelope ->
            val trade = envelope.effect as CanonicalEffect.Trade
            trade.buyOrderId in states && trade.sellOrderId in states
        }) { "trade lacks changed maker/taker state" }

        plan.finalOrderStates.forEach { envelope ->
            val state = envelope.effect as CanonicalEffect.OrderStateChanged
            val identity = identities.getValue(state.orderId)
            check(identity.acceptedBefore(envelope)) { "order state precedes canonical order acceptance" }
            check(identity.instrumentId == state.instrumentId && identity.side == state.side &&
                identity.currency == state.currency) { "live order state conflicts with canonical identity" }
            prior[state.orderId]?.let { old ->
                check(old.partitionId == envelope.position.partitionId &&
                    (old.sequence < envelope.position.streamSequence ||
                        old.sequence == envelope.position.streamSequence && old.ordinal < envelope.position.effectOrdinal)) {
                    "live order state position did not advance"
                }
            }
        }
        val filledThisWindow = mutableMapOf<String, BigDecimal>()
        plan.executions.forEach { envelope ->
            val execution = envelope.effect as CanonicalEffect.Execution
            val identity = identities.getValue(execution.orderId)
            check(identity.acceptedBefore(envelope)) { "execution precedes canonical order acceptance" }
            check(identity.instrumentId == execution.instrumentId && identity.currency == execution.currency) {
                "execution conflicts with canonical identity"
            }
            val quantity = positive(execution.quantityUnits)
            filledThisWindow.merge(execution.orderId, quantity, BigDecimal::add)
        }
        plan.trades.forEach { envelope ->
            val trade = envelope.effect as CanonicalEffect.Trade
            val buy = identities.getValue(trade.buyOrderId)
            val sell = identities.getValue(trade.sellOrderId)
            check(buy.acceptedBefore(envelope) && sell.acceptedBefore(envelope)) {
                "trade precedes canonical order acceptance"
            }
            check(buy.side == "BUY" && sell.side == "SELL" &&
                buy.instrumentId == trade.instrumentId && sell.instrumentId == trade.instrumentId &&
                buy.currency == trade.currency && sell.currency == trade.currency) {
                "trade conflicts with canonical order identities"
            }
            positive(trade.quantityUnits)
        }
        insertExecutions(connection, window, plan.executions)
        insertTrades(connection, window, plan.trades)
        upsertStates(connection, window, plan.finalOrderStates, prior, filledThisWindow)
    }

    private data class Identity(
        val instrumentId: String, val side: String, val currency: String,
        val acceptedSequence: Long, val acceptedOrdinal: Int
    ) {
        fun acceptedBefore(envelope: CanonicalEffectEnvelope): Boolean =
            acceptedSequence < envelope.position.streamSequence ||
                acceptedSequence == envelope.position.streamSequence && acceptedOrdinal < envelope.position.effectOrdinal
    }
    private data class PriorState(val filled: BigDecimal, val partitionId: Int, val sequence: Long, val ordinal: Int)

    private fun loadIdentities(
        connection: Connection, window: VerifiedCanonicalSourceWindow, orderIds: Set<String>
    ): Map<String, Identity> = connection.prepareStatement(
        """SELECT order_id, instrument_id, side, currency, source_stream_sequence, source_effect_ordinal
           FROM postmatch.canonical_order_directory
           WHERE event_stream = ? AND source_generation = ? AND source_partition_id = ? AND order_id = ANY (?)"""
    ).use { statement ->
        statement.setString(1, window.eventStream)
        statement.setString(2, window.sourceGeneration)
        statement.setInt(3, window.partitionId)
        val ids = connection.createArrayOf("text", orderIds.toTypedArray())
        try {
            statement.setArray(4, ids)
            statement.executeQuery().use { rows ->
                buildMap<String, Identity> {
                    while (rows.next()) put(rows.getString(1), Identity(
                        rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getInt(6)
                    ))
                }
            }
        } finally {
            ids.free()
        }
    }

    private fun loadPriorStates(
        connection: Connection, window: VerifiedCanonicalSourceWindow, orderIds: Set<String>
    ): Map<String, PriorState> {
        if (orderIds.isEmpty()) return emptyMap()
        return connection.prepareStatement(
            """SELECT order_id, filled_quantity, source_partition_id, source_stream_sequence, source_effect_ordinal
               FROM postmatch.live_order_state WHERE event_stream = ? AND source_generation = ? AND order_id = ANY (?)
               ORDER BY order_id FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            val ids = connection.createArrayOf("text", orderIds.toTypedArray())
            try {
                statement.setArray(3, ids)
                statement.executeQuery().use { rows ->
                    buildMap<String, PriorState> {
                        while (rows.next()) put(rows.getString(1), PriorState(
                            rows.getBigDecimal(2), rows.getInt(3), rows.getLong(4), rows.getInt(5)
                        ))
                    }
                }
            } finally {
                ids.free()
            }
        }
    }

    private fun insertExecutions(connection: Connection, window: VerifiedCanonicalSourceWindow, effects: List<CanonicalEffectEnvelope>) {
        connection.prepareStatement(
            """INSERT INTO postmatch.live_execution_facts(
               event_stream, source_generation, execution_id, event_id, order_id, instrument_id,
               quantity_units, execution_price, currency, liquidity_role, occurred_at,
               source_partition_id, source_stream_sequence, source_effect_ordinal)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            effects.forEach { envelope ->
                val effect = envelope.effect as CanonicalEffect.Execution
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, effect.executionId)
                statement.setString(4, effect.eventId)
                statement.setString(5, effect.orderId)
                statement.setString(6, effect.instrumentId)
                statement.setBigDecimal(7, positive(effect.quantityUnits))
                statement.setBigDecimal(8, nonnegative(effect.price))
                statement.setString(9, effect.currency)
                statement.setString(10, effect.liquidityRole)
                statement.setTimestamp(11, timestamp(effect.occurredAt))
                statement.setInt(12, envelope.position.partitionId)
                statement.setLong(13, envelope.position.streamSequence)
                statement.setInt(14, envelope.position.effectOrdinal)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertTrades(connection: Connection, window: VerifiedCanonicalSourceWindow, effects: List<CanonicalEffectEnvelope>) {
        connection.prepareStatement(
            """INSERT INTO postmatch.live_trade_facts(
               event_stream, source_generation, trade_id, event_id, execution_id, buy_order_id, sell_order_id,
               instrument_id, quantity_units, price, currency, occurred_at,
               source_partition_id, source_stream_sequence, source_effect_ordinal)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            effects.forEach { envelope ->
                val effect = envelope.effect as CanonicalEffect.Trade
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, effect.tradeId)
                statement.setString(4, effect.eventId)
                statement.setString(5, effect.executionId)
                statement.setString(6, effect.buyOrderId)
                statement.setString(7, effect.sellOrderId)
                statement.setString(8, effect.instrumentId)
                statement.setBigDecimal(9, positive(effect.quantityUnits))
                statement.setBigDecimal(10, nonnegative(effect.price))
                statement.setString(11, effect.currency)
                statement.setTimestamp(12, timestamp(effect.occurredAt))
                statement.setInt(13, envelope.position.partitionId)
                statement.setLong(14, envelope.position.streamSequence)
                statement.setInt(15, envelope.position.effectOrdinal)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertStates(
        connection: Connection, window: VerifiedCanonicalSourceWindow, effects: List<CanonicalEffectEnvelope>,
        prior: Map<String, PriorState>, filledThisWindow: Map<String, BigDecimal>
    ) {
        connection.prepareStatement(
            """INSERT INTO postmatch.live_order_state AS current_state(
               event_stream, source_generation, order_id, instrument_id, status, original_quantity,
               remaining_quantity, filled_quantity, limit_price, currency, last_event_at,
               source_partition_id, source_stream_sequence, source_effect_ordinal)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (event_stream, source_generation, order_id) DO UPDATE SET
               status = EXCLUDED.status, original_quantity = EXCLUDED.original_quantity,
               remaining_quantity = EXCLUDED.remaining_quantity, filled_quantity = EXCLUDED.filled_quantity,
               limit_price = EXCLUDED.limit_price, last_event_at = EXCLUDED.last_event_at,
               source_stream_sequence = EXCLUDED.source_stream_sequence,
               source_effect_ordinal = EXCLUDED.source_effect_ordinal, updated_at = clock_timestamp()
               WHERE current_state.source_partition_id = EXCLUDED.source_partition_id
                 AND (current_state.source_stream_sequence, current_state.source_effect_ordinal)
                   < (EXCLUDED.source_stream_sequence, EXCLUDED.source_effect_ordinal)"""
        ).use { statement ->
            effects.forEach { envelope ->
                val effect = envelope.effect as CanonicalEffect.OrderStateChanged
                val original = nonnegative(effect.originalQuantity)
                val remaining = nonnegative(effect.remainingQuantity)
                val filled = (prior[effect.orderId]?.filled ?: BigDecimal.ZERO) +
                    (filledThisWindow[effect.orderId] ?: BigDecimal.ZERO)
                check(filled + remaining <= original) { "live order quantities exceed original quantity" }
                check(effect.status == "CANCELLED" || (filled + remaining).compareTo(original) == 0) {
                    "active live order quantities conflict with canonical executions"
                }
                check(when (effect.status) {
                    "ACCEPTED" -> filled.signum() == 0 && remaining.compareTo(original) == 0
                    "PARTIALLY_FILLED" -> filled.signum() > 0 && remaining.signum() > 0
                    "FILLED" -> filled.compareTo(original) == 0 && remaining.signum() == 0
                    "CANCELLED" -> remaining.signum() == 0
                    else -> false
                }) { "live order status conflicts with canonical quantities" }
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, effect.orderId)
                statement.setString(4, effect.instrumentId)
                statement.setString(5, effect.status)
                statement.setBigDecimal(6, original)
                statement.setBigDecimal(7, remaining)
                statement.setBigDecimal(8, filled)
                statement.setBigDecimal(9, nonnegative(effect.limitPrice))
                statement.setString(10, effect.currency)
                statement.setTimestamp(11, timestamp(effect.occurredAt))
                statement.setInt(12, envelope.position.partitionId)
                statement.setLong(13, envelope.position.streamSequence)
                statement.setInt(14, envelope.position.effectOrdinal)
                statement.addBatch()
            }
            check(statement.executeBatch().all { it == 1 }) { "live order state position did not advance" }
        }
    }

    private fun nonnegative(value: String): BigDecimal = BigDecimal(value).also {
        require(it.signum() >= 0) { "negative canonical quantity or price" }
    }

    private fun positive(value: String): BigDecimal = nonnegative(value).also {
        require(it.signum() > 0) { "zero canonical execution or trade quantity" }
    }

    private fun timestamp(value: String): Timestamp = Timestamp.from(Instant.parse(value))
}
