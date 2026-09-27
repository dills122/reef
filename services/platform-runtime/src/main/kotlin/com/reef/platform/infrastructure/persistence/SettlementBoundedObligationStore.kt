package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.settlement.PostTradeProfileSelection
import com.reef.platform.application.settlement.PostTradeProfileSelectionSource
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class SettlementObligationWindowTooLarge : IllegalArgumentException("settlement obligation trade window exceeds configured bound")

class SettlementObligationWindow internal constructor(
    val eventStream: String,
    val sourceGeneration: String,
    val partitionId: Int,
    val fromExclusiveSequence: Long,
    val throughInclusiveSequence: Long,
    val trades: List<SettlementIntakeTrade>,
    val tradeDigest: String
)

/** Bounded obligation projection from committed canonical trade intake. */
class SettlementBoundedObligationStore(private val dataSource: DataSource) {
    fun readNextWindow(
        eventStream: String, partitionId: Int, sourceGeneration: String,
        maxSourcePositions: Int = 500, maxTrades: Int = 1000
    ): SettlementObligationWindow? {
        require(eventStream.isNotBlank() && sourceGeneration.isNotBlank() && partitionId in 0..32767)
        require(maxSourcePositions in 1..5000 && maxTrades in 1..20_000)
        return dataSource.connection.use { connection ->
            val origin = CanonicalStreamPosition.origin(partitionId)
            val intakeHead = frontier(connection, "canonical_intake_frontiers", eventStream, partitionId)
                ?: return@use null
            check(intakeHead.first == sourceGeneration) { "settlement intake source generation changed" }
            val obligationHead = frontier(connection, "canonical_obligation_frontiers", eventStream, partitionId)
            check(obligationHead == null || obligationHead.first == sourceGeneration) {
                "settlement obligation source generation changed"
            }
            val from = obligationHead?.second ?: origin
            check(from >= origin && from <= intakeHead.second) { "settlement obligation frontier exceeds intake" }
            if (from == intakeHead.second) return@use null
            val through = minOf(from + maxSourcePositions, intakeHead.second)
            val trades = readTrades(connection, eventStream, partitionId, sourceGeneration,
                from, through, maxTrades + 1)
            if (trades.size > maxTrades) throw SettlementObligationWindowTooLarge()
            SettlementIntakeManifest.verify(connection, eventStream, sourceGeneration, partitionId, from, through, trades)
            SettlementObligationWindow(eventStream, sourceGeneration, partitionId, from, through,
                trades, SettlementIntakeManifest.digest(trades))
        }
    }

    internal fun readTrades(
        connection: Connection, eventStream: String, partitionId: Int, sourceGeneration: String,
        from: Long, through: Long, limit: Int
    ): List<SettlementIntakeTrade> = connection.prepareStatement(
                """SELECT stream_sequence, effect_ordinal, trade_id, event_id,
                          execution_id, buy_order_id, sell_order_id, run_id, venue_session_id,
                          buyer_participant_id, seller_participant_id, buyer_account_id, seller_account_id,
                          instrument_id, quantity_units, price, currency, occurred_at_text
                   FROM settlement.canonical_trade_intake
                   WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                     AND stream_sequence > ? AND stream_sequence <= ?
                   ORDER BY stream_sequence, effect_ordinal LIMIT ?"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setString(2, sourceGeneration)
                statement.setInt(3, partitionId)
                statement.setLong(4, from)
                statement.setLong(5, through)
                statement.setInt(6, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(SettlementIntakeTrade(
                            streamSequence = rows.getLong(1), effectOrdinal = rows.getInt(2),
                            tradeId = rows.getString(3), eventId = rows.getString(4),
                            executionId = rows.getString(5), buyOrderId = rows.getString(6),
                            sellOrderId = rows.getString(7), runId = rows.getString(8),
                            venueSessionId = rows.getString(9),
                            buyerParticipantId = rows.getString(10), sellerParticipantId = rows.getString(11),
                            buyerAccountId = rows.getString(12), sellerAccountId = rows.getString(13),
                            instrumentId = rows.getString(14), quantityUnits = rows.getString(15),
                            price = rows.getString(16), currency = rows.getString(17),
                            occurredAt = rows.getString(18)
                        ))
                    }
                }
            }

    fun apply(
        window: SettlementObligationWindow,
        selections: Map<SettlementPolicyKey, SettlementPolicySnapshot>
    ): PostMatchApplyResult = dataSource.connection.use { connection ->
        val keys = window.trades.mapTo(mutableSetOf()) { it.policyKey }
        require(selections.keys == keys) { "settlement obligation policies do not cover exact trade keys" }
        require(selections.values.all { it.selection.profileId.isNotBlank() && it.selection.policyVersion > 0 &&
            it.selection.mode.isNotBlank() && it.settlementCycle.isNotBlank() &&
            it.nettingMode.isNotBlank() && it.ledgerPostingMode.isNotBlank() }) {
            "settlement obligation policy selection is invalid"
        }
        require(window.fromExclusiveSequence >= CanonicalStreamPosition.origin(window.partitionId) &&
            window.throughInclusiveSequence > window.fromExclusiveSequence &&
            window.throughInclusiveSequence - window.fromExclusiveSequence <= 5000)
        check(window.tradeDigest == SettlementIntakeManifest.digest(window.trades)) {
            "settlement obligation window digest changed"
        }
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            initializeOrigin(connection, window)
            val (generation, frontier) = lockFrontier(connection, window)
            check(generation == window.sourceGeneration) { "settlement obligation source generation changed" }
            val intake = frontier(connection, "canonical_intake_frontiers", window.eventStream, window.partitionId)
                ?: error("settlement intake frontier is absent")
            check(intake.first == window.sourceGeneration && intake.second >= window.throughInclusiveSequence) {
                "settlement obligation window exceeds verified intake"
            }
            val currentTrades = readTrades(connection, window.eventStream, window.partitionId,
                window.sourceGeneration, window.fromExclusiveSequence, window.throughInclusiveSequence,
                window.trades.size + 1)
            check(currentTrades == window.trades) { "settlement intake changed before obligation commit" }
            SettlementIntakeManifest.verify(connection, window.eventStream, window.sourceGeneration,
                window.partitionId, window.fromExclusiveSequence, window.throughInclusiveSequence, currentTrades)
            val result = when {
                frontier == window.fromExclusiveSequence -> {
                    insertBindings(connection, window, selections)
                    verifyBindings(connection, window, selections)
                    insertObligations(connection, window, selections)
                    insertCoverage(connection, window)
                    advanceFrontier(connection, window)
                    PostMatchApplyResult.APPLIED
                }
                window.throughInclusiveSequence <= frontier -> {
                    verifyBindings(connection, window, selections)
                    verifyReplay(connection, window, selections)
                    PostMatchApplyResult.DUPLICATE
                }
                else -> error("settlement obligation window overlaps or skips committed frontier")
            }
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun frontier(connection: Connection, table: String, stream: String, partition: Int): Pair<String, Long>? =
        connection.prepareStatement(
            "SELECT source_generation, last_stream_sequence FROM settlement.$table WHERE event_stream = ? AND partition_id = ?"
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getLong(2) else null
            }
        }

    private fun initializeOrigin(connection: Connection, window: SettlementObligationWindow) {
        if (window.fromExclusiveSequence != CanonicalStreamPosition.origin(window.partitionId)) return
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_obligation_frontiers(
                 event_stream, partition_id, source_generation, last_stream_sequence
               ) VALUES (?, ?, ?, ?) ON CONFLICT (event_stream, partition_id) DO NOTHING"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setString(3, window.sourceGeneration)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.executeUpdate()
        }
    }

    private fun lockFrontier(connection: Connection, window: SettlementObligationWindow): Pair<String, Long> =
        connection.prepareStatement(
            """SELECT source_generation, last_stream_sequence FROM settlement.canonical_obligation_frontiers
               WHERE event_stream = ? AND partition_id = ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "settlement obligation origin is not initialized" }
                rows.getString(1) to rows.getLong(2)
            }
        }

    private fun insertBindings(
        connection: Connection, window: SettlementObligationWindow,
        selections: Map<SettlementPolicyKey, SettlementPolicySnapshot>
    ) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_policy_bindings(
                 event_stream, source_generation, run_id, venue_session_id,
                 post_trade_profile_id, post_trade_policy_version, post_trade_mode,
                 settlement_cycle, netting_mode, ledger_posting_mode, selection_source
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (event_stream, source_generation, run_id, venue_session_id) DO NOTHING"""
        ).use { statement ->
            selections.toSortedMap(compareBy<SettlementPolicyKey> { it.runId }.thenBy { it.venueSessionId })
                .forEach { (key, selection) ->
                    statement.setString(1, window.eventStream)
                    statement.setString(2, window.sourceGeneration)
                    statement.setString(3, key.runId)
                    statement.setString(4, key.venueSessionId)
                    statement.setString(5, selection.selection.profileId)
                    statement.setInt(6, selection.selection.policyVersion)
                    statement.setString(7, selection.selection.mode)
                    statement.setString(8, selection.settlementCycle)
                    statement.setString(9, selection.nettingMode)
                    statement.setString(10, selection.ledgerPostingMode)
                    statement.setString(11, selection.selection.source.name)
                    statement.addBatch()
                }
            statement.executeBatch()
        }
    }

    private fun verifyBindings(
        connection: Connection, window: SettlementObligationWindow,
        selections: Map<SettlementPolicyKey, SettlementPolicySnapshot>
    ) {
        if (selections.isEmpty()) return
        val keys = selections.keys.toList()
        val stored = connection.prepareStatement(
            """SELECT binding.run_id, binding.venue_session_id, binding.post_trade_profile_id,
                      binding.post_trade_policy_version, binding.post_trade_mode,
                      binding.settlement_cycle, binding.netting_mode, binding.ledger_posting_mode,
                      binding.selection_source
               FROM settlement.canonical_policy_bindings binding
               JOIN unnest(?::text[], ?::text[]) AS keys(run_id, venue_session_id)
                 ON keys.run_id = binding.run_id AND keys.venue_session_id = binding.venue_session_id
               WHERE binding.event_stream = ? AND binding.source_generation = ?"""
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("text", keys.map { it.runId }.toTypedArray()))
            statement.setArray(2, connection.createArrayOf("text", keys.map { it.venueSessionId }.toTypedArray()))
            statement.setString(3, window.eventStream)
            statement.setString(4, window.sourceGeneration)
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val key = SettlementPolicyKey(rows.getString(1), rows.getString(2))
                        val selection = PostTradeProfileSelection(
                            profileId = rows.getString(3), policyVersion = rows.getInt(4),
                            mode = rows.getString(5), source = PostTradeProfileSelectionSource.valueOf(rows.getString(9))
                        )
                        val snapshot = SettlementPolicySnapshot(selection, rows.getString(6),
                            rows.getString(7), rows.getString(8))
                        check(put(key, snapshot) == null) { "settlement policy binding is duplicated" }
                    }
                }
            }
        }
        check(stored == selections) { "settlement policy binding drifted from its first trade" }
    }

    private fun insertObligations(
        connection: Connection, window: SettlementObligationWindow,
        selections: Map<SettlementPolicyKey, SettlementPolicySnapshot>
    ) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_settlement_obligations(
                 event_stream, source_generation, partition_id, stream_sequence, effect_ordinal,
                 trade_id, event_id, run_id, venue_session_id, post_trade_profile_id,
                 post_trade_policy_version, post_trade_mode, settlement_cycle, netting_mode,
                 ledger_posting_mode, selection_source,
                 buyer_participant_id, seller_participant_id, buyer_account_id, seller_account_id,
                 instrument_id, quantity_units, cash_amount, currency, occurred_at
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            window.trades.forEach { trade ->
                val policy = selections.getValue(trade.policyKey)
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setInt(3, window.partitionId)
                statement.setLong(4, trade.streamSequence)
                statement.setInt(5, trade.effectOrdinal)
                statement.setString(6, trade.tradeId)
                statement.setString(7, trade.eventId)
                statement.setString(8, trade.runId)
                statement.setString(9, trade.venueSessionId)
                statement.setString(10, policy.selection.profileId)
                statement.setInt(11, policy.selection.policyVersion)
                statement.setString(12, policy.selection.mode)
                statement.setString(13, policy.settlementCycle)
                statement.setString(14, policy.nettingMode)
                statement.setString(15, policy.ledgerPostingMode)
                statement.setString(16, policy.selection.source.name)
                statement.setString(17, trade.buyerParticipantId)
                statement.setString(18, trade.sellerParticipantId)
                statement.setString(19, trade.buyerAccountId)
                statement.setString(20, trade.sellerAccountId)
                statement.setString(21, trade.instrumentId)
                statement.setBigDecimal(22, BigDecimal(trade.quantityUnits))
                statement.setBigDecimal(23, BigDecimal(trade.cashAmount()))
                statement.setString(24, trade.currency)
                statement.setObject(25, OffsetDateTime.ofInstant(Instant.parse(trade.occurredAt), ZoneOffset.UTC))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertCoverage(connection: Connection, window: SettlementObligationWindow) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_obligation_coverage(
                 event_stream, partition_id, source_generation, from_exclusive_sequence,
                 through_inclusive_sequence, trade_count, trade_digest
               ) VALUES (?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setString(3, window.sourceGeneration)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.setInt(6, window.trades.size)
            statement.setString(7, window.tradeDigest)
            check(statement.executeUpdate() == 1) { "settlement obligation coverage was not recorded" }
        }
    }

    private fun advanceFrontier(connection: Connection, window: SettlementObligationWindow) {
        connection.prepareStatement(
            """UPDATE settlement.canonical_obligation_frontiers
               SET last_stream_sequence = ?, updated_at = clock_timestamp()
               WHERE event_stream = ? AND partition_id = ? AND source_generation = ?
                 AND last_stream_sequence = ?"""
        ).use { statement ->
            statement.setLong(1, window.throughInclusiveSequence)
            statement.setString(2, window.eventStream)
            statement.setInt(3, window.partitionId)
            statement.setString(4, window.sourceGeneration)
            statement.setLong(5, window.fromExclusiveSequence)
            check(statement.executeUpdate() == 1) { "settlement obligation frontier changed before commit" }
        }
    }

    private fun verifyReplay(
        connection: Connection, window: SettlementObligationWindow,
        selections: Map<SettlementPolicyKey, SettlementPolicySnapshot>
    ) {
        connection.prepareStatement(
            """SELECT source_generation, from_exclusive_sequence, trade_count, trade_digest
               FROM settlement.canonical_obligation_coverage
               WHERE event_stream = ? AND partition_id = ? AND through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setLong(3, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == window.sourceGeneration &&
                    rows.getLong(2) == window.fromExclusiveSequence &&
                    rows.getInt(3) == window.trades.size && rows.getString(4) == window.tradeDigest) {
                    "settlement obligation replay coverage conflict"
                }
            }
        }
        connection.prepareStatement(
            """SELECT stream_sequence, effect_ordinal, trade_id, event_id, run_id, venue_session_id,
                      post_trade_profile_id, post_trade_policy_version, post_trade_mode,
                      settlement_cycle, netting_mode, ledger_posting_mode, selection_source,
                      buyer_participant_id, seller_participant_id, buyer_account_id, seller_account_id,
                      instrument_id, quantity_units, cash_amount, currency, occurred_at
               FROM settlement.canonical_settlement_obligations
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND stream_sequence > ? AND stream_sequence <= ?
               ORDER BY stream_sequence, effect_ordinal"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                window.trades.forEach { trade ->
                    val policy = selections.getValue(trade.policyKey)
                    check(rows.next() && rows.getLong(1) == trade.streamSequence &&
                        rows.getInt(2) == trade.effectOrdinal && rows.getString(3) == trade.tradeId &&
                        rows.getString(4) == trade.eventId && rows.getString(5) == trade.runId &&
                        rows.getString(6) == trade.venueSessionId &&
                        rows.getString(7) == policy.selection.profileId &&
                        rows.getInt(8) == policy.selection.policyVersion &&
                        rows.getString(9) == policy.selection.mode &&
                        rows.getString(10) == policy.settlementCycle &&
                        rows.getString(11) == policy.nettingMode &&
                        rows.getString(12) == policy.ledgerPostingMode &&
                        rows.getString(13) == policy.selection.source.name &&
                        rows.getString(14) == trade.buyerParticipantId &&
                        rows.getString(15) == trade.sellerParticipantId &&
                        rows.getString(16) == trade.buyerAccountId &&
                        rows.getString(17) == trade.sellerAccountId &&
                        rows.getString(18) == trade.instrumentId &&
                        rows.getBigDecimal(19).compareTo(BigDecimal(trade.quantityUnits)) == 0 &&
                        rows.getBigDecimal(20).compareTo(BigDecimal(trade.cashAmount())) == 0 &&
                        rows.getString(21) == trade.currency &&
                        rows.getObject(22, OffsetDateTime::class.java).toInstant() == Instant.parse(trade.occurredAt)) {
                        "settlement obligation replay fact conflict"
                    }
                }
                check(!rows.next()) { "settlement obligation replay has extra facts" }
            }
        }
    }

}
