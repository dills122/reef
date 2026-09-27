package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import javax.sql.DataSource

/** Reads retained canonical membership from its source database, never the target store. */
class PostgresCanonicalOutcomeSourceReader(
    private val sourceDataSource: DataSource,
    private val verifier: CanonicalSourceCoverageVerifier = CanonicalSourceCoverageVerifier()
) {
    fun readVerifiedWindow(
        consumerName: String,
        eventStream: String,
        partitionId: Int,
        sourceGeneration: String,
        fromExclusiveSequence: Long,
        throughInclusiveSequence: Long
    ): VerifiedCanonicalSourceWindow {
        require(fromExclusiveSequence >= 0 && throughInclusiveSequence > fromExclusiveSequence &&
            throughInclusiveSequence - fromExclusiveSequence <= 5000) { "canonical source read must be bounded" }
        val sources = readSources(eventStream, partitionId, fromExclusiveSequence, throughInclusiveSequence, 5000, true)
        return verifier.verify(
            consumerName, eventStream, partitionId, sourceGeneration,
            fromExclusiveSequence, throughInclusiveSequence, sources
        )
    }

    fun readNextWindow(
        consumerName: String,
        eventStream: String,
        partitionId: Int,
        sourceGeneration: String,
        fromExclusiveSequence: Long,
        maxOutcomes: Int,
        maxResultBytes: Long = Long.MAX_VALUE
    ): VerifiedCanonicalSourceWindow? {
        require(fromExclusiveSequence >= 0 && maxOutcomes in 1..5000 && maxResultBytes > 0) {
            "canonical source read must be bounded"
        }
        val sources = readSources(eventStream, partitionId, fromExclusiveSequence, null, maxOutcomes, false,
            maxResultBytes)
        if (sources.isEmpty()) return null
        check(sources.all { it.eventStream == eventStream }) {
            "canonical partition contains a different event stream; source coverage is unresolved"
        }
        return verifier.verify(
            consumerName, eventStream, partitionId, sourceGeneration,
            fromExclusiveSequence, sources.last().streamSequence, sources
        )
    }

    private fun readSources(
        eventStream: String, partitionId: Int, fromExclusiveSequence: Long,
        throughInclusiveSequence: Long?, limit: Int, onlyMatchingStream: Boolean,
        maxResultBytes: Long = Long.MAX_VALUE
    ): List<CanonicalOutcomeSource> = sourceDataSource.connection.use { connection ->
        val bounded = maxResultBytes != Long.MAX_VALUE
        val oldAutoCommit = connection.autoCommit
        if (bounded) connection.autoCommit = false // pgjdbc needs a transaction to cursor-fetch one row at a time.
        try {
            val payloadProjection = if (bounded) {
                "CASE WHEN octet_length(outcome.result_payload::text) <= ?::bigint " +
                    "THEN outcome.result_payload::text ELSE NULL END, " +
                    "octet_length(outcome.result_payload::text)"
            } else "outcome.result_payload::text"
            val sources = connection.prepareStatement(
                """
                SELECT outcome.event_stream, outcome.partition_id, outcome.stream_sequence,
                       outcome.batch_id, outcome.command_id, outcome.command_type,
                       outcome.payload_hash, outcome.instrument_id, outcome.order_id,
                       outcome.result_status, $payloadProjection,
                       batch.batch_id IS NOT NULL AS has_retained_batch
                FROM runtime.canonical_command_outcomes outcome
                LEFT JOIN runtime.canonical_venue_event_batches batch
                  ON batch.event_stream = outcome.event_stream
                 AND batch.batch_id = outcome.batch_id
                 AND batch.partition_id = outcome.partition_id
                 AND outcome.stream_sequence BETWEEN batch.first_sequence AND batch.last_sequence
                WHERE outcome.partition_id = ? AND outcome.stream_sequence > ?
                  AND outcome.stream_sequence <= COALESCE(?, 9223372036854775807)
                  AND (?::boolean = FALSE OR outcome.event_stream = ?)
                ORDER BY outcome.stream_sequence
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                val firstWhere = if (bounded) 2 else 1
                if (bounded) {
                    statement.setLong(1, maxResultBytes)
                    statement.fetchSize = 1
                }
                statement.setInt(firstWhere, partitionId)
                statement.setLong(firstWhere + 1, fromExclusiveSequence)
                if (throughInclusiveSequence == null) statement.setNull(firstWhere + 2, java.sql.Types.BIGINT)
                else statement.setLong(firstWhere + 2, throughInclusiveSequence)
                statement.setBoolean(firstWhere + 3, onlyMatchingStream)
                statement.setString(firstWhere + 4, eventStream)
                statement.setInt(firstWhere + 5, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        var totalResultBytes = 0L
                        while (rows.next()) {
                            check(rows.getBoolean(if (bounded) 13 else 12)) {
                                "canonical outcome has no matching retained batch membership"
                            }
                            if (bounded) {
                                val nextBytes = rows.getLong(12)
                                check(nextBytes <= maxResultBytes || isNotEmpty()) {
                                    "single canonical result payload exceeds configured bound"
                                }
                                if (nextBytes > maxResultBytes - totalResultBytes) break
                                totalResultBytes += nextBytes
                            }
                            add(CanonicalOutcomeSource(
                                eventStream = rows.getString(1), partitionId = rows.getInt(2),
                                streamSequence = rows.getLong(3), batchId = rows.getString(4),
                                commandId = rows.getString(5), commandType = rows.getString(6),
                                payloadHash = rows.getString(7), instrumentId = rows.getString(8),
                                orderId = rows.getString(9), resultStatus = rows.getString(10),
                                resultPayloadJson = rows.getString(11)
                            ))
                        }
                    }
                }
            }
            if (bounded) connection.commit()
            sources
        } catch (error: Throwable) {
            if (bounded) connection.rollback()
            throw error
        } finally {
            if (bounded) connection.autoCommit = oldAutoCommit
        }
    }
}
