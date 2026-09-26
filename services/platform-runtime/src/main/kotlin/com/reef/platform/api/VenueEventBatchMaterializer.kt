package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

interface VenueEventBatchDelivery {
    val subject: String
    val payloadJson: String
    val streamSequence: Long
    val deliveredCount: Long
    fun ack()
    fun nak()
    fun term()
}

interface VenueEventBatchSource {
    fun fetch(batchSize: Int, timeout: Duration): List<VenueEventBatchDelivery>
    fun ackBatch(deliveries: List<VenueEventBatchDelivery>): VenueEventBatchAckResult {
        deliveries.forEach { it.ack() }
        return VenueEventBatchAckResult(deliveries.map { it.streamSequence })
    }
}

data class VenueEventBatchAckResult(
    val committedStreamSequences: List<Long>
)

fun venueEventBatchSourceWithLocalFaultHooks(
    source: VenueEventBatchSource,
    lookup: (String) -> String? = { key -> System.getenv(key) }
): VenueEventBatchSource {
    if (!RuntimeEnv.bool("VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE", false, lookup = lookup)) return source
    val internalHttpMode = RuntimeEnv.string("PLATFORM_INTERNAL_HTTP_MODE", "local", lookup).trim().lowercase()
    require(internalHttpMode in setOf("enabled", "all", "raw-external")) {
        "VENUE_EVENT_MATERIALIZER_TEST_FAIL_ACK_ONCE requires PLATFORM_INTERNAL_HTTP_MODE=enabled"
    }
    return FailAckOnceVenueEventBatchSource(source)
}

class VenueEventBatchMaterializer(
    private val source: VenueEventBatchSource,
    private val api: PlatformApi,
    private val batchSize: Int = RuntimeEnv.int("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", 100, min = 1),
    private val pollIntervalMs: Long = RuntimeEnv.long("VENUE_EVENT_MATERIALIZER_POLL_MS", 25L, min = 1L),
    private val fetchTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", 200L, min = 1L)),
    private val stopAfterAckFailure: Boolean = RuntimeEnv.bool("VENUE_EVENT_MATERIALIZER_TEST_STOP_AFTER_ACK_FAILURE", false),
    private val workerName: String = "reef-venue-event-batch-materializer",
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var workerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        workerThread = thread(name = workerName, isDaemon = true) {
            try {
                while (running.get()) {
                    val processed = processOnce()
                    if (processed == 0) {
                        VenueEventBatchMaterializerMetrics.recordEmptyPoll()
                        Thread.sleep(pollIntervalMs)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
                workerThread = null
            }
        }
    }

    fun stop() {
        running.set(false)
        workerThread?.interrupt()
    }

    fun awaitStopped(timeout: Duration = Duration.ofSeconds(5)): Boolean {
        val thread = workerThread ?: return true
        thread.join(timeout.toMillis())
        return !thread.isAlive
    }

    fun processOnce(): Int {
        val deliveries = HotPathMetrics.time("venueEventMaterializer.fetch") {
            source.fetch(batchSize, fetchTimeout)
        }
        VenueEventBatchMaterializerMetrics.recordFetched(deliveries.map { it.streamSequence })
        val parsed = deliveries.sortedBy { it.streamSequence }.mapNotNull { delivery -> parseDelivery(delivery) }
        if (parsed.isNotEmpty()) {
            materializeAndAck(parsed)
        }
        return deliveries.size
    }

    private fun parseDelivery(delivery: VenueEventBatchDelivery): ParsedVenueEventBatchDelivery? {
        val eventType = delivery.subject.substringAfterLast('.', missingDelimiterValue = "")
        if (eventType != "VenueEventBatch") {
            safeTerm(delivery)
            VenueEventBatchMaterializerMetrics.recordUnsupported()
            return null
        }

        val parsed = try {
            parseVenueEventBatch(delivery.payloadJson)
        } catch (ex: VenueEventBatchChecksumException) {
            safeNak(delivery)
            VenueEventBatchMaterializerMetrics.recordFailed(ex.message ?: "venue event batch checksum mismatch")
            return null
        } catch (ex: VenueEventBatchMembershipException) {
            safeNak(delivery)
            VenueEventBatchMaterializerMetrics.recordFailed(ex.message ?: "venue event batch membership mismatch")
            return null
        } catch (ex: Exception) {
            safeTerm(delivery)
            VenueEventBatchMaterializerMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            return null
        }
        return ParsedVenueEventBatchDelivery(delivery, parsed.batch, parsed.checksumGuarded)
    }

    private fun materializeAndAck(parsed: List<ParsedVenueEventBatchDelivery>) {
        val canonicalStartedNanos = nanoTime()
        val materializedOutcomes = try {
            HotPathMetrics.time("venueEventMaterializer.materializeBatchGroup") {
                api.materializeVenueEventBatches(parsed.map { it.batch })
            }
        } catch (ex: Exception) {
            if (parsed.size > 1) {
                val midpoint = parsed.size / 2
                materializeAndAck(parsed.subList(0, midpoint))
                materializeAndAck(parsed.subList(midpoint, parsed.size))
            } else {
                safeNak(parsed.single().delivery)
                VenueEventBatchMaterializerMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            }
            return
        }
        val canonicalFinishedNanos = nanoTime()
        val canonicalCommitObservedAt = clock.instant()
        parsed.forEach { MaterializerTimingJournal.global.record(it.batch, canonicalCommitObservedAt) }
        val latest = parsed.maxBy { it.delivery.streamSequence }
        VenueEventBatchMaterializerMetrics.recordMaterialized(
            batchCount = parsed.size,
            latestBatch = latest.batch,
            latestStreamSequence = latest.delivery.streamSequence,
            outcomeCount = materializedOutcomes,
            batches = parsed.map { it.batch to it.checksumGuarded },
            canonicalCommitObservedAt = canonicalCommitObservedAt,
            canonicalCommitElapsedNanos = guardedElapsedNanos(canonicalStartedNanos, canonicalFinishedNanos)
        )

        val sourceCommitStartedNanos = canonicalFinishedNanos
        try {
            val suppliedDeliveries = parsed.map { it.delivery }
            val ackResult = source.ackBatch(suppliedDeliveries)
            val suppliedSequences = suppliedDeliveries.mapTo(mutableSetOf()) { it.streamSequence }
            require(ackResult.committedStreamSequences.all(suppliedSequences::contains)) {
                "venue-event source reported a committed sequence that was not supplied"
            }
            val committedSequences = ackResult.committedStreamSequences.distinct()
            val sourceCommitFinishedNanos = nanoTime()
            VenueEventBatchMaterializerMetrics.recordSourceCommitObserved(
                streamSequences = committedSequences,
                batchCount = committedSequences.size,
                observedAt = clock.instant(),
                elapsedNanos = guardedElapsedNanos(sourceCommitStartedNanos, sourceCommitFinishedNanos)
            )
        } catch (ex: Exception) {
            parsed.forEach { safeNak(it.delivery) }
            VenueEventBatchMaterializerMetrics.recordAckFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            if (stopAfterAckFailure) {
                running.set(false)
                throw ex
            }
        }
    }

    private fun guardedElapsedNanos(started: Long, finished: Long): Long? {
        val elapsed = finished - started
        if (elapsed < 0L) {
            VenueEventBatchMaterializerMetrics.recordClockGuardFailure()
            return null
        }
        return elapsed
    }

    private fun parseVenueEventBatch(payloadJson: String): ParsedVenueEventBatch {
        val root = JsonCodec.parseObject(payloadJson)
        val checksumGuarded = validateSemanticChecksum(root)
        val outcomes = root.objectDocuments("outcomes").map { outcome ->
            val result = outcome.raw("result").ifBlank { "{}" }
            VenueCommandOutcomeFact(
                commandId = outcome.string("commandId"),
                commandType = outcome.string("commandType"),
                streamSequence = outcome.long("streamSequence"),
                deliveredCount = outcome.long("deliveredCount"),
                payloadHash = outcome.string("payloadHash"),
                instrumentId = outcome.string("instrumentId"),
                orderId = outcome.string("orderId"),
                resultStatus = outcome.string("status"),
                rejectCode = outcome.string("rejectCode").ifBlank {
                    outcome.obj("result").obj("rejected").string("code")
                },
                resultPayloadJson = result
            )
        }
        val batch = VenueEventBatchFact(
            batchId = root.string("batchId"),
            shardId = root.string("shardId"),
            partition = root.int("partition"),
            commandStream = root.string("commandStream"),
            eventStream = root.string("eventStream"),
            firstSequence = root.long("firstSequence"),
            lastSequence = root.long("lastSequence"),
            commandCount = root.int("commandCount"),
            createdAt = root.string("createdAt"),
            workFinishedAt = validatedWorkFinishedAt(root, checksumGuarded),
            payloadChecksum = root.string("payloadChecksum"),
            payloadChecksumAlgorithm = root.string("payloadChecksumAlgorithm"),
            payloadFormat = root.string("payloadFormat").ifBlank { "venue-event-batch-json" },
            payloadVersion = root.string("payloadVersion").ifBlank { "v1" },
            outcomes = outcomes
        ).also(::validateBatchMembership)
        return ParsedVenueEventBatch(batch, checksumGuarded)
    }

    private fun validateBatchMembership(batch: VenueEventBatchFact) {
        validateMembership(batch.commandCount == batch.outcomes.size) {
            "venue event batch ${batch.batchId} commandCount ${batch.commandCount} does not match ${batch.outcomes.size} outcomes"
        }
        if (batch.outcomes.isEmpty()) {
            validateMembership(batch.firstSequence == 0L && batch.lastSequence == 0L) {
                "empty venue event batch ${batch.batchId} must have zero sequence bounds"
            }
            return
        }
        validateMembership(batch.commandCount > 0 && batch.firstSequence > 0L && batch.lastSequence >= batch.firstSequence) {
            "venue event batch ${batch.batchId} has invalid source sequence bounds"
        }
        val sequences = batch.outcomes.map { it.streamSequence }
        validateMembership(sequences == sequences.sorted()) { "venue event batch ${batch.batchId} outcomes are not in source order" }
        validateMembership(sequences.distinct().size == sequences.size) {
            "venue event batch ${batch.batchId} repeats a source sequence"
        }
        validateMembership(sequences.first() == batch.firstSequence && sequences.last() == batch.lastSequence) {
            "venue event batch ${batch.batchId} membership does not match its sequence bounds"
        }
        if (batch.partition == 0 || batch.firstSequence >= (1L shl 48)) {
            sequences.forEach { sequence ->
                validateMembership(kafkaSourcePosition(sequence).partition == batch.partition) {
                    "venue event batch ${batch.batchId} source partition does not match encoded membership"
                }
            }
        }
    }

    private inline fun validateMembership(condition: Boolean, message: () -> String) {
        if (!condition) throw VenueEventBatchMembershipException(message())
    }

    private fun validateSemanticChecksum(root: JsonDocument): Boolean {
        val algorithm = root.string("payloadChecksumAlgorithm")
        if (algorithm.isBlank()) return false
        if (algorithm != VENUE_EVENT_BATCH_CHECKSUM_ALGORITHM) {
            throw VenueEventBatchChecksumException("unsupported venue event batch checksum algorithm: $algorithm")
        }
        val expected = root.string("payloadChecksum")
        val actual = root.semanticSha256(VENUE_EVENT_BATCH_CHECKSUM_EXCLUDED_FIELDS)
        if (expected.isBlank() || actual != expected) {
            throw VenueEventBatchChecksumException("venue event batch semantic checksum mismatch")
        }
        return true
    }

    private fun validatedWorkFinishedAt(root: JsonDocument, checksumGuarded: Boolean): String {
        val finishedAt = root.string("workFinishedAt")
        val timingChecksum = root.string("timingChecksum")
        // Legacy batches remain readable but cannot establish timing authority.
        if (!checksumGuarded || timingChecksum.isBlank()) return ""
        val bound = "reef-venue-batch-timing-v1\n${root.string("payloadChecksum")}\n$finishedAt"
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bound.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (actual != timingChecksum) {
            throw VenueEventBatchChecksumException("venue event batch timing checksum mismatch")
        }
        return finishedAt
    }

    private fun JsonDocument.int(key: String): Int {
        return string(key).toIntOrNull() ?: 0
    }

    private fun JsonDocument.long(key: String): Long {
        return string(key).toLongOrNull() ?: 0L
    }

    private fun safeNak(delivery: VenueEventBatchDelivery) {
        try {
            delivery.nak()
        } catch (_: Exception) {
        }
    }

    private fun safeTerm(delivery: VenueEventBatchDelivery) {
        try {
            delivery.term()
        } catch (_: Exception) {
        }
    }
}

private data class ParsedVenueEventBatchDelivery(
    val delivery: VenueEventBatchDelivery,
    val batch: VenueEventBatchFact,
    val checksumGuarded: Boolean
)

private data class ParsedVenueEventBatch(
    val batch: VenueEventBatchFact,
    val checksumGuarded: Boolean
)

private const val VENUE_EVENT_BATCH_CHECKSUM_ALGORITHM = "sha256-reef-canonical-v1"
private val VENUE_EVENT_BATCH_CHECKSUM_EXCLUDED_FIELDS = setOf(
    "createdAt",
    "workFinishedAt",
    "timingChecksum",
    "payloadChecksum",
    "payloadChecksumAlgorithm"
)

private class VenueEventBatchChecksumException(message: String) : IllegalArgumentException(message)
private class VenueEventBatchMembershipException(message: String) : IllegalArgumentException(message)

private class FailAckOnceVenueEventBatchSource(
    private val delegate: VenueEventBatchSource
) : VenueEventBatchSource {
    private val failNextAck = AtomicBoolean(true)

    override fun fetch(batchSize: Int, timeout: Duration): List<VenueEventBatchDelivery> {
        return delegate.fetch(batchSize, timeout).map { delivery ->
            if (failNextAck.get()) {
                FailAckOnceVenueEventBatchDelivery(delivery, failNextAck)
            } else {
                delivery
            }
        }
    }
}

private class FailAckOnceVenueEventBatchDelivery(
    private val delegate: VenueEventBatchDelivery,
    private val failNextAck: AtomicBoolean
) : VenueEventBatchDelivery {
    override val subject: String get() = delegate.subject
    override val payloadJson: String get() = delegate.payloadJson
    override val streamSequence: Long get() = delegate.streamSequence
    override val deliveredCount: Long get() = delegate.deliveredCount

    override fun ack() {
        if (failNextAck.compareAndSet(true, false)) {
            error("injected materializer ack failure before offset commit")
        }
        delegate.ack()
    }

    override fun nak() {
        delegate.nak()
    }

    override fun term() {
        delegate.term()
    }
}

data class VenueEventBatchMaterializerStats(
    val fetched: Long,
    val materialized: Long,
    val materializedOutcomes: Long,
    val failed: Long,
    val ackFailed: Long,
    val unsupported: Long,
    val emptyPolls: Long,
    val lastFetchedStreamSequence: Long,
    val lastMaterializedStreamSequence: Long,
    val lastMaterializedBatchId: String,
    val lastMaterializedPartition: Int,
    val lastMaterializedFirstSequence: Long,
    val lastMaterializedLastSequence: Long,
    val materializerLag: Long,
    val sourcePartitions: List<MaterializerSourcePartition>,
    val materializedSourceFrontiers: List<MaterializedSourceFrontier>,
    val checksumValidatedBatches: Long,
    val legacyUncheckedBatches: Long,
    val validatedMembershipOutcomes: Long,
    val canonicalCommitObservedBatches: Long,
    val sourceCommitObservedBatches: Long,
    val canonicalCommitElapsedNanos: Long,
    val canonicalCommitMaxNanos: Long,
    val sourceCommitElapsedNanos: Long,
    val sourceCommitMaxNanos: Long,
    val sourceResidenceSamples: Long,
    val sourceResidenceElapsedMs: Long,
    val sourceResidenceMaxMs: Long,
    val clockGuardFailures: Long,
    val lastSourceWorkFinishedAt: String,
    val lastCanonicalCommitObservedAt: String,
    val lastSourceCommitObservedAt: String,
    val lastMaterializedAt: String,
    val lastFailedAt: String,
    val lastError: String
)

data class MaterializerSourcePartition(
    val partition: Int,
    val fetched: Long,
    val commitObserved: Long,
    val firstFetchedOffsetInclusive: Long,
    val lastFetchedOffsetExclusive: Long,
    val lastCommitObservedOffsetExclusive: Long,
    val lag: Long
)

data class MaterializedSourceFrontier(
    val partition: Int,
    val observedBatches: Long,
    val observedOutcomes: Long,
    val firstOffsetInclusive: Long,
    val lastOffsetExclusive: Long,
    val coveredRanges: List<SourceOffsetRange> = emptyList()
)

data class SourceOffsetRange(
    val firstOffsetInclusive: Long,
    val lastOffsetExclusive: Long
)

object VenueEventBatchMaterializerMetrics {
    private val fetched = AtomicLong(0)
    private val materialized = AtomicLong(0)
    private val materializedOutcomes = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val ackFailed = AtomicLong(0)
    private val unsupported = AtomicLong(0)
    private val emptyPolls = AtomicLong(0)
    private val lastFetchedStreamSequence = AtomicLong(0)
    private val lastMaterializedStreamSequence = AtomicLong(0)
    private val lastMaterializedPartition = AtomicLong(-1)
    private val lastMaterializedFirstSequence = AtomicLong(0)
    private val lastMaterializedLastSequence = AtomicLong(0)
    private val lastMaterializedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    private val validatedMembershipOutcomes = AtomicLong(0)
    private val checksumValidatedBatches = AtomicLong(0)
    private val legacyUncheckedBatches = AtomicLong(0)
    private val canonicalCommitObservedBatches = AtomicLong(0)
    private val sourceCommitObservedBatches = AtomicLong(0)
    private val canonicalCommitElapsedNanos = AtomicLong(0)
    private val canonicalCommitMaxNanos = AtomicLong(0)
    private val sourceCommitElapsedNanos = AtomicLong(0)
    private val sourceCommitMaxNanos = AtomicLong(0)
    private val sourceResidenceSamples = AtomicLong(0)
    private val sourceResidenceElapsedMs = AtomicLong(0)
    private val sourceResidenceMaxMs = AtomicLong(0)
    private val clockGuardFailures = AtomicLong(0)
    private val lastSourceWorkFinishedAtEpochMs = AtomicLong(0)
    private val lastCanonicalCommitObservedAtEpochMs = AtomicLong(0)
    private val lastSourceCommitObservedAtEpochMs = AtomicLong(0)
    private val sourcePartitionTrackers = ConcurrentHashMap<Int, MaterializerSourcePartitionTracker>()
    private val materializedSourceTrackers = ConcurrentHashMap<Int, MaterializedSourceFrontierTracker>()
    @Volatile
    private var lastMaterializedBatchId: String = ""
    @Volatile
    private var lastError: String = ""

    fun recordFetched(streamSequences: List<Long>) {
        fetched.addAndGet(streamSequences.size.toLong())
        streamSequences.forEach { streamSequence ->
            if (streamSequence <= 0L) return@forEach
            lastFetchedStreamSequence.accumulateAndGet(streamSequence, ::maxOf)
            val position = kafkaSourcePosition(streamSequence)
            sourcePartitionTrackers
                .computeIfAbsent(position.partition) { MaterializerSourcePartitionTracker(position.partition) }
                .recordFetched(position.offset)
        }
    }

    fun recordMaterialized(
        batchCount: Int,
        latestBatch: VenueEventBatchFact,
        latestStreamSequence: Long,
        outcomeCount: Long,
        batches: List<Pair<VenueEventBatchFact, Boolean>>,
        canonicalCommitObservedAt: Instant,
        canonicalCommitElapsedNanos: Long?
    ) {
        materialized.addAndGet(batchCount.toLong())
        materializedOutcomes.addAndGet(outcomeCount)
        lastMaterializedBatchId = latestBatch.batchId
        lastMaterializedPartition.set(latestBatch.partition.toLong())
        lastMaterializedFirstSequence.set(latestBatch.firstSequence)
        lastMaterializedLastSequence.set(latestBatch.lastSequence)
        lastMaterializedStreamSequence.set(latestStreamSequence)
        lastMaterializedAtEpochMs.set(canonicalCommitObservedAt.toEpochMilli())
        lastCanonicalCommitObservedAtEpochMs.set(canonicalCommitObservedAt.toEpochMilli())
        canonicalCommitObservedBatches.addAndGet(batchCount.toLong())
        canonicalCommitElapsedNanos?.let { elapsed ->
            this.canonicalCommitElapsedNanos.addAndGet(elapsed)
            canonicalCommitMaxNanos.accumulateAndGet(elapsed, ::maxOf)
        }
        batches.forEach { (batch, checksumGuarded) ->
            if (checksumGuarded) {
                checksumValidatedBatches.incrementAndGet()
                recordCommittedMembership(batch, canonicalCommitObservedAt)
            } else {
                legacyUncheckedBatches.incrementAndGet()
            }
        }
    }

    fun recordSourceCommitObserved(
        streamSequences: List<Long>,
        batchCount: Int,
        observedAt: Instant,
        elapsedNanos: Long?
    ) {
        sourceCommitObservedBatches.addAndGet(batchCount.toLong())
        lastSourceCommitObservedAtEpochMs.set(observedAt.toEpochMilli())
        elapsedNanos?.let { elapsed ->
            sourceCommitElapsedNanos.addAndGet(elapsed)
            sourceCommitMaxNanos.accumulateAndGet(elapsed, ::maxOf)
        }
        streamSequences.forEach { streamSequence ->
            if (streamSequence <= 0L) return@forEach
            val position = kafkaSourcePosition(streamSequence)
            sourcePartitionTrackers
                .computeIfAbsent(position.partition) { MaterializerSourcePartitionTracker(position.partition) }
                .recordCommitObserved(position.offset)
        }
    }

    private fun recordCommittedMembership(batch: VenueEventBatchFact, observedAt: Instant) {
        validatedMembershipOutcomes.addAndGet(batch.commandCount.toLong())
        sourceOffsets(batch)?.let { offsets ->
            materializedSourceTrackers
                .computeIfAbsent(batch.partition) { MaterializedSourceFrontierTracker(batch.partition) }
                .record(batch.commandCount, offsets)
        }
        val sourceWorkFinishedAt = try {
            Instant.parse(batch.workFinishedAt)
        } catch (_: DateTimeParseException) {
            recordClockGuardFailure()
            return
        }
        if (sourceWorkFinishedAt.isAfter(observedAt)) {
            recordClockGuardFailure()
            return
        }
        val sourceWorkFinishedAtEpochMs = try {
            sourceWorkFinishedAt.toEpochMilli()
        } catch (_: ArithmeticException) {
            recordClockGuardFailure()
            return
        }
        val residenceMs = try {
            Duration.between(sourceWorkFinishedAt, observedAt).toMillis()
        } catch (_: ArithmeticException) {
            recordClockGuardFailure()
            return
        }
        lastSourceWorkFinishedAtEpochMs.accumulateAndGet(sourceWorkFinishedAtEpochMs, ::maxOf)
        sourceResidenceSamples.incrementAndGet()
        sourceResidenceElapsedMs.addAndGet(residenceMs)
        sourceResidenceMaxMs.accumulateAndGet(residenceMs, ::maxOf)
    }

    private fun sourceOffsets(batch: VenueEventBatchFact): List<Long>? {
        if (batch.commandCount <= 0) return null
        if (batch.partition != 0 && batch.firstSequence < (1L shl 48)) return null
        val positions = batch.outcomes.map { outcome -> kafkaSourcePosition(outcome.streamSequence) }
        if (positions.any { position -> position.partition != batch.partition }) return null
        return positions.map { position -> position.offset }
    }

    fun recordClockGuardFailure() {
        clockGuardFailures.incrementAndGet()
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun recordAckFailed(error: String) {
        ackFailed.incrementAndGet()
        lastError = error
    }

    fun recordUnsupported() {
        unsupported.incrementAndGet()
    }

    fun recordEmptyPoll() {
        emptyPolls.incrementAndGet()
    }

    fun snapshot(): VenueEventBatchMaterializerStats {
        val sourcePartitions = sourcePartitionTrackers.values.map { it.snapshot() }.sortedBy { it.partition }
        return VenueEventBatchMaterializerStats(
            fetched = fetched.get(),
            materialized = materialized.get(),
            materializedOutcomes = materializedOutcomes.get(),
            failed = failed.get(),
            ackFailed = ackFailed.get(),
            unsupported = unsupported.get(),
            emptyPolls = emptyPolls.get(),
            lastFetchedStreamSequence = lastFetchedStreamSequence.get(),
            lastMaterializedStreamSequence = lastMaterializedStreamSequence.get(),
            lastMaterializedBatchId = lastMaterializedBatchId,
            lastMaterializedPartition = lastMaterializedPartition.get().toInt(),
            lastMaterializedFirstSequence = lastMaterializedFirstSequence.get(),
            lastMaterializedLastSequence = lastMaterializedLastSequence.get(),
            materializerLag = sourcePartitions.sumOf { it.lag },
            sourcePartitions = sourcePartitions,
            materializedSourceFrontiers = materializedSourceTrackers.values.map { it.snapshot() }.sortedBy { it.partition },
            checksumValidatedBatches = checksumValidatedBatches.get(),
            legacyUncheckedBatches = legacyUncheckedBatches.get(),
            validatedMembershipOutcomes = validatedMembershipOutcomes.get(),
            canonicalCommitObservedBatches = canonicalCommitObservedBatches.get(),
            sourceCommitObservedBatches = sourceCommitObservedBatches.get(),
            canonicalCommitElapsedNanos = canonicalCommitElapsedNanos.get(),
            canonicalCommitMaxNanos = canonicalCommitMaxNanos.get(),
            sourceCommitElapsedNanos = sourceCommitElapsedNanos.get(),
            sourceCommitMaxNanos = sourceCommitMaxNanos.get(),
            sourceResidenceSamples = sourceResidenceSamples.get(),
            sourceResidenceElapsedMs = sourceResidenceElapsedMs.get(),
            sourceResidenceMaxMs = sourceResidenceMaxMs.get(),
            clockGuardFailures = clockGuardFailures.get(),
            lastSourceWorkFinishedAt = instantString(lastSourceWorkFinishedAtEpochMs.get()),
            lastCanonicalCommitObservedAt = instantString(lastCanonicalCommitObservedAtEpochMs.get()),
            lastSourceCommitObservedAt = instantString(lastSourceCommitObservedAtEpochMs.get()),
            lastMaterializedAt = instantString(lastMaterializedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        fetched.set(0)
        materialized.set(0)
        materializedOutcomes.set(0)
        failed.set(0)
        ackFailed.set(0)
        unsupported.set(0)
        emptyPolls.set(0)
        lastFetchedStreamSequence.set(0)
        lastMaterializedStreamSequence.set(0)
        lastMaterializedPartition.set(-1)
        lastMaterializedFirstSequence.set(0)
        lastMaterializedLastSequence.set(0)
        lastMaterializedAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        validatedMembershipOutcomes.set(0)
        checksumValidatedBatches.set(0)
        legacyUncheckedBatches.set(0)
        canonicalCommitObservedBatches.set(0)
        sourceCommitObservedBatches.set(0)
        canonicalCommitElapsedNanos.set(0)
        canonicalCommitMaxNanos.set(0)
        sourceCommitElapsedNanos.set(0)
        sourceCommitMaxNanos.set(0)
        sourceResidenceSamples.set(0)
        sourceResidenceElapsedMs.set(0)
        sourceResidenceMaxMs.set(0)
        clockGuardFailures.set(0)
        lastSourceWorkFinishedAtEpochMs.set(0)
        lastCanonicalCommitObservedAtEpochMs.set(0)
        lastSourceCommitObservedAtEpochMs.set(0)
        sourcePartitionTrackers.clear()
        materializedSourceTrackers.clear()
        lastMaterializedBatchId = ""
        lastError = ""
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

private class MaterializerSourcePartitionTracker(private val partition: Int) {
    private var fetched = 0L
    private var commitObserved = 0L
    private var firstFetchedOffsetInclusive = Long.MAX_VALUE
    private var lastFetchedOffsetExclusive = 0L
    private var lastCommitObservedOffsetExclusive = 0L

    @Synchronized
    fun recordFetched(offset: Long) {
        fetched += 1L
        firstFetchedOffsetInclusive = minOf(firstFetchedOffsetInclusive, offset)
        lastFetchedOffsetExclusive = maxOf(lastFetchedOffsetExclusive, offset + 1L)
    }

    @Synchronized
    fun recordCommitObserved(offset: Long) {
        commitObserved += 1L
        lastCommitObservedOffsetExclusive = maxOf(lastCommitObservedOffsetExclusive, offset + 1L)
    }

    @Synchronized
    fun snapshot(): MaterializerSourcePartition {
        val firstFetched = firstFetchedOffsetInclusive.takeUnless { it == Long.MAX_VALUE } ?: 0L
        val committedFrontier = lastCommitObservedOffsetExclusive.takeIf { it > 0L } ?: firstFetched
        return MaterializerSourcePartition(
            partition = partition,
            fetched = fetched,
            commitObserved = commitObserved,
            firstFetchedOffsetInclusive = firstFetched,
            lastFetchedOffsetExclusive = lastFetchedOffsetExclusive,
            lastCommitObservedOffsetExclusive = lastCommitObservedOffsetExclusive,
            lag = kafkaOffsetDistance(committedFrontier, lastFetchedOffsetExclusive)
        )
    }
}

private class MaterializedSourceFrontierTracker(private val partition: Int) {
    private var observedBatches = 0L
    private var observedOutcomes = 0L
    private var coveredRanges = emptyList<SourceOffsetRange>()

    @Synchronized
    fun record(outcomeCount: Int, offsets: List<Long>) {
        observedBatches += 1L
        observedOutcomes += outcomeCount.toLong()
        coveredRanges = mergeSourceOffsetRanges(coveredRanges + sourceOffsetRanges(offsets))
    }

    @Synchronized
    fun snapshot(): MaterializedSourceFrontier {
        val ranges = coveredRanges.toList()
        return MaterializedSourceFrontier(
            partition = partition,
            observedBatches = observedBatches,
            observedOutcomes = observedOutcomes,
            firstOffsetInclusive = ranges.firstOrNull()?.firstOffsetInclusive ?: 0L,
            lastOffsetExclusive = ranges.lastOrNull()?.lastOffsetExclusive ?: 0L,
            coveredRanges = ranges
        )
    }
}

private fun sourceOffsetRanges(offsets: List<Long>): List<SourceOffsetRange> {
    if (offsets.isEmpty()) return emptyList()
    val ranges = mutableListOf<SourceOffsetRange>()
    var first = offsets.first()
    var lastExclusive = first + 1L
    offsets.drop(1).forEach { offset ->
        require(offset >= lastExclusive) { "source offsets must be ordered and unique" }
        if (offset == lastExclusive) {
            lastExclusive += 1L
        } else {
            ranges += SourceOffsetRange(first, lastExclusive)
            first = offset
            lastExclusive = offset + 1L
        }
    }
    ranges += SourceOffsetRange(first, lastExclusive)
    return ranges
}

private fun mergeSourceOffsetRanges(ranges: List<SourceOffsetRange>): List<SourceOffsetRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedWith(compareBy(SourceOffsetRange::firstOffsetInclusive, SourceOffsetRange::lastOffsetExclusive))
    val merged = mutableListOf<SourceOffsetRange>()
    sorted.forEach { range ->
        require(range.lastOffsetExclusive > range.firstOffsetInclusive) { "source offset range must be non-empty" }
        val previous = merged.lastOrNull()
        if (previous == null || range.firstOffsetInclusive > previous.lastOffsetExclusive) {
            merged += range
        } else if (range.lastOffsetExclusive > previous.lastOffsetExclusive) {
            merged[merged.lastIndex] = previous.copy(lastOffsetExclusive = range.lastOffsetExclusive)
        }
    }
    return merged
}
