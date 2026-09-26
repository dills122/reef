package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectEnvelope
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import java.sql.Connection
import javax.sql.DataSource

enum class PostMatchApplyResult { APPLIED, DUPLICATE }

/** Target-store transaction authority for one verified canonical source window. */
class PostMatchOperationalStore(private val dataSource: DataSource) {
    fun apply(
        window: VerifiedCanonicalSourceWindow,
        applyEffects: (Connection, List<CanonicalEffectEnvelope>) -> Unit
    ): PostMatchApplyResult = dataSource.connection.use { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            initializeOriginIfEmpty(connection, window)
            val frontier = lockFrontier(connection, window)
            check(frontier.sourceGeneration == window.sourceGeneration) { "post-match source generation changed" }
            val result = when {
                frontier.lastSequence == window.fromExclusiveSequence -> {
                    insertOrderIdentities(connection, window)
                    applyEffects(connection, window.outcomes.flatMap { it.effects })
                    insertReceipts(connection, window)
                    insertCoverage(connection, window)
                    advanceFrontier(connection, window)
                    PostMatchApplyResult.APPLIED
                }
                window.throughInclusiveSequence <= frontier.lastSequence -> {
                    verifyCompletedReplay(connection, window)
                    PostMatchApplyResult.DUPLICATE
                }
                else -> error("post-match source window overlaps or skips committed frontier")
            }
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private data class Frontier(val sourceGeneration: String, val lastSequence: Long)

    private fun initializeOriginIfEmpty(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        if (window.fromExclusiveSequence != 0L) return
        connection.prepareStatement(
            """
            INSERT INTO postmatch.consumer_frontiers(
              consumer_name, event_stream, partition_id, source_generation, last_stream_sequence
            ) VALUES (?, ?, ?, ?, 0)
            ON CONFLICT (consumer_name, event_stream, partition_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, window.consumerName)
            statement.setString(2, window.eventStream)
            statement.setInt(3, window.partitionId)
            statement.setString(4, window.sourceGeneration)
            statement.executeUpdate()
        }
    }

    private fun lockFrontier(connection: Connection, window: VerifiedCanonicalSourceWindow): Frontier =
        connection.prepareStatement(
            """
            SELECT source_generation, last_stream_sequence
            FROM postmatch.consumer_frontiers
            WHERE consumer_name = ? AND event_stream = ? AND partition_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, window.consumerName)
            statement.setString(2, window.eventStream)
            statement.setInt(3, window.partitionId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "post-match source origin has not been verified and initialized" }
                Frontier(rows.getString(1), rows.getLong(2))
            }
        }

    private fun insertOrderIdentities(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """
            INSERT INTO postmatch.canonical_order_directory(
              event_stream, source_generation, order_id, engine_order_id, client_order_id, run_id,
              venue_session_id, instrument_id, participant_id, account_id, side, order_type,
              quantity_units, limit_price, currency, time_in_force, accepted_at,
              source_partition_id, source_stream_sequence, source_effect_ordinal
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            window.outcomes.flatMap { it.effects }.forEach { envelope ->
                val identity = (envelope.effect as? CanonicalEffect.Accepted)?.newOrder ?: return@forEach
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, identity.orderId)
                statement.setString(4, identity.engineOrderId)
                statement.setString(5, identity.clientOrderId)
                statement.setString(6, identity.runId)
                statement.setString(7, identity.venueSessionId)
                statement.setString(8, identity.instrumentId)
                statement.setString(9, identity.participantId)
                statement.setString(10, identity.accountId)
                statement.setString(11, identity.side)
                statement.setString(12, identity.orderType)
                statement.setString(13, identity.quantityUnits)
                statement.setString(14, identity.limitPrice)
                statement.setString(15, identity.currency)
                statement.setString(16, identity.timeInForce)
                statement.setString(17, identity.acceptedAt)
                statement.setInt(18, envelope.position.partitionId)
                statement.setLong(19, envelope.position.streamSequence)
                statement.setInt(20, envelope.position.effectOrdinal)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertReceipts(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """
            INSERT INTO postmatch.consumer_outcome_receipts(
              consumer_name, event_stream, partition_id, source_generation, stream_sequence,
              batch_id, command_id, command_payload_hash, result_digest, effect_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            window.outcomes.forEach { outcome ->
                statement.setString(1, window.consumerName)
                statement.setString(2, window.eventStream)
                statement.setInt(3, window.partitionId)
                statement.setString(4, window.sourceGeneration)
                statement.setLong(5, outcome.source.streamSequence)
                statement.setString(6, outcome.source.batchId)
                statement.setString(7, outcome.source.commandId)
                statement.setString(8, outcome.source.payloadHash)
                statement.setString(9, outcome.resultDigest)
                statement.setInt(10, outcome.effects.size)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertCoverage(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """
            INSERT INTO postmatch.consumer_source_coverage(
              consumer_name, event_stream, partition_id, source_generation,
              from_exclusive_sequence, through_inclusive_sequence, source_member_count, source_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, window.consumerName)
            statement.setString(2, window.eventStream)
            statement.setInt(3, window.partitionId)
            statement.setString(4, window.sourceGeneration)
            statement.setLong(5, window.fromExclusiveSequence)
            statement.setLong(6, window.throughInclusiveSequence)
            statement.setInt(7, window.outcomes.size)
            statement.setString(8, window.sourceDigest)
            check(statement.executeUpdate() == 1) { "post-match source coverage was not recorded" }
        }
    }

    private fun advanceFrontier(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """
            UPDATE postmatch.consumer_frontiers
            SET last_stream_sequence = ?, last_batch_id = ?, last_coverage_digest = ?, updated_at = clock_timestamp()
            WHERE consumer_name = ? AND event_stream = ? AND partition_id = ?
              AND source_generation = ? AND last_stream_sequence = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, window.throughInclusiveSequence)
            statement.setString(2, window.outcomes.last().source.batchId)
            statement.setString(3, window.sourceDigest)
            statement.setString(4, window.consumerName)
            statement.setString(5, window.eventStream)
            statement.setInt(6, window.partitionId)
            statement.setString(7, window.sourceGeneration)
            statement.setLong(8, window.fromExclusiveSequence)
            check(statement.executeUpdate() == 1) { "post-match frontier changed before commit" }
        }
    }

    private fun verifyCompletedReplay(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """
            SELECT source_generation, from_exclusive_sequence, source_member_count, source_digest
            FROM postmatch.consumer_source_coverage
            WHERE consumer_name = ? AND event_stream = ? AND partition_id = ? AND through_inclusive_sequence = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, window.consumerName)
            statement.setString(2, window.eventStream)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == window.sourceGeneration &&
                    rows.getLong(2) == window.fromExclusiveSequence && rows.getInt(3) == window.outcomes.size &&
                    rows.getString(4) == window.sourceDigest) { "post-match replay coverage conflict" }
            }
        }
        connection.prepareStatement(
            """
            SELECT stream_sequence, batch_id, command_id, command_payload_hash, result_digest, effect_count
            FROM postmatch.consumer_outcome_receipts
            WHERE consumer_name = ? AND event_stream = ? AND partition_id = ? AND source_generation = ?
              AND stream_sequence > ? AND stream_sequence <= ?
            ORDER BY stream_sequence
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, window.consumerName)
            statement.setString(2, window.eventStream)
            statement.setInt(3, window.partitionId)
            statement.setString(4, window.sourceGeneration)
            statement.setLong(5, window.fromExclusiveSequence)
            statement.setLong(6, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                window.outcomes.forEach { outcome ->
                    check(rows.next() && rows.getLong(1) == outcome.source.streamSequence &&
                        rows.getString(2) == outcome.source.batchId && rows.getString(3) == outcome.source.commandId &&
                        rows.getString(4) == outcome.source.payloadHash && rows.getString(5) == outcome.resultDigest &&
                        rows.getInt(6) == outcome.effects.size) { "post-match replay receipt conflict" }
                }
                check(!rows.next()) { "post-match replay has extra receipts" }
            }
        }
    }
}
