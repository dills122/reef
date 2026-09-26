package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Optional evidence journal, called only after checksum/timing validation and canonical commit.
 * These timestamps bound residence; they do not measure individual downstream stages.
 * Snapshot is for dedicated cohort checkpoints, never periodic runtime statistics.
 */
class MaterializerTimingJournal(
    private val enabled: Boolean,
    private val maxEntries: Int = 250_000,
    private val clock: Clock = Clock.systemUTC()
) {
    private val instanceId = UUID.randomUUID().toString()
    // Owned primitive membership; no batch/outcome references or JSON containers retained.
    private class Record(
        val sequence: Long,
        val batchId: String,
        val payloadChecksum: String,
        val commandStream: String,
        val partition: Int,
        val commandCount: Int,
        val streamSequences: LongArray,
        val workFinishedAt: Instant,
        val canonicalCommitObservedAt: Instant
    ) {
        fun snapshot(): Map<String, Any> = mapOf(
            "sequence" to sequence.toString(),
            "batchId" to batchId,
            "payloadChecksum" to payloadChecksum,
            "commandStream" to commandStream,
            "partition" to partition,
            "commandCount" to commandCount.toString(),
            "streamSequences" to streamSequences.map(Long::toString),
            "workFinishedAt" to workFinishedAt.toString(),
            "canonicalCommitObservedAt" to canonicalCommitObservedAt.toString()
        )
    }

    private val records = mutableListOf<Record>()
    private val identities = mutableSetOf<Pair<String, String>>()
    private var lastSequence = 0L
    private var dropped = 0L
    private var duplicate = 0L
    private var invalid = 0L

    init {
        require(maxEntries > 0) { "materializer timing journal capacity must be positive" }
    }

    @Synchronized
    fun record(batch: VenueEventBatchFact, canonicalCommitObservedAt: Instant) {
        if (!enabled) return
        val workFinishedAt = try {
            Instant.parse(batch.workFinishedAt)
        } catch (_: DateTimeParseException) {
            invalid++
            return
        }
        val sequences = LongArray(batch.outcomes.size) { batch.outcomes[it].streamSequence }
        if (batch.batchId.isBlank() || batch.commandStream.isBlank() || batch.eventStream.isBlank() ||
            batch.partition < 0 || batch.payloadChecksum.isBlank() ||
            batch.payloadChecksumAlgorithm != "sha256-reef-canonical-v1" ||
            batch.commandCount <= 0 || batch.commandCount != sequences.size ||
            sequences.any { it <= 0 } || sequences.toSet().size != sequences.size ||
            sequences.minOrNull() != batch.firstSequence || sequences.maxOrNull() != batch.lastSequence ||
            workFinishedAt.isAfter(canonicalCommitObservedAt) || canonicalCommitObservedAt.isAfter(clock.instant())
        ) {
            invalid++
            return
        }
        // Canonical batch identity is scoped by event stream. Never replace original evidence.
        val identity = batch.eventStream to batch.batchId
        if (identity in identities) {
            duplicate++
            return
        }
        if (records.size >= maxEntries) {
            dropped++
            return
        }
        identities.add(identity)
        lastSequence++
        records.add(Record(
            sequence = lastSequence,
            batchId = batch.batchId,
            payloadChecksum = batch.payloadChecksum,
            commandStream = batch.commandStream,
            partition = batch.partition,
            commandCount = batch.commandCount,
            streamSequences = sequences,
            workFinishedAt = workFinishedAt,
            canonicalCommitObservedAt = canonicalCommitObservedAt
        ))
    }

    /** Detached JSON-friendly checkpoint. Counters invalidate incomplete or ambiguous evidence. */
    @Synchronized
    fun snapshot(): Map<String, Any> = mapOf(
        "schemaVersion" to 1,
        "enabled" to enabled,
        "instanceId" to instanceId,
        "lastSequence" to lastSequence.toString(),
        "dropped" to dropped.toString(),
        "duplicate" to duplicate.toString(),
        "invalid" to invalid.toString(),
        "records" to records.map(Record::snapshot)
    )

    companion object {
        val global = MaterializerTimingJournal(
            enabled = RuntimeEnv.bool("PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED", false)
        )
    }
}
