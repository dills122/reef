package com.reef.platform.api

import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/** Conservative source-to-visible age: observation occurs after projection commit. */
internal class PostMatchCandidateAgeMetrics(private val source: DataSource) {
    fun recordVisibleWindow(window: VerifiedCanonicalSourceWindow, observedAt: Instant) {
        val oldestSourceAt = source.connection.use { connection ->
            connection.prepareStatement(
                """SELECT MIN(created_at_ts), COUNT(*), COUNT(created_at_ts)
                   FROM runtime.canonical_venue_event_batches
                   WHERE event_stream = ? AND partition_id = ?
                     AND last_sequence > ? AND first_sequence <= ?"""
            ).use { statement ->
                statement.setString(1, window.eventStream)
                statement.setInt(2, window.partitionId)
                statement.setLong(3, window.fromExclusiveSequence)
                statement.setLong(4, window.throughInclusiveSequence)
                statement.executeQuery().use { rows ->
                    check(rows.next() && rows.getLong(2) > 0 && rows.getLong(2) == rows.getLong(3)) {
                        "matching source batch timestamps are incomplete"
                    }
                    rows.getTimestamp(1).toInstant()
                }
            }
        }
        check(!oldestSourceAt.isAfter(observedAt)) { "matching source timestamp exceeds visible observation" }
        val upperBoundMs = Duration.between(oldestSourceAt, observedAt).toMillis()
        System.out.println(
            "postmatch_candidate_visible_age " +
                "observed_at=$observedAt event_stream=${window.eventStream} partition=${window.partitionId} " +
                "from_exclusive=${window.fromExclusiveSequence} through=${window.throughInclusiveSequence} " +
                "outcomes=${window.outcomes.size} source_to_visible_upper_bound_ms=$upperBoundMs"
        )
    }
}
