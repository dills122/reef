package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import javax.sql.DataSource

/** Re-reads retained matching outcomes for default-off settlement recovery proof. */
class PostgresSettlementReplaySourceAuthority(
    private val sourceDataSource: DataSource,
    private val sourceCatalog: PostMatchReadSourceCatalog = PostMatchSourceCatalog(sourceDataSource),
    private val sourceReader: PostgresCanonicalOutcomeSourceReader =
        PostgresCanonicalOutcomeSourceReader(sourceDataSource),
    private val emptyRangeAttestor: SettlementEmptyRangeAttestor? = null
) : SettlementReplaySourceAuthority {
    override fun stableEndSequence(eventStream: String, sourceGeneration: String,
        partitionId: Int, fromExclusiveSequence: Long): Long? {
        require(eventStream.isNotBlank() && partitionId in 0..32767)
        check(sourceCatalog.generation() == sourceGeneration) {
            "retained source generation changed before Kafka stable-end read"
        }
        val stableEnd = emptyRangeAttestor?.stableEndSequence(eventStream, sourceGeneration,
            partitionId, fromExclusiveSequence)
        check(sourceCatalog.generation() == sourceGeneration) {
            "retained source generation changed during Kafka stable-end read"
        }
        return stableEnd
    }

    override fun verifyRecoveredFrontier(eventStream: String, sourceGeneration: String,
        partitionId: Int, lastRetainedFrontier: Long, coveredFrontier: Long): Boolean {
        if (eventStream.isBlank() || partitionId !in 0..32767 || sourceGeneration.isBlank()) return false
        val origin = com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(partitionId)
        if (lastRetainedFrontier < origin || coveredFrontier < lastRetainedFrontier ||
            coveredFrontier - lastRetainedFrontier > 5_000_000 ||
            coveredFrontier - origin >= (1L shl 48) ||
            sourceCatalog.generation() != sourceGeneration ||
            retainedPartitionHead(partitionId, eventStream) < lastRetainedFrontier
        ) return false
        var from = lastRetainedFrontier
        while (from < coveredFrontier) {
            val through = minOf(coveredFrontier, from + 5000)
            val window = SettlementJournalSourceWindow(0, sourceGeneration, partitionId,
                from, through, "recovery-broker-absence", "", emptyList())
            if (!verifyEmpty(eventStream, window)) return false
            from = through
        }
        return sourceCatalog.generation() == sourceGeneration &&
            retainedPartitionHead(partitionId, eventStream) >= lastRetainedFrontier
    }

    override fun readVerified(
        eventStream: String,
        window: SettlementJournalSourceWindow
    ): VerifiedCanonicalSourceWindow {
        require(eventStream.isNotBlank() && window.members.isNotEmpty()) {
            "retained source replay requires a nonempty window"
        }
        val generation = sourceCatalog.generation()
        check(generation == window.sourceGeneration) {
            "retained source generation changed before replay"
        }
        val verified = sourceReader.readVerifiedWindow(
            CONSUMER_NAME, eventStream, window.partitionId, generation,
            window.fromExclusiveSequence, window.throughInclusiveSequence
        )
        check(sourceCatalog.generation() == generation) {
            "retained source generation changed during replay"
        }
        return verified
    }

    /** SQL absence corroborates broker proof; it never establishes an empty Kafka range alone. */
    override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow): Boolean {
        if (eventStream.isBlank() || window.members.isNotEmpty() ||
            window.partitionId !in 0..32767 || window.sourceGeneration.isBlank() ||
            window.fromExclusiveSequence <
                com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(window.partitionId) ||
            window.throughInclusiveSequence <= window.fromExclusiveSequence ||
            window.throughInclusiveSequence - window.fromExclusiveSequence > 5000
        ) return false
        val attestor = emptyRangeAttestor ?: return false
        val generation = sourceCatalog.generation()
        if (generation != window.sourceGeneration || retainedOutcomeExists(window)) return false
        val proved = attestor.verify(eventStream, window)
        return proved && sourceCatalog.generation() == generation && !retainedOutcomeExists(window)
    }

    private fun retainedOutcomeExists(window: SettlementJournalSourceWindow): Boolean =
        sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT EXISTS (
                    SELECT 1 FROM runtime.canonical_command_outcomes
                    WHERE partition_id = ? AND stream_sequence > ? AND stream_sequence <= ?
                )""".trimIndent()
            ).use { statement ->
                statement.setInt(1, window.partitionId)
                statement.setLong(2, window.fromExclusiveSequence)
                statement.setLong(3, window.throughInclusiveSequence)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "retained source absence check returned no result" }
                    rows.getBoolean(1)
                }
            }
        }

    private fun retainedPartitionHead(partitionId: Int, eventStream: String): Long =
        sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT event_stream, stream_sequence
                   FROM runtime.canonical_command_outcomes
                   WHERE partition_id = ? ORDER BY stream_sequence DESC LIMIT 1"""
            ).use { statement ->
                statement.setInt(1, partitionId)
                statement.executeQuery().use { rows ->
                    if (!rows.next())
                        com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(partitionId)
                    else {
                        check(rows.getString(1) == eventStream) {
                            "retained source partition belongs to another event stream"
                        }
                        rows.getLong(2)
                    }
                }
            }
        }

    companion object {
        private const val CONSUMER_NAME = "settlement-journal-replay"
    }
}
