package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier

/** Supplied by an authority outside the journal database restore domain. */
data class SettlementJournalSnapshotAnchor(
    val batchSequence: Long,
    val batchDigest: String,
    val stateVersion: Int,
    val stateDigest: String
)

data class SettlementJournalFinalityAnchor(
    val eventStream: String,
    val incarnationId: String,
    val acknowledgedBatchSequence: Long,
    val acknowledgedBatchDigest: String,
    val controlSequence: Long,
    val controlDigest: String,
    val sourceBindingDigest: String? = null,
    val snapshot: SettlementJournalSnapshotAnchor? = null
)

/** Re-read from retained source binding, never from writer configuration or cached state. */
fun interface SettlementSourceBindingDigestReader {
    fun readDigest(eventStream: String): String?
}

/** Implementations must provide strongly consistent, durable monotonic acknowledgements and
 * non-reused incarnations from a failure domain independent of the journal database.
 */
fun interface SettlementJournalFinalityAnchorReader {
    fun read(eventStream: String): SettlementJournalFinalityAnchor?
}

/** Read-only gate; successful proof does not grant a writer lease or reconcile an ambiguous tail. */
class SettlementJournalExternalAnchorGate(
    private val replay: SettlementJournalReplayProof,
    private val anchorReader: SettlementJournalFinalityAnchorReader
) {
    fun prove(
        eventStream: String,
        sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier,
        maxBatches: Int = 256
    ): SettlementJournalReplayState = proveWith(eventStream, anchorReader) {
        replay.prove(eventStream, sourceAuthority, controlVerifier, maxBatches)
    }

    companion object {
        internal fun proveWith(eventStream: String,
            anchorReader: SettlementJournalFinalityAnchorReader,
            replay: () -> SettlementJournalReplayState): SettlementJournalReplayState {
            val before = anchorReader.read(eventStream)
                ?: error("external settlement finality anchor is missing")
            val state = replay()
            val after = anchorReader.read(eventStream)
                ?: error("external settlement finality anchor disappeared")
            check(before == after) { "external settlement finality anchor changed during replay" }
            verify(eventStream, state, before)
            return state
        }

        internal fun verify(eventStream: String, state: SettlementJournalReplayState,
            anchor: SettlementJournalFinalityAnchor) {
            val head = state.head
            check(anchor.eventStream == eventStream && anchor.incarnationId.isNotBlank() &&
                anchor.acknowledgedBatchSequence >= 0) { "external settlement finality anchor is invalid" }
            anchor.sourceBindingDigest?.let { digest ->
                check(digest.matches(Regex("[0-9a-f]{64}"))) {
                    "external settlement source binding digest is invalid"
                }
            }
            anchor.snapshot?.let { snapshot ->
                check(snapshot.batchSequence in 1L..anchor.acknowledgedBatchSequence &&
                    snapshot.batchDigest.matches(Regex("[0-9a-f]{64}")) &&
                    snapshot.stateVersion > 0 &&
                    snapshot.stateDigest.matches(Regex("[0-9a-f]{64}")) &&
                    (snapshot.batchSequence != anchor.acknowledgedBatchSequence ||
                        snapshot.batchDigest == anchor.acknowledgedBatchDigest)) {
                    "external settlement snapshot anchor is invalid"
                }
            }
            check(anchor.incarnationId == head.incarnationId &&
                anchor.acknowledgedBatchSequence == head.nextBatchSequence - 1 &&
                anchor.acknowledgedBatchDigest == head.lastBatchDigest &&
                anchor.controlSequence == head.lastControlSequence &&
                anchor.controlDigest == head.lastControlDigest) {
                "journal does not match external acknowledged finality"
            }
        }
    }
}
