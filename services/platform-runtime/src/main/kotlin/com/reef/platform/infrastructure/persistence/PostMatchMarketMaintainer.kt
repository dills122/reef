package com.reef.platform.infrastructure.persistence

import java.math.BigDecimal
import java.sql.Connection
import javax.sql.DataSource

enum class MarketAdvanceResult { APPLIED, NO_WORK }

/** Independent market frontier over committed, bounded live-state change windows. */
class PostMatchMarketMaintainer(private val dataSource: DataSource) {
    companion object { const val CONSUMER_NAME = "live-market-v1" }

    private val consumerName = CONSUMER_NAME

    fun applyNext(eventStream: String, partitionId: Int, sourceGeneration: String): MarketAdvanceResult {
        require(eventStream.isNotBlank() && partitionId >= 0 && sourceGeneration.isNotBlank())
        return dataSource.connection.use { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                initializeFrontier(connection, eventStream, partitionId, sourceGeneration)
                val lastSequence = lockFrontier(connection, eventStream, partitionId, sourceGeneration)
                val window = nextWindow(connection, eventStream, partitionId, sourceGeneration, lastSequence)
                val result = if (window == null) MarketAdvanceResult.NO_WORK else {
                    check(window.fromExclusive == lastSequence) { "market source window skips committed frontier" }
                    val changes = loadChanges(connection, eventStream, partitionId, sourceGeneration, window)
                    check(changes.size == window.changeCount) { "market source change count conflicts with committed window" }
                    val deltas = linkedMapOf<LevelKey, BigDecimal>()
                    val affected = linkedSetOf<BookKey>()
                    changes.forEach { change ->
                        val book = BookKey(change.runId, change.sessionId, change.instrumentId, change.currency)
                        affected += book
                        change.oldPrice?.let { price ->
                            deltas.merge(LevelKey(book, change.side, price.stripTrailingZeros()),
                                change.oldQuantity.negate(), BigDecimal::add)
                        }
                        change.newPrice?.let { price ->
                            deltas.merge(LevelKey(book, change.side, price.stripTrailingZeros()),
                                change.newQuantity, BigDecimal::add)
                        }
                    }
                    deltas.entries.sortedWith(compareBy({ it.key.book.runId }, { it.key.book.sessionId }, { it.key.book.instrumentId },
                        { it.key.book.currency }, { it.key.side }, { it.key.price })).forEach { (key, delta) ->
                        if (delta.signum() != 0) applyLevelDelta(connection, eventStream, sourceGeneration, key, delta)
                    }
                    affected.sortedWith(compareBy({ it.runId }, { it.sessionId }, { it.instrumentId }, { it.currency })).forEach { book ->
                        refreshSnapshot(connection, eventStream, sourceGeneration, partitionId, window.throughInclusive, book)
                    }
                    advanceFrontier(connection, eventStream, partitionId, sourceGeneration, lastSequence, window)
                    MarketAdvanceResult.APPLIED
                }
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = autoCommit
            }
        }
    }

    private data class Window(val fromExclusive: Long, val throughInclusive: Long, val digest: String, val changeCount: Int)
    private data class BookKey(val runId: String, val sessionId: String, val instrumentId: String, val currency: String)
    private data class LevelKey(val book: BookKey, val side: String, val price: BigDecimal)
    private data class Change(
        val runId: String, val sessionId: String, val instrumentId: String, val currency: String, val side: String,
        val oldPrice: BigDecimal?, val oldQuantity: BigDecimal,
        val newPrice: BigDecimal?, val newQuantity: BigDecimal
    )

    private fun initializeFrontier(connection: Connection, stream: String, partition: Int, generation: String) {
        connection.prepareStatement(
            """INSERT INTO postmatch.consumer_frontiers(
               consumer_name, event_stream, partition_id, source_generation, last_stream_sequence)
               VALUES (?, ?, ?, ?, 0) ON CONFLICT (consumer_name, event_stream, partition_id) DO NOTHING"""
        ).use { statement ->
            statement.setString(1, consumerName)
            statement.setString(2, stream)
            statement.setInt(3, partition)
            statement.setString(4, generation)
            statement.executeUpdate()
        }
    }

    private fun lockFrontier(connection: Connection, stream: String, partition: Int, generation: String): Long =
        connection.prepareStatement(
            """SELECT source_generation, last_stream_sequence FROM postmatch.consumer_frontiers
               WHERE consumer_name = ? AND event_stream = ? AND partition_id = ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, consumerName)
            statement.setString(2, stream)
            statement.setInt(3, partition)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == generation) { "market source generation changed" }
                rows.getLong(2)
            }
        }

    private fun nextWindow(
        connection: Connection, stream: String, partition: Int, generation: String, lastSequence: Long
    ): Window? = connection.prepareStatement(
        """SELECT from_exclusive_sequence, through_inclusive_sequence, source_digest, change_count
           FROM postmatch.live_market_change_windows
           WHERE event_stream = ? AND source_generation = ? AND partition_id = ? AND through_inclusive_sequence > ?
           ORDER BY through_inclusive_sequence LIMIT 1"""
    ).use { statement ->
        statement.setString(1, stream)
        statement.setString(2, generation)
        statement.setInt(3, partition)
        statement.setLong(4, lastSequence)
        statement.executeQuery().use { rows ->
            if (rows.next()) Window(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getInt(4)) else null
        }
    }

    private fun loadChanges(
        connection: Connection, stream: String, partition: Int, generation: String, window: Window
    ): List<Change> = connection.prepareStatement(
        """SELECT run_id, venue_session_id, instrument_id, currency, side, old_price, old_quantity,
                  new_price, new_quantity, source_stream_sequence
           FROM postmatch.live_market_order_changes
           WHERE event_stream = ? AND source_generation = ? AND partition_id = ? AND through_inclusive_sequence = ?
           ORDER BY source_stream_sequence, source_effect_ordinal, order_id"""
    ).use { statement ->
        statement.setString(1, stream)
        statement.setString(2, generation)
        statement.setInt(3, partition)
        statement.setLong(4, window.throughInclusive)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val sequence = rows.getLong(10)
                    check(sequence > window.fromExclusive && sequence <= window.throughInclusive) {
                        "market change position is outside committed window"
                    }
                    add(Change(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getBigDecimal(6), rows.getBigDecimal(7),
                        rows.getBigDecimal(8), rows.getBigDecimal(9)))
                }
            }
        }
    }

    private fun applyLevelDelta(
        connection: Connection, stream: String, generation: String, key: LevelKey, delta: BigDecimal
    ) {
        val existing = connection.prepareStatement(
            """SELECT quantity FROM postmatch.market_price_levels WHERE event_stream = ? AND source_generation = ?
               AND run_id = ? AND venue_session_id = ? AND instrument_id = ? AND currency = ? AND side = ? AND price = ? FOR UPDATE"""
        ).use { statement ->
            bindLevelKey(statement, stream, generation, key)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getBigDecimal(1) else null }
        }
        val next = (existing ?: BigDecimal.ZERO) + delta
        check(next.signum() >= 0) { "market price level would become negative" }
        val sql = when {
            existing == null -> """INSERT INTO postmatch.market_price_levels(
                event_stream, source_generation, run_id, venue_session_id, instrument_id, currency, side, price, quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            next.signum() == 0 -> """DELETE FROM postmatch.market_price_levels WHERE event_stream = ? AND source_generation = ?
                AND run_id = ? AND venue_session_id = ? AND instrument_id = ? AND currency = ? AND side = ? AND price = ?"""
            else -> """UPDATE postmatch.market_price_levels SET quantity = ? WHERE event_stream = ? AND source_generation = ?
                AND run_id = ? AND venue_session_id = ? AND instrument_id = ? AND currency = ? AND side = ? AND price = ?"""
        }
        connection.prepareStatement(sql).use { statement ->
            if (existing != null && next.signum() > 0) {
                statement.setBigDecimal(1, next)
                bindLevelKey(statement, stream, generation, key, 2)
            } else {
                bindLevelKey(statement, stream, generation, key)
                if (existing == null) statement.setBigDecimal(9, next)
            }
            check(statement.executeUpdate() == 1) { "market price level changed before commit" }
        }
    }

    private fun bindLevelKey(
        statement: java.sql.PreparedStatement, stream: String, generation: String, key: LevelKey, start: Int = 1
    ) {
        statement.setString(start, stream)
        statement.setString(start + 1, generation)
        statement.setString(start + 2, key.book.runId)
        statement.setString(start + 3, key.book.sessionId)
        statement.setString(start + 4, key.book.instrumentId)
        statement.setString(start + 5, key.book.currency)
        statement.setString(start + 6, key.side)
        statement.setBigDecimal(start + 7, key.price)
    }

    private fun refreshSnapshot(
        connection: Connection, stream: String, generation: String, partition: Int, sequence: Long, book: BookKey
    ) {
        val bid = bestLevel(connection, stream, generation, book, "BUY", "DESC")
        val ask = bestLevel(connection, stream, generation, book, "SELL", "ASC")
        connection.prepareStatement(
            """INSERT INTO postmatch.market_snapshots(
               event_stream, source_generation, run_id, venue_session_id, instrument_id, currency,
               best_bid_price, best_bid_quantity, best_ask_price, best_ask_quantity,
               last_partition_id, last_stream_sequence)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (event_stream, source_generation, run_id, venue_session_id, instrument_id, currency)
               DO UPDATE SET best_bid_price = EXCLUDED.best_bid_price,
                 best_bid_quantity = EXCLUDED.best_bid_quantity,
                 best_ask_price = EXCLUDED.best_ask_price,
                 best_ask_quantity = EXCLUDED.best_ask_quantity,
                 last_partition_id = EXCLUDED.last_partition_id,
                 last_stream_sequence = EXCLUDED.last_stream_sequence,
                 updated_at = clock_timestamp()"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, book.runId)
            statement.setString(4, book.sessionId)
            statement.setString(5, book.instrumentId)
            statement.setString(6, book.currency)
            statement.setBigDecimal(7, bid?.first)
            statement.setBigDecimal(8, bid?.second)
            statement.setBigDecimal(9, ask?.first)
            statement.setBigDecimal(10, ask?.second)
            statement.setInt(11, partition)
            statement.setLong(12, sequence)
            check(statement.executeUpdate() == 1) { "market snapshot was not updated" }
        }
    }

    private fun bestLevel(
        connection: Connection, stream: String, generation: String, book: BookKey, side: String, direction: String
    ): Pair<BigDecimal, BigDecimal>? {
        require(direction == "ASC" || direction == "DESC")
        return connection.prepareStatement(
            """SELECT price, quantity FROM postmatch.market_price_levels
               WHERE event_stream = ? AND source_generation = ? AND run_id = ? AND venue_session_id = ?
                 AND instrument_id = ? AND currency = ? AND side = ?
               ORDER BY price $direction LIMIT 1"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, book.runId)
            statement.setString(4, book.sessionId)
            statement.setString(5, book.instrumentId)
            statement.setString(6, book.currency)
            statement.setString(7, side)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getBigDecimal(1) to rows.getBigDecimal(2) else null
            }
        }
    }

    private fun advanceFrontier(
        connection: Connection, stream: String, partition: Int, generation: String, lastSequence: Long, window: Window
    ) {
        connection.prepareStatement(
            """UPDATE postmatch.consumer_frontiers
               SET last_stream_sequence = ?, last_coverage_digest = ?, updated_at = clock_timestamp()
               WHERE consumer_name = ? AND event_stream = ? AND partition_id = ?
                 AND source_generation = ? AND last_stream_sequence = ?"""
        ).use { statement ->
            statement.setLong(1, window.throughInclusive)
            statement.setString(2, window.digest)
            statement.setString(3, consumerName)
            statement.setString(4, stream)
            statement.setInt(5, partition)
            statement.setString(6, generation)
            statement.setLong(7, lastSequence)
            check(statement.executeUpdate() == 1) { "market frontier changed before commit" }
        }
    }
}
