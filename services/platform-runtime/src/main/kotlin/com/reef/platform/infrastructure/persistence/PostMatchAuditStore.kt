package com.reef.platform.infrastructure.persistence

import com.fasterxml.jackson.databind.json.JsonMapper
import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectEnvelope
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import java.sql.Connection
import javax.sql.DataSource

/** Independent audit write transaction. Never advances the live or legacy projector frontier. */
class PostMatchAuditStore(private val dataSource: DataSource) {
    private val mapper = JsonMapper.builder().build()
    fun lastCommittedSequence(eventStream: String, partitionId: Int, generation: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT source_generation, last_stream_sequence FROM runtime.canonical_audit_frontiers
                   WHERE event_stream = ? AND partition_id = ?"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setInt(2, partitionId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) CanonicalStreamPosition.origin(partitionId) else {
                        check(rows.getString(1) == generation) { "audit source generation changed" }
                        rows.getLong(2)
                    }
                }
            }
        }

    fun apply(window: VerifiedCanonicalSourceWindow): PostMatchApplyResult = dataSource.connection.use { connection ->
        require(window.consumerName == CONSUMER) { "audit window belongs to another consumer" }
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            initializeOrigin(connection, window)
            val (generation, lastSequence) = lockFrontier(connection, window)
            check(generation == window.sourceGeneration) { "audit source generation changed" }
            val result = when {
                lastSequence == window.fromExclusiveSequence -> {
                    insertOutcomes(connection, window)
                    insertEffects(connection, window)
                    insertCoverage(connection, window)
                    advanceFrontier(connection, window)
                    PostMatchApplyResult.APPLIED
                }
                window.throughInclusiveSequence <= lastSequence -> {
                    verifyReplay(connection, window)
                    PostMatchApplyResult.DUPLICATE
                }
                else -> error("audit source window overlaps or skips committed frontier")
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
            """INSERT INTO runtime.canonical_audit_frontiers(
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
            """SELECT source_generation, last_stream_sequence FROM runtime.canonical_audit_frontiers
               WHERE event_stream = ? AND partition_id = ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "audit source origin was not initialized" }
                rows.getString(1) to rows.getLong(2)
            }
        }

    private fun insertOutcomes(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """INSERT INTO runtime.canonical_audit_outcomes(
                 event_stream, source_generation, partition_id, stream_sequence, batch_id,
                 command_id, command_type, command_payload_hash, instrument_id, order_id,
                 result_status, result_payload, result_digest, effect_count
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)"""
        ).use { statement ->
            window.outcomes.forEach { outcome ->
                val source = outcome.source
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setInt(3, window.partitionId)
                statement.setLong(4, source.streamSequence)
                statement.setString(5, source.batchId)
                statement.setString(6, source.commandId)
                statement.setString(7, source.commandType)
                statement.setString(8, source.payloadHash)
                statement.setString(9, source.instrumentId)
                statement.setString(10, source.orderId)
                statement.setString(11, source.resultStatus)
                statement.setString(12, source.resultPayloadJson)
                statement.setString(13, outcome.resultDigest)
                statement.setInt(14, outcome.effects.size)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertEffects(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """INSERT INTO runtime.canonical_audit_effects(
                 event_stream, source_generation, partition_id, stream_sequence, effect_ordinal,
                 event_id, effect_type, order_id, related_order_id, occurred_at
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            window.outcomes.flatMap { it.effects }.forEach { envelope ->
                val fact = effectFact(envelope)
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setInt(3, window.partitionId)
                statement.setLong(4, envelope.position.streamSequence)
                statement.setInt(5, envelope.position.effectOrdinal)
                statement.setString(6, fact.eventId)
                statement.setString(7, fact.type)
                statement.setString(8, fact.orderId)
                statement.setString(9, fact.relatedOrderId)
                statement.setString(10, fact.occurredAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private data class EffectFact(
        val eventId: String?, val type: String, val orderId: String?, val occurredAt: String?,
        val relatedOrderId: String = ""
    )

    private fun effectFact(envelope: CanonicalEffectEnvelope): EffectFact = when (val effect = envelope.effect) {
        is CanonicalEffect.Accepted -> EffectFact(effect.eventId, when (envelope.commandType) {
            "SubmitOrder" -> "OrderAccepted"
            "ModifyOrder" -> "OrderModified"
            "CancelOrder" -> "OrderCancelled"
            else -> error("unsupported accepted audit command")
        }, effect.orderId, effect.occurredAt)
        is CanonicalEffect.Rejected -> EffectFact(effect.eventId, "OrderRejected", effect.orderId, effect.occurredAt)
        is CanonicalEffect.Failed -> EffectFact(null, "CommandFailed", null, null)
        is CanonicalEffect.Execution -> EffectFact(effect.eventId, "Execution", effect.orderId, effect.occurredAt)
        is CanonicalEffect.Trade -> EffectFact(
            effect.eventId, "Trade", effect.buyOrderId, effect.occurredAt, effect.sellOrderId
        )
        is CanonicalEffect.OrderStateChanged -> EffectFact(null, "OrderStateChanged", effect.orderId, effect.occurredAt)
    }

    private fun insertCoverage(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """INSERT INTO runtime.canonical_audit_coverage(
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
            check(statement.executeUpdate() == 1) { "audit source coverage was not recorded" }
        }
    }

    private fun advanceFrontier(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """UPDATE runtime.canonical_audit_frontiers
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
            check(statement.executeUpdate() == 1) { "audit frontier changed before commit" }
        }
    }

    private fun verifyReplay(connection: Connection, window: VerifiedCanonicalSourceWindow) {
        connection.prepareStatement(
            """SELECT source_generation, from_exclusive_sequence, source_member_count, source_digest
               FROM runtime.canonical_audit_coverage
               WHERE event_stream = ? AND partition_id = ? AND through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setLong(3, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == window.sourceGeneration &&
                    rows.getLong(2) == window.fromExclusiveSequence && rows.getInt(3) == window.outcomes.size &&
                    rows.getString(4) == window.sourceDigest) { "audit replay coverage conflict" }
            }
        }
        connection.prepareStatement(
            """SELECT stream_sequence, batch_id, command_id, command_type, command_payload_hash,
                      instrument_id, order_id, result_status, result_payload::text, result_digest, effect_count
               FROM runtime.canonical_audit_outcomes
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND stream_sequence > ? AND stream_sequence <= ?
               ORDER BY stream_sequence"""
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
                        rows.getString(4) == outcome.source.commandType &&
                        rows.getString(5) == outcome.source.payloadHash &&
                        rows.getString(6) == outcome.source.instrumentId &&
                        rows.getString(7) == outcome.source.orderId &&
                        rows.getString(8) == outcome.source.resultStatus &&
                        mapper.readTree(rows.getString(9)) == mapper.readTree(outcome.source.resultPayloadJson) &&
                        rows.getString(10) == outcome.resultDigest &&
                        rows.getInt(11) == outcome.effects.size) { "audit replay outcome conflict" }
                }
                check(!rows.next()) { "audit replay has extra outcomes" }
            }
        }
        connection.prepareStatement(
            """SELECT stream_sequence, effect_ordinal, event_id, effect_type, order_id,
                      related_order_id, occurred_at
               FROM runtime.canonical_audit_effects
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
                window.outcomes.flatMap { it.effects }.forEach { envelope ->
                    val fact = effectFact(envelope)
                    check(rows.next() && rows.getLong(1) == envelope.position.streamSequence &&
                        rows.getInt(2) == envelope.position.effectOrdinal && rows.getString(3) == fact.eventId &&
                        rows.getString(4) == fact.type && rows.getString(5) == fact.orderId &&
                        rows.getString(6) == fact.relatedOrderId && rows.getString(7) == fact.occurredAt) {
                        "audit replay effect conflict"
                    }
                }
                check(!rows.next()) { "audit replay has extra effects" }
            }
        }
    }

    companion object { const val CONSUMER = "audit-v1" }
}
