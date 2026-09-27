package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import javax.sql.DataSource

interface PostMatchReadSourceCatalog {
    fun generation(): String
    fun partitionHeads(eventStream: String, partitionCount: Int): Map<Int, Long>
}

/** Durable source identity from canonical runtime storage. */
class PostMatchSourceCatalog(private val sourceDataSource: DataSource) : PostMatchReadSourceCatalog {
    override fun generation(): String = sourceDataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "canonical post-match source generation is missing" }
                rows.getString(1)
            }
        }
    }

    /** One indexed last-row lookup per canonical partition; avoids a historical GROUP BY scan. */
    override fun partitionHeads(eventStream: String, partitionCount: Int): Map<Int, Long> {
        require(eventStream.isNotBlank() && partitionCount in 1..32768)
        // Match the worker's global partition cursor: a foreign stream is a coverage fault.
        return sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT partition_id FROM runtime.canonical_command_outcomes ORDER BY partition_id DESC LIMIT 1"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    if (rows.next()) check(rows.getInt(1) < partitionCount) {
                        "canonical source has a partition outside live read coverage"
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT partitions.partition_id, latest.event_stream, latest.stream_sequence
                FROM generate_series(0, ?::integer - 1) AS partitions(partition_id)
                LEFT JOIN LATERAL (
                    SELECT event_stream, stream_sequence
                    FROM runtime.canonical_command_outcomes
                    WHERE partition_id = partitions.partition_id
                    ORDER BY stream_sequence DESC
                    LIMIT 1
                ) latest ON TRUE
                ORDER BY partitions.partition_id
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, partitionCount)
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            val partition = rows.getInt(1)
                            val stream = rows.getString(2)
                            check(stream == null || stream == eventStream) {
                                "canonical partition contains a different event stream"
                            }
                            val sequence = if (stream == null) CanonicalStreamPosition.origin(partition)
                            else rows.getLong(3)
                            put(partition, sequence)
                        }
                    }
                }
            }
        }
    }

}
