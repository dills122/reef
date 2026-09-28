package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

enum class MatchingMarketAdvance { APPLIED, NO_WORK }

data class MatchingMarketFrontier(
    val eventStream: String,
    val partitionId: Int,
    val projectorGeneration: String,
    val sourceGeneration: String,
    val projectedSequence: Long,
    val lastWindowDigest: String?,
    val observedSourceSequence: Long,
    val lagPositions: Long
)

data class MatchingMarketLevel(val price: BigDecimal, val quantity: BigDecimal)
data class MatchingMarketBook(
    val bids: List<MatchingMarketLevel>,
    val asks: List<MatchingMarketLevel>,
    val frontier: MatchingMarketFrontier
)
data class MatchingMarketTrade(
    val tradeId: String,
    val eventId: String,
    val executionId: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val occurredAt: String,
    val sourceSequence: Long,
    val effectOrdinal: Int
)
data class MatchingMarketTape(
    val trades: List<MatchingMarketTrade>,
    val frontier: MatchingMarketFrontier
)
data class MatchingMarketVector(val frontiers: Map<Int, MatchingMarketFrontier>)

/**
 * Default-off sibling projection. Reads retained matching outcomes directly; no settlement
 * journal or settlement status is consulted. Each target batch is one atomic frontier advance.
 */
class MatchingOutcomeMarketCandidate(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val schema: String = "postmatch",
    private val sourceReader: PostgresCanonicalOutcomeSourceReader =
        PostgresCanonicalOutcomeSourceReader(sourceDataSource),
    private val maxRecoveryWindows: Int = 10_000,
    private val beforeCommit: () -> Unit = {}
) {
    companion object { const val CONSUMER_NAME = "matching-market-candidate-v1" }
    init {
        require(schema.matches(Regex("[a-z][a-z0-9_]*")) &&
            maxRecoveryWindows in 1..100_000)
    }
    private data class ProofKey(val stream: String, val partition: Int,
        val sourceGeneration: String, val projectorGeneration: String)
    private val trustedSequence = mutableMapOf<ProofKey, Long>()

    fun initialize(stream: String, partition: Int, sourceGeneration: String, projectorGeneration: String) {
        require(stream.isNotBlank() && partition >= 0 && sourceGeneration.isNotBlank() &&
            projectorGeneration.isNotBlank())
        targetDataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO $schema.matching_market_candidate_frontiers
                   (event_stream, partition_id, projector_generation, source_generation,
                    last_stream_sequence) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"""
            ).use { statement ->
                statement.setString(1, stream)
                statement.setInt(2, partition)
                statement.setString(3, projectorGeneration)
                statement.setString(4, sourceGeneration)
                statement.setLong(5, CanonicalStreamPosition.origin(partition))
                statement.executeUpdate()
            }
            check(frontier(connection, stream, partition, projectorGeneration, false)
                .sourceGeneration == sourceGeneration) { "market source generation changed" }
        }
    }

    fun advanceNext(stream: String, partition: Int, sourceGeneration: String,
        projectorGeneration: String, maxOutcomes: Int = 5000,
        maxResultBytes: Long = 8_000_000): MatchingMarketAdvance {
        require(maxOutcomes in 1..5000 && maxResultBytes > 0)
        initialize(stream, partition, sourceGeneration, projectorGeneration)
        val proofKey = ProofKey(stream, partition, sourceGeneration, projectorGeneration)
        ensureRecoveredSourcePrefix(proofKey)
        val start = targetDataSource.connection.use { connection ->
            frontier(connection, stream, partition, projectorGeneration, false).sequence
        }
        val window = sourceReader.readNextWindow(CONSUMER_NAME, stream, partition,
            sourceGeneration, start, maxOutcomes, maxResultBytes) ?: return MatchingMarketAdvance.NO_WORK
        applyVerified(projectorGeneration, window)
        synchronized(trustedSequence) {
            trustedSequence[proofKey] = maxOf(trustedSequence[proofKey] ?: start,
                window.throughInclusiveSequence)
        }
        return MatchingMarketAdvance.APPLIED
    }

    private fun ensureRecoveredSourcePrefix(key: ProofKey) {
        synchronized(trustedSequence) {
            val current = targetDataSource.connection.use { connection ->
                frontier(connection, key.stream, key.partition, key.projectorGeneration, false)
            }
            check(current.sourceGeneration == key.sourceGeneration) {
                "market source generation changed"
            }
            if ((trustedSequence[key] ?: Long.MIN_VALUE) >= current.sequence) return
            if (current.sequence > CanonicalStreamPosition.origin(key.partition)) {
                verifySourcePrefix(key.stream, key.partition, key.sourceGeneration,
                    key.projectorGeneration, maxRecoveryWindows)
            }
            val after = targetDataSource.connection.use { connection ->
                frontier(connection, key.stream, key.partition, key.projectorGeneration, false)
            }
            check(after.sourceGeneration == key.sourceGeneration &&
                after.sequence == current.sequence && after.digest == current.digest) {
                "market frontier changed during recovery proof"
            }
            trustedSequence[key] = current.sequence
        }
    }

    /** Explicit retained-source tamper check for last committed coverage window. */
    fun verifyLastWindow(stream: String, partition: Int, sourceGeneration: String,
        projectorGeneration: String) {
        val saved = targetDataSource.connection.use { connection ->
            val current = frontier(connection, stream, partition, projectorGeneration, false)
            check(current.sourceGeneration == sourceGeneration) { "market source generation changed" }
            connection.prepareStatement(
                """SELECT from_exclusive_sequence, through_inclusive_sequence,
                          source_generation, verified_source_digest, exact_source_digest
                   FROM $schema.matching_market_candidate_windows
                   WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                   ORDER BY through_inclusive_sequence DESC LIMIT 1"""
            ).use { statement ->
                statement.setString(1, stream)
                statement.setInt(2, partition)
                statement.setString(3, projectorGeneration)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "market has no committed source window" }
                    SavedWindow(rows.getLong(1), rows.getLong(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), current.sequence,
                        current.digest)
                }
            }
        }
        check(saved.through == saved.projectedSequence &&
            saved.sourceGeneration == sourceGeneration &&
            saved.exactDigest == saved.checkpointDigest) {
            "market window differs from projected frontier"
        }
        val retained = sourceReader.readVerifiedWindow(CONSUMER_NAME, stream, partition,
            sourceGeneration, saved.from, saved.through)
        check(retained.sourceDigest == saved.verifiedDigest &&
            exactDigest(retained) == saved.exactDigest) { "retained market source changed" }
    }

    /**
     * Bounded startup source-prefix proof, never called by read requests or on each batch.
     * Verifies retained source against every coverage receipt through the frontier.
     * This does not prove projected order, level, or tape rows equal an independent rebuild.
     */
    fun verifySourcePrefix(stream: String, partition: Int, sourceGeneration: String,
        projectorGeneration: String, maxWindows: Int) {
        require(maxWindows in 1..100_000)
        val saved = targetDataSource.connection.use { connection ->
            val oldIsolation = connection.transactionIsolation
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val current = frontier(connection, stream, partition, projectorGeneration, false)
                check(current.sourceGeneration == sourceGeneration) {
                    "market source generation changed"
                }
                val windows = connection.prepareStatement(
                    """SELECT from_exclusive_sequence, through_inclusive_sequence,
                              source_generation, verified_source_digest, exact_source_digest
                       FROM $schema.matching_market_candidate_windows
                       WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                       ORDER BY through_inclusive_sequence LIMIT ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setInt(2, partition)
                    statement.setString(3, projectorGeneration)
                    statement.setInt(4, maxWindows + 1)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(SavedWindow(rows.getLong(1), rows.getLong(2),
                                rows.getString(3), rows.getString(4), rows.getString(5),
                                current.sequence, current.digest))
                        }
                    }
                }
                check(windows.size <= maxWindows) { "market recovery window bound exceeded" }
                var expected = CanonicalStreamPosition.origin(partition)
                windows.forEach { window ->
                    check(window.sourceGeneration == sourceGeneration &&
                        window.from == expected && window.through > window.from) {
                        "market recovery coverage has a gap or changed generation"
                    }
                    expected = window.through
                }
                check(expected == current.sequence &&
                    (windows.lastOrNull()?.exactDigest == current.digest ||
                        windows.isEmpty() && current.digest == null)) {
                    "market recovery frontier differs from coverage"
                }
                connection.commit()
                windows
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.transactionIsolation = oldIsolation
            }
        }
        saved.forEach { window ->
            val retained = sourceReader.readVerifiedWindow(CONSUMER_NAME, stream, partition,
                sourceGeneration, window.from, window.through)
            check(retained.sourceDigest == window.verifiedDigest &&
                exactDigest(retained) == window.exactDigest) {
                "retained market source changed within recovery prefix"
            }
        }
    }

    fun readBook(stream: String, partition: Int, projectorGeneration: String,
        sourceGeneration: String, runId: String, venueSessionId: String,
        instrumentId: String, currency: String, depth: Int = 1,
        requireCurrent: Boolean = false): MatchingMarketBook {
        require(depth in 1..100)
        return snapshot(stream, partition, projectorGeneration, sourceGeneration,
            requireCurrent) { connection, frontier ->
            MatchingMarketBook(
                levels(connection, stream, partition, projectorGeneration, runId,
                    venueSessionId, instrumentId, currency, "BUY", depth),
                levels(connection, stream, partition, projectorGeneration, runId,
                    venueSessionId, instrumentId, currency, "SELL", depth),
                frontier)
        }
    }

    /** One target snapshot for all requested partition checkpoints; source heads are observed separately. */
    fun readVector(stream: String, projectorGeneration: String,
        sourceGenerations: Map<Int, String>, requireCurrent: Boolean = false): MatchingMarketVector {
        require(sourceGenerations.size in 1..32 && sourceGenerations.keys.all { it >= 0 } &&
            sourceGenerations.values.all(String::isNotBlank))
        val observedByPartition = sourceGenerations.keys.associateWith { sourceLatest(stream, it) }
        return targetDataSource.connection.use { connection ->
            val oldIsolation = connection.transactionIsolation
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val vector = sourceGenerations.toSortedMap().mapValues { (partition, sourceGeneration) ->
                    val current = frontier(connection, stream, partition, projectorGeneration, false)
                    check(current.sourceGeneration == sourceGeneration) {
                        "market source generation changed"
                    }
                    val observed = observedByPartition.getValue(partition)
                    val lag = observed - current.sequence
                    check(lag >= 0) { "market projection exceeds retained source" }
                    if (requireCurrent) check(lag == 0L) { "market projection is behind source" }
                    MatchingMarketFrontier(stream, partition, projectorGeneration,
                        sourceGeneration, current.sequence, current.digest, observed, lag)
                }
                connection.commit()
                MatchingMarketVector(vector)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.transactionIsolation = oldIsolation
            }
        }
    }

    fun readTape(stream: String, partition: Int, projectorGeneration: String,
        sourceGeneration: String, runId: String, venueSessionId: String,
        instrumentId: String, currency: String, limit: Int = 50,
        beforeSequence: Long? = null, beforeOrdinal: Int? = null,
        requireCurrent: Boolean = false): MatchingMarketTape {
        require(limit in 1..500 && ((beforeSequence == null && beforeOrdinal == null) ||
            (beforeSequence != null && beforeOrdinal != null && beforeOrdinal >= 0)))
        return snapshot(stream, partition, projectorGeneration, sourceGeneration,
            requireCurrent) { connection, frontier ->
            val trades = connection.prepareStatement(
                """SELECT trade_id, event_id, execution_id, quantity_units, price,
                          occurred_at_text, source_stream_sequence, source_effect_ordinal
                   FROM $schema.matching_market_candidate_tape
                   WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                     AND run_id = ? AND venue_session_id = ? AND instrument_id = ?
                     AND currency = ?
                     AND (?::bigint IS NULL OR (source_stream_sequence, source_effect_ordinal)
                          < (?, ?))
                   ORDER BY source_stream_sequence DESC, source_effect_ordinal DESC
                   LIMIT ?"""
            ).use { statement ->
                statement.setString(1, stream)
                statement.setInt(2, partition)
                statement.setString(3, projectorGeneration)
                statement.setString(4, runId)
                statement.setString(5, venueSessionId)
                statement.setString(6, instrumentId)
                statement.setString(7, currency)
                if (beforeSequence == null) {
                    statement.setNull(8, java.sql.Types.BIGINT)
                    statement.setNull(9, java.sql.Types.BIGINT)
                    statement.setNull(10, java.sql.Types.INTEGER)
                } else {
                    statement.setLong(8, beforeSequence)
                    statement.setLong(9, beforeSequence)
                    statement.setInt(10, beforeOrdinal!!)
                }
                statement.setInt(11, limit)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(MatchingMarketTrade(rows.getString(1),
                        rows.getString(2), rows.getString(3), rows.getBigDecimal(4),
                        rows.getBigDecimal(5), rows.getString(6), rows.getLong(7),
                        rows.getInt(8))) }
                }
            }
            MatchingMarketTape(trades, frontier)
        }
    }

    private fun applyVerified(generation: String, window: VerifiedCanonicalSourceWindow) {
        check(window.consumerName == CONSUMER_NAME && window.outcomes.isNotEmpty())
        targetDataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val current = frontier(connection, window.eventStream, window.partitionId,
                    generation, true)
                check(current.sourceGeneration == window.sourceGeneration &&
                    current.sequence == window.fromExclusiveSequence) {
                    "market source frontier, generation or worker order changed"
                }
                val exact = exactDigest(window)
                val orders = linkedMapOf<String, MarketOrder?>()
                val changed = linkedSetOf<String>()
                val levelDeltas = linkedMapOf<LevelKey, BigDecimal>()
                val trades = mutableListOf<MarketTradeInsert>()
                fun order(id: String): MarketOrder? =
                    if (orders.containsKey(id)) orders[id] else loadOrder(connection,
                        window.eventStream, window.partitionId, generation, id).also {
                        orders[id] = it
                    }
                window.outcomes.forEach { outcome ->
                    outcome.effects.forEach { envelope ->
                        when (val effect = envelope.effect) {
                            is CanonicalEffect.Accepted -> effect.newOrder?.let { identity ->
                                check(order(identity.orderId) == null) { "market order identity repeated" }
                                check(identity.runId.isNotBlank() &&
                                    identity.venueSessionId.isNotBlank() &&
                                    identity.side in setOf("BUY", "SELL")) {
                                    "market order has incomplete book identity"
                                }
                                orders[identity.orderId] = MarketOrder(identity.orderId,
                                    identity.runId, identity.venueSessionId, identity.instrumentId,
                                    identity.currency, identity.side, identity.orderType,
                                    "NEW", BigDecimal.ZERO,
                                    BigDecimal(identity.quantityUnits), BigDecimal.ZERO,
                                    BigDecimal(identity.limitPrice))
                                changed += identity.orderId
                            }
                            is CanonicalEffect.Execution -> {
                                val prior = order(effect.orderId)
                                    ?: error("market execution lacks accepted order")
                                val amount = BigDecimal(effect.quantityUnits)
                                check(amount > BigDecimal.ZERO && prior.instrument == effect.instrumentId &&
                                    prior.currency == effect.currency) {
                                    "market execution identity or quantity changed"
                                }
                                orders[effect.orderId] = prior.copy(filled = prior.filled + amount)
                                changed += effect.orderId
                            }
                            is CanonicalEffect.OrderStateChanged -> {
                                val prior = order(effect.orderId)
                                    ?: error("market order state lacks accepted identity")
                                check(prior.instrument == effect.instrumentId &&
                                    prior.currency == effect.currency && prior.side == effect.side) {
                                    "market order identity changed"
                                }
                                val quantity = BigDecimal(effect.remainingQuantity)
                                val original = BigDecimal(effect.originalQuantity)
                                val price = BigDecimal(effect.limitPrice)
                                check(original >= BigDecimal.ZERO && quantity >= BigDecimal.ZERO &&
                                    prior.filled + quantity <= original && price >= BigDecimal.ZERO &&
                                    (effect.status == "CANCELLED" ||
                                        prior.filled + quantity == original) &&
                                    when (effect.status) {
                                        "ACCEPTED" -> prior.filled == BigDecimal.ZERO &&
                                            quantity == original
                                        "PARTIALLY_FILLED" -> prior.filled > BigDecimal.ZERO &&
                                            quantity > BigDecimal.ZERO
                                        "FILLED" -> prior.filled == original &&
                                            quantity == BigDecimal.ZERO
                                        "CANCELLED" -> quantity == BigDecimal.ZERO
                                        else -> false
                                    }) {
                                    "market order state has invalid quantity"
                                }
                                val next = prior.copy(status = effect.status, remaining = quantity,
                                    original = original, price = price)
                                contribution(prior)?.let { key ->
                                    levelDeltas.merge(key, prior.remaining.negate(), BigDecimal::add)
                                }
                                contribution(next)?.let { key ->
                                    levelDeltas.merge(key, next.remaining, BigDecimal::add)
                                }
                                orders[effect.orderId] = next
                                changed += effect.orderId
                            }
                            is CanonicalEffect.Trade -> {
                                val buyer = order(effect.buyOrderId)
                                    ?: error("market trade lacks buyer order")
                                val seller = order(effect.sellOrderId)
                                    ?: error("market trade lacks seller order")
                                check(buyer.runId == seller.runId &&
                                    buyer.session == seller.session &&
                                    buyer.instrument == effect.instrumentId &&
                                    seller.instrument == effect.instrumentId &&
                                    buyer.currency == effect.currency &&
                                    seller.currency == effect.currency &&
                                    buyer.side == "BUY" && seller.side == "SELL") {
                                    "market trade crosses book identity"
                                }
                                trades += MarketTradeInsert(buyer, effect, envelope.position.effectOrdinal,
                                    envelope.position.streamSequence)
                            }
                            else -> Unit
                        }
                    }
                }
                changed.forEach { id ->
                    saveOrder(connection, window.eventStream, window.partitionId, generation,
                        orders.getValue(id)!!)
                }
                levelDeltas.forEach { (key, delta) ->
                    if (delta.signum() != 0) applyLevelDelta(connection, window.eventStream,
                        window.partitionId, generation, key, delta)
                }
                trades.forEach { trade ->
                    insertTrade(connection, window, generation, trade)
                }
                connection.prepareStatement(
                    """INSERT INTO $schema.matching_market_candidate_windows
                       (event_stream, partition_id, projector_generation, source_generation,
                        from_exclusive_sequence, through_inclusive_sequence,
                        verified_source_digest, exact_source_digest)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    statement.setString(1, window.eventStream)
                    statement.setInt(2, window.partitionId)
                    statement.setString(3, generation)
                    statement.setString(4, window.sourceGeneration)
                    statement.setLong(5, window.fromExclusiveSequence)
                    statement.setLong(6, window.throughInclusiveSequence)
                    statement.setString(7, window.sourceDigest)
                    statement.setString(8, exact)
                    check(statement.executeUpdate() == 1)
                }
                connection.prepareStatement(
                    """UPDATE $schema.matching_market_candidate_frontiers
                       SET last_stream_sequence = ?, last_window_digest = ?
                       WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                         AND last_stream_sequence = ? AND source_generation = ?"""
                ).use { statement ->
                    statement.setLong(1, window.throughInclusiveSequence)
                    statement.setString(2, exact)
                    statement.setString(3, window.eventStream)
                    statement.setInt(4, window.partitionId)
                    statement.setString(5, generation)
                    statement.setLong(6, window.fromExclusiveSequence)
                    statement.setString(7, window.sourceGeneration)
                    check(statement.executeUpdate() == 1)
                }
                beforeCommit()
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    private data class MarketOrder(val id: String, val runId: String, val session: String,
        val instrument: String, val currency: String, val side: String, val orderType: String,
        val status: String,
        val remaining: BigDecimal, val original: BigDecimal, val filled: BigDecimal,
        val price: BigDecimal)
    private data class LevelKey(val runId: String, val session: String, val instrument: String,
        val currency: String, val side: String, val price: BigDecimal)
    private data class MarketTradeInsert(val book: MarketOrder, val trade: CanonicalEffect.Trade,
        val ordinal: Int, val sequence: Long)
    private data class StoredFrontier(val sourceGeneration: String, val sequence: Long,
        val digest: String?)
    private data class SavedWindow(val from: Long, val through: Long,
        val sourceGeneration: String, val verifiedDigest: String, val exactDigest: String,
        val projectedSequence: Long, val checkpointDigest: String?)

    private fun contribution(order: MarketOrder): LevelKey? =
        if (order.orderType == "LIMIT" && order.remaining.signum() > 0 &&
            order.status in setOf("ACCEPTED", "PARTIALLY_FILLED"))
            LevelKey(order.runId, order.session, order.instrument, order.currency, order.side,
                order.price.stripTrailingZeros()) else null

    private fun frontier(connection: Connection, stream: String, partition: Int,
        generation: String, lock: Boolean): StoredFrontier =
        connection.prepareStatement(
            """SELECT source_generation, last_stream_sequence, last_window_digest
               FROM $schema.matching_market_candidate_frontiers
               WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
               ${if (lock) "FOR UPDATE" else ""}"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.setString(3, generation)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "market projection frontier is not initialized" }
                StoredFrontier(rows.getString(1), rows.getLong(2), rows.getString(3))
            }
        }

    private fun loadOrder(connection: Connection, stream: String, partition: Int,
        generation: String, id: String): MarketOrder? =
        connection.prepareStatement(
            """SELECT run_id, venue_session_id, instrument_id, currency, side, order_type,
                      status, remaining_quantity, original_quantity, filled_quantity, limit_price
               FROM $schema.matching_market_candidate_orders
               WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                 AND order_id = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.setString(3, generation)
            statement.setString(4, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) MarketOrder(id, rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                    rows.getString(7), rows.getBigDecimal(8), rows.getBigDecimal(9),
                    rows.getBigDecimal(10), rows.getBigDecimal(11)) else null
            }
        }

    private fun saveOrder(connection: Connection, stream: String, partition: Int,
        generation: String, order: MarketOrder) {
        connection.prepareStatement(
            """INSERT INTO $schema.matching_market_candidate_orders
               (event_stream, partition_id, projector_generation, order_id, run_id,
                venue_session_id, instrument_id, currency, side, order_type, status,
                remaining_quantity, original_quantity, filled_quantity, limit_price)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (event_stream, partition_id, projector_generation, order_id)
               DO UPDATE SET status = EXCLUDED.status,
                 remaining_quantity = EXCLUDED.remaining_quantity,
                 original_quantity = EXCLUDED.original_quantity,
                 filled_quantity = EXCLUDED.filled_quantity,
                 limit_price = EXCLUDED.limit_price"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.setString(3, generation)
            statement.setString(4, order.id)
            statement.setString(5, order.runId)
            statement.setString(6, order.session)
            statement.setString(7, order.instrument)
            statement.setString(8, order.currency)
            statement.setString(9, order.side)
            statement.setString(10, order.orderType)
            statement.setString(11, order.status)
            statement.setBigDecimal(12, order.remaining)
            statement.setBigDecimal(13, order.original)
            statement.setBigDecimal(14, order.filled)
            statement.setBigDecimal(15, order.price)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun applyLevelDelta(connection: Connection, stream: String, partition: Int,
        generation: String, key: LevelKey, delta: BigDecimal) {
        val existing = connection.prepareStatement(
            """SELECT quantity FROM $schema.matching_market_candidate_levels
               WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                 AND run_id = ? AND venue_session_id = ? AND instrument_id = ?
                 AND currency = ? AND side = ? AND price = ?"""
        ).use { statement ->
            bindLevel(statement, stream, partition, generation, key)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getBigDecimal(1) else BigDecimal.ZERO
            }
        }
        val next = existing + delta
        check(next >= BigDecimal.ZERO) { "market price level would become negative" }
        if (next.signum() == 0) {
            connection.prepareStatement(
                """DELETE FROM $schema.matching_market_candidate_levels
                   WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                     AND run_id = ? AND venue_session_id = ? AND instrument_id = ?
                     AND currency = ? AND side = ? AND price = ?"""
            ).use { statement ->
                bindLevel(statement, stream, partition, generation, key)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                """INSERT INTO $schema.matching_market_candidate_levels
                   (event_stream, partition_id, projector_generation, run_id, venue_session_id,
                    instrument_id, currency, side, price, quantity)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (event_stream, partition_id, projector_generation, run_id,
                                venue_session_id, instrument_id, currency, side, price)
                   DO UPDATE SET quantity = EXCLUDED.quantity"""
            ).use { statement ->
                bindLevel(statement, stream, partition, generation, key)
                statement.setBigDecimal(10, next)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    private fun bindLevel(statement: java.sql.PreparedStatement, stream: String, partition: Int,
        generation: String, key: LevelKey) {
        statement.setString(1, stream)
        statement.setInt(2, partition)
        statement.setString(3, generation)
        statement.setString(4, key.runId)
        statement.setString(5, key.session)
        statement.setString(6, key.instrument)
        statement.setString(7, key.currency)
        statement.setString(8, key.side)
        statement.setBigDecimal(9, key.price)
    }

    private fun insertTrade(connection: Connection, window: VerifiedCanonicalSourceWindow,
        generation: String, input: MarketTradeInsert) {
        val trade = input.trade
        connection.prepareStatement(
            """INSERT INTO $schema.matching_market_candidate_tape
               (event_stream, partition_id, projector_generation, run_id, venue_session_id,
                instrument_id, currency, trade_id, event_id, execution_id, quantity_units,
                price, occurred_at_text, source_generation, source_stream_sequence,
                source_effect_ordinal)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setString(3, generation)
            statement.setString(4, input.book.runId)
            statement.setString(5, input.book.session)
            statement.setString(6, input.book.instrument)
            statement.setString(7, input.book.currency)
            statement.setString(8, trade.tradeId)
            statement.setString(9, trade.eventId)
            statement.setString(10, trade.executionId)
            statement.setBigDecimal(11, BigDecimal(trade.quantityUnits))
            statement.setBigDecimal(12, BigDecimal(trade.price))
            statement.setString(13, trade.occurredAt)
            statement.setString(14, window.sourceGeneration)
            statement.setLong(15, input.sequence)
            statement.setInt(16, input.ordinal)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun levels(connection: Connection, stream: String, partition: Int,
        generation: String, run: String, session: String, instrument: String,
        currency: String, side: String, limit: Int): List<MatchingMarketLevel> {
        val direction = if (side == "BUY") "DESC" else "ASC"
        return connection.prepareStatement(
            """SELECT price, quantity FROM $schema.matching_market_candidate_levels
               WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                 AND run_id = ? AND venue_session_id = ? AND instrument_id = ?
                 AND currency = ? AND side = ?
               ORDER BY price $direction LIMIT ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.setString(3, generation)
            statement.setString(4, run)
            statement.setString(5, session)
            statement.setString(6, instrument)
            statement.setString(7, currency)
            statement.setString(8, side)
            statement.setInt(9, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(MatchingMarketLevel(
                    rows.getBigDecimal(1), rows.getBigDecimal(2))) }
            }
        }
    }

    private fun <T> snapshot(stream: String, partition: Int, generation: String,
        sourceGeneration: String, requireCurrent: Boolean,
        read: (Connection, MatchingMarketFrontier) -> T): T {
        require(stream.isNotBlank() && partition >= 0 && generation.isNotBlank() &&
            sourceGeneration.isNotBlank())
        val observed = sourceLatest(stream, partition)
        val result = targetDataSource.connection.use { connection ->
            val oldIsolation = connection.transactionIsolation
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val current = frontier(connection, stream, partition, generation, false)
                check(current.sourceGeneration == sourceGeneration) {
                    "market source generation changed"
                }
                val lag = observed - current.sequence
                check(lag >= 0) { "market projection exceeds retained source" }
                if (requireCurrent) check(lag == 0L) { "market projection is behind source" }
                val value = read(connection, MatchingMarketFrontier(stream, partition,
                    generation, sourceGeneration, current.sequence, current.digest,
                    observed, lag))
                connection.commit()
                value
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.transactionIsolation = oldIsolation
            }
        }
        return result
    }

    private fun sourceLatest(stream: String, partition: Int): Long =
        sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT stream_sequence FROM runtime.canonical_command_outcomes
                   WHERE event_stream = ? AND partition_id = ?
                   ORDER BY stream_sequence DESC LIMIT 1"""
            ).use { statement ->
                statement.setString(1, stream)
                statement.setInt(2, partition)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1) else CanonicalStreamPosition.origin(partition)
                }
            }
        }

    private fun exactDigest(window: VerifiedCanonicalSourceWindow): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        listOf("reef.matching.market.source-window.v1", window.eventStream,
            window.partitionId.toString(), window.sourceGeneration,
            window.fromExclusiveSequence.toString(), window.throughInclusiveSequence.toString(),
            window.sourceDigest).forEach(::field)
        window.outcomes.forEach { outcome ->
            val source: CanonicalOutcomeSource = outcome.source
            listOf(source.streamSequence.toString(), source.batchId, source.commandId,
                source.commandType, source.payloadHash, source.instrumentId, source.orderId,
                source.resultStatus, source.resultPayloadJson).forEach(::field)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
