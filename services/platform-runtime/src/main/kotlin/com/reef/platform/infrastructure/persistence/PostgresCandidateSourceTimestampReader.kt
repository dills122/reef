package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.CandidateSourceTimestampReader
import java.time.Instant
import javax.sql.DataSource

/** Indexed diagnostic lookup; keep it off normal API requests under load. */
class PostgresCandidateSourceTimestampReader(
    private val sourceDataSource: DataSource
) : CandidateSourceTimestampReader {
    override fun sourceCreatedAt(eventStream: String, partitionId: Int,
        sourceSequence: Long): Instant? = sourceDataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT batch.created_at_ts
               FROM runtime.canonical_command_outcomes outcome
               JOIN runtime.canonical_venue_event_batches batch
                 ON batch.event_stream = outcome.event_stream
                AND batch.partition_id = outcome.partition_id
                AND batch.batch_id = outcome.batch_id
                AND outcome.stream_sequence BETWEEN batch.first_sequence AND batch.last_sequence
               WHERE outcome.event_stream = ? AND outcome.partition_id = ?
                 AND outcome.stream_sequence <= ?
               ORDER BY outcome.stream_sequence DESC
               LIMIT 1"""
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setInt(2, partitionId)
            statement.setLong(3, sourceSequence)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else rows.getTimestamp(1)?.toInstant()
            }
        }
    }
}
