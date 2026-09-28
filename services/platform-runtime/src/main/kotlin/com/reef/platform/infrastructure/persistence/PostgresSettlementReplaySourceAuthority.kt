package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import javax.sql.DataSource

/** Re-reads retained matching outcomes for default-off settlement recovery proof. */
class PostgresSettlementReplaySourceAuthority(
    sourceDataSource: DataSource,
    private val sourceCatalog: PostMatchReadSourceCatalog = PostMatchSourceCatalog(sourceDataSource),
    private val sourceReader: PostgresCanonicalOutcomeSourceReader =
        PostgresCanonicalOutcomeSourceReader(sourceDataSource)
) : SettlementReplaySourceAuthority {
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

    /** SQL absence is not proof that a Kafka offset interval contained no matching outcomes. */
    override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow): Boolean = false

    companion object {
        private const val CONSUMER_NAME = "settlement-journal-replay"
    }
}
