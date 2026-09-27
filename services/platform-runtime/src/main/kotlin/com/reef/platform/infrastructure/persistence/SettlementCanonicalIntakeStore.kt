package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectEnvelope
import com.reef.platform.application.postmatch.CanonicalOrderIdentity
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import java.sql.Connection
import javax.sql.DataSource

/** Settlement source authority. All intake rows and contiguous progress share one transaction. */
class SettlementIntakeWindowTooLarge : IllegalArgumentException("settlement intake effect window exceeds configured bound")

class SettlementCanonicalIntakeStore(
    private val dataSource: DataSource,
    private val maxWindowEffects: Int = 20_000
) {
    init { require(maxWindowEffects > 0) }
    fun lastCommittedSequence(eventStream: String, partitionId: Int, generation: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT source_generation, last_stream_sequence FROM settlement.canonical_intake_frontiers
                   WHERE event_stream = ? AND partition_id = ?"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setInt(2, partitionId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) CanonicalStreamPosition.origin(partitionId) else {
                        check(rows.getString(1) == generation) { "settlement source generation changed" }
                        rows.getLong(2)
                    }
                }
            }
        }

    fun apply(window: VerifiedCanonicalSourceWindow): PostMatchApplyResult = dataSource.connection.use { connection ->
        require(window.consumerName == CONSUMER) { "settlement window belongs to another consumer" }
        if (window.outcomes.sumOf { it.effects.size.toLong() } > maxWindowEffects) {
            throw SettlementIntakeWindowTooLarge()
        }
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            initializeOrigin(connection, window)
            val (generation, frontier) = lockFrontier(connection, window)
            check(generation == window.sourceGeneration) { "settlement source generation changed" }
            val result = when {
                frontier == window.fromExclusiveSequence -> {
                    val trades = applyEffects(connection, window)
                    insertReceipts(connection, window, trades)
                    insertCoverage(connection, window)
                    advanceFrontier(connection, window)
                    PostMatchApplyResult.APPLIED
                }
                window.throughInclusiveSequence <= frontier -> {
                    verifyReplay(connection, window)
                    PostMatchApplyResult.DUPLICATE
                }
                else -> error("settlement source window overlaps or skips committed frontier")
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

    private fun initializeOrigin(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        val origin = CanonicalStreamPosition.origin(window.partitionId)
        if (window.fromExclusiveSequence != origin) return
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_intake_frontiers(
                 event_stream, partition_id, source_generation, last_stream_sequence
               ) VALUES (?, ?, ?, ?) ON CONFLICT (event_stream, partition_id) DO NOTHING"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setString(3, window.sourceGeneration)
            statement.setLong(4, origin)
            statement.executeUpdate()
        }
    }

    private fun lockFrontier(connection: Connection, window: VerifiedCanonicalSourceWindow): Pair<String, Long> =
        connection.prepareStatement(
            """SELECT source_generation, last_stream_sequence FROM settlement.canonical_intake_frontiers
               WHERE event_stream = ? AND partition_id = ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "settlement source origin has not been verified and initialized" }
                rows.getString(1) to rows.getLong(2)
            }
        }

    private data class Owner(
        val orderId: String,
        val runId: String,
        val venueSessionId: String,
        val instrumentId: String,
        val participantId: String,
        val accountId: String,
        val side: String,
        val currency: String,
        val partitionId: Int,
        val sequence: Long,
        val ordinal: Int
    )

    private fun owner(identity: CanonicalOrderIdentity, effect: CanonicalEffectEnvelope) = Owner(
        identity.orderId, identity.runId, identity.venueSessionId, identity.instrumentId,
        identity.participantId, identity.accountId, identity.side, identity.currency,
        effect.position.partitionId, effect.position.streamSequence, effect.position.effectOrdinal
    )

    private fun intakeTrade(
        effect: CanonicalEffectEnvelope, trade: CanonicalEffect.Trade, buyer: Owner, seller: Owner
    ) = SettlementIntakeTrade(
        streamSequence = effect.position.streamSequence, effectOrdinal = effect.position.effectOrdinal,
        tradeId = trade.tradeId, eventId = trade.eventId,
        executionId = trade.executionId, buyOrderId = trade.buyOrderId,
        sellOrderId = trade.sellOrderId,
        runId = buyer.runId.ifBlank { seller.runId }, venueSessionId = buyer.venueSessionId,
        buyerParticipantId = buyer.participantId, sellerParticipantId = seller.participantId,
        buyerAccountId = buyer.accountId, sellerAccountId = seller.accountId,
        instrumentId = trade.instrumentId, quantityUnits = trade.quantityUnits,
        price = trade.price, currency = trade.currency, occurredAt = trade.occurredAt
    )

    private fun applyEffects(connection: Connection, window: VerifiedCanonicalSourceWindow): List<SettlementIntakeTrade> {
        val effects = window.outcomes.flatMap { it.effects }
        val tradeEffects = effects.filter { it.effect is CanonicalEffect.Trade }
        val ids = tradeEffects.flatMap { effect ->
            val trade = effect.effect as CanonicalEffect.Trade
            listOf(trade.buyOrderId, trade.sellOrderId)
        }.toSet()
        val owners = loadOwners(connection, window, ids).toMutableMap()
        val accepted = effects.mapNotNull { effect ->
            val identity = (effect.effect as? CanonicalEffect.Accepted)?.newOrder ?: return@mapNotNull null
            owner(identity, effect).also { newOwner ->
                check(owners.putIfAbsent(identity.orderId, newOwner) == null) {
                    "settlement canonical order identity repeated"
                }
            }
        }
        insertOwners(connection, window, accepted)
        val intakeTrades = mutableListOf<SettlementIntakeTrade>()
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_trade_intake(
                 event_stream, source_generation, partition_id, stream_sequence, effect_ordinal,
                 event_id, trade_id, execution_id, run_id, venue_session_id,
                 buy_order_id, sell_order_id, buyer_participant_id, seller_participant_id,
                 buyer_account_id, seller_account_id, instrument_id, quantity_units, price,
                 currency, occurred_at_text
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            tradeEffects.forEach { envelope ->
                val trade = envelope.effect as CanonicalEffect.Trade
                val buyer = checkNotNull(owners[trade.buyOrderId]) { "settlement buyer ownership is missing" }
                val seller = checkNotNull(owners[trade.sellOrderId]) { "settlement seller ownership is missing" }
                check(buyer.side == "BUY" && seller.side == "SELL") { "settlement trade sides conflict with order ownership" }
                check(buyer.instrumentId == trade.instrumentId && seller.instrumentId == trade.instrumentId &&
                    buyer.currency == trade.currency && seller.currency == trade.currency) {
                    "settlement trade instrument or currency conflicts with order ownership"
                }
                check(buyer.venueSessionId == seller.venueSessionId) { "settlement trade crosses venue sessions" }
                check(buyer.partitionId == envelope.position.partitionId &&
                    seller.partitionId == envelope.position.partitionId) {
                    "settlement trade ownership crosses partitions without a causal proof"
                }
                check(buyer.runId.isBlank() || seller.runId.isBlank() || buyer.runId == seller.runId) {
                    "settlement trade crosses scenario runs"
                }
                listOf(buyer, seller).forEach { owner ->
                    check(owner.sequence < envelope.position.streamSequence ||
                        owner.sequence == envelope.position.streamSequence && owner.ordinal < envelope.position.effectOrdinal) {
                        "settlement ownership follows its trade"
                    }
                }
                check(trade.quantityUnits.toBigDecimalOrNull()?.signum() == 1) {
                    "settlement trade quantity must be positive numeric"
                }
                check(trade.price.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true) {
                    "settlement trade price must be nonnegative numeric"
                }
                intakeTrades += intakeTrade(envelope, trade, buyer, seller)
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setInt(3, envelope.position.partitionId)
                statement.setLong(4, envelope.position.streamSequence)
                statement.setInt(5, envelope.position.effectOrdinal)
                statement.setString(6, trade.eventId)
                statement.setString(7, trade.tradeId)
                statement.setString(8, trade.executionId)
                statement.setString(9, buyer.runId.ifBlank { seller.runId })
                statement.setString(10, buyer.venueSessionId)
                statement.setString(11, trade.buyOrderId)
                statement.setString(12, trade.sellOrderId)
                statement.setString(13, buyer.participantId)
                statement.setString(14, seller.participantId)
                statement.setString(15, buyer.accountId)
                statement.setString(16, seller.accountId)
                statement.setString(17, trade.instrumentId)
                statement.setString(18, trade.quantityUnits)
                statement.setString(19, trade.price)
                statement.setString(20, trade.currency)
                statement.setString(21, trade.occurredAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        return intakeTrades
    }

    private fun loadOwners(connection: Connection, window: VerifiedCanonicalSourceWindow, ids: Set<String>): Map<String, Owner> {
        if (ids.isEmpty()) return emptyMap()
        return connection.prepareStatement(
            """SELECT order_id, run_id, venue_session_id, instrument_id, participant_id,
                      account_id, side, currency, source_partition_id, source_stream_sequence,
                      source_effect_ordinal
               FROM settlement.canonical_order_directory
               WHERE event_stream = ? AND source_generation = ? AND order_id = ANY(?)"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setArray(3, connection.createArrayOf("text", ids.toTypedArray()))
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val owner = Owner(
                            rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                            rows.getInt(9), rows.getLong(10), rows.getInt(11)
                        )
                        check(put(owner.orderId, owner) == null) {
                            "settlement replay ownership conflict"
                        }
                    }
                }
            }
        }
    }

    private fun insertOwners(connection: Connection, window: VerifiedCanonicalSourceWindow, owners: List<Owner>) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_order_directory(
                 event_stream, source_generation, order_id, run_id, venue_session_id,
                 instrument_id, participant_id, account_id, side, currency,
                 source_partition_id, source_stream_sequence, source_effect_ordinal
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            owners.forEach { owner ->
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, owner.orderId)
                statement.setString(4, owner.runId)
                statement.setString(5, owner.venueSessionId)
                statement.setString(6, owner.instrumentId)
                statement.setString(7, owner.participantId)
                statement.setString(8, owner.accountId)
                statement.setString(9, owner.side)
                statement.setString(10, owner.currency)
                statement.setInt(11, owner.partitionId)
                statement.setLong(12, owner.sequence)
                statement.setInt(13, owner.ordinal)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertReceipts(
        connection: Connection, window: VerifiedCanonicalSourceWindow, trades: List<SettlementIntakeTrade>
    ) {
        val tradesBySequence = trades.groupBy { it.streamSequence }
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_intake_receipts(
                 event_stream, source_generation, partition_id, stream_sequence, batch_id,
                 command_id, command_payload_hash, result_digest, effect_count, trade_count, trade_digest
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            window.outcomes.forEach { outcome ->
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setInt(3, window.partitionId)
                statement.setLong(4, outcome.source.streamSequence)
                statement.setString(5, outcome.source.batchId)
                statement.setString(6, outcome.source.commandId)
                statement.setString(7, outcome.source.payloadHash)
                statement.setString(8, outcome.resultDigest)
                statement.setInt(9, outcome.effects.size)
                val members = tradesBySequence[outcome.source.streamSequence].orEmpty()
                statement.setInt(10, members.size)
                statement.setString(11, if (members.isEmpty()) SettlementIntakeManifest.emptyDigest
                    else SettlementIntakeManifest.digest(members))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertCoverage(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_intake_coverage(
                 event_stream, partition_id, source_generation, from_exclusive_sequence,
                 through_inclusive_sequence, source_member_count, source_digest
               ) VALUES (?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setString(3, window.sourceGeneration)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.setInt(6, window.outcomes.size)
            statement.setString(7, window.sourceDigest)
            check(statement.executeUpdate() == 1) { "settlement source coverage was not recorded" }
        }
    }

    private fun advanceFrontier(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """UPDATE settlement.canonical_intake_frontiers
               SET last_stream_sequence = ?, last_coverage_digest = ?, updated_at = clock_timestamp()
               WHERE event_stream = ? AND partition_id = ? AND source_generation = ?
                 AND last_stream_sequence = ?"""
        ).use { statement ->
            statement.setLong(1, window.throughInclusiveSequence)
            statement.setString(2, window.sourceDigest)
            statement.setString(3, window.eventStream)
            statement.setInt(4, window.partitionId)
            statement.setString(5, window.sourceGeneration)
            statement.setLong(6, window.fromExclusiveSequence)
            check(statement.executeUpdate() == 1) { "settlement frontier changed before commit" }
        }
    }

    private fun verifyReplay(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """SELECT source_generation, from_exclusive_sequence, source_member_count, source_digest
               FROM settlement.canonical_intake_coverage
               WHERE event_stream = ? AND partition_id = ? AND through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setLong(3, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == window.sourceGeneration &&
                    rows.getLong(2) == window.fromExclusiveSequence &&
                    rows.getInt(3) == window.outcomes.size && rows.getString(4) == window.sourceDigest) {
                    "settlement replay coverage conflict"
                }
            }
        }
        connection.prepareStatement(
            """SELECT stream_sequence, batch_id, command_id, command_payload_hash, result_digest, effect_count
               FROM settlement.canonical_intake_receipts
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND stream_sequence > ? AND stream_sequence <= ? ORDER BY stream_sequence"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                window.outcomes.forEach { outcome ->
                    check(rows.next() && rows.getLong(1) == outcome.source.streamSequence &&
                        rows.getString(2) == outcome.source.batchId && rows.getString(3) == outcome.source.commandId &&
                        rows.getString(4) == outcome.source.payloadHash && rows.getString(5) == outcome.resultDigest &&
                        rows.getInt(6) == outcome.effects.size) { "settlement replay receipt conflict" }
                }
                check(!rows.next()) { "settlement replay has extra receipts" }
            }
        }
        val accepted = window.outcomes.flatMap { it.effects }.mapNotNull { envelope ->
            val identity = (envelope.effect as? CanonicalEffect.Accepted)?.newOrder ?: return@mapNotNull null
            owner(identity, envelope)
        }
        val storedOwners = loadOwners(connection, window, accepted.map { it.orderId }.toSet())
        check(accepted.all { storedOwners[it.orderId] == it }) { "settlement replay ownership conflict" }
        val trades = window.outcomes.flatMap { it.effects }.filter { it.effect is CanonicalEffect.Trade }
        val tradeOwnerIds = trades.flatMap { envelope ->
            val trade = envelope.effect as CanonicalEffect.Trade
            listOf(trade.buyOrderId, trade.sellOrderId)
        }.toSet()
        val tradeOwners = loadOwners(connection, window, tradeOwnerIds)
        val replayTrades = mutableListOf<SettlementIntakeTrade>()
        connection.prepareStatement(
            """SELECT stream_sequence, effect_ordinal, trade_id, event_id, execution_id,
                      run_id, venue_session_id, buy_order_id, sell_order_id,
                      buyer_participant_id, seller_participant_id, buyer_account_id, seller_account_id,
                      instrument_id, quantity_units, price, currency, occurred_at_text
               FROM settlement.canonical_trade_intake
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND stream_sequence > ? AND stream_sequence <= ? ORDER BY stream_sequence, effect_ordinal"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                trades.forEach { envelope ->
                    val trade = envelope.effect as CanonicalEffect.Trade
                    val buyer = checkNotNull(tradeOwners[trade.buyOrderId]) { "settlement replay buyer ownership is missing" }
                    val seller = checkNotNull(tradeOwners[trade.sellOrderId]) { "settlement replay seller ownership is missing" }
                    check(rows.next() && rows.getLong(1) == envelope.position.streamSequence &&
                        rows.getInt(2) == envelope.position.effectOrdinal && rows.getString(3) == trade.tradeId &&
                        rows.getString(4) == trade.eventId && rows.getString(5) == trade.executionId &&
                        rows.getString(6) == buyer.runId.ifBlank { seller.runId } &&
                        rows.getString(7) == buyer.venueSessionId &&
                        rows.getString(8) == trade.buyOrderId && rows.getString(9) == trade.sellOrderId &&
                        rows.getString(10) == buyer.participantId && rows.getString(11) == seller.participantId &&
                        rows.getString(12) == buyer.accountId && rows.getString(13) == seller.accountId &&
                        rows.getString(14) == trade.instrumentId &&
                        rows.getString(15) == trade.quantityUnits && rows.getString(16) == trade.price &&
                        rows.getString(17) == trade.currency && rows.getString(18) == trade.occurredAt) {
                        "settlement replay trade conflict"
                    }
                    replayTrades += intakeTrade(envelope, trade, buyer, seller)
                }
                check(!rows.next()) { "settlement replay has extra trades" }
            }
        }
        SettlementIntakeManifest.verify(connection, window.eventStream, window.sourceGeneration,
            window.partitionId, window.fromExclusiveSequence, window.throughInclusiveSequence, replayTrades)
    }

    companion object { const val CONSUMER = "settlement-intake-v1" }
}
