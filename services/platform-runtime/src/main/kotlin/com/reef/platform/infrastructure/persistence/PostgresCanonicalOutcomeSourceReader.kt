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
        val sources = sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT outcome.event_stream, outcome.partition_id, outcome.stream_sequence,
                       outcome.batch_id, outcome.command_id, outcome.command_type,
                       outcome.payload_hash, outcome.instrument_id, outcome.order_id,
                       outcome.result_status, outcome.result_payload::text,
                       batch.batch_id IS NOT NULL AS has_retained_batch
                FROM runtime.canonical_command_outcomes outcome
                LEFT JOIN runtime.canonical_venue_event_batches batch
                  ON batch.event_stream = outcome.event_stream
                 AND batch.batch_id = outcome.batch_id
                 AND batch.partition_id = outcome.partition_id
                 AND outcome.stream_sequence BETWEEN batch.first_sequence AND batch.last_sequence
                WHERE outcome.event_stream = ? AND outcome.partition_id = ?
                  AND outcome.stream_sequence > ? AND outcome.stream_sequence <= ?
                ORDER BY outcome.stream_sequence
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setInt(2, partitionId)
                statement.setLong(3, fromExclusiveSequence)
                statement.setLong(4, throughInclusiveSequence)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            check(rows.getBoolean(12)) {
                                "canonical outcome has no matching retained batch membership"
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
        }
        return verifier.verify(
            consumerName, eventStream, partitionId, sourceGeneration,
            fromExclusiveSequence, throughInclusiveSequence, sources
        )
    }
}
