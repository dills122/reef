package com.reef.platform.api

/** Default-off, process-local timing. One line per interval; never writes to financial stores. */
internal class SettlementCandidatePollTiming(
    private val intervalNanos: Long = 10_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val emit: (String) -> Unit = System.err::println
) {
    init { require(intervalNanos > 0) }

    internal enum class Stage(val field: String) {
        LEASE("lease"), SOURCE_IDENTITY("source_identity"), JOURNAL_HEAD("journal_head"),
        CONTROL_PREFIX("control_prefix"), SOURCE_SELECT("source_select"),
        SOURCE_HEADS("source_heads"), SOURCE_RANGE("source_range"),
        BROKER_GAP("broker_gap"), SOURCE_READ("source_read"), MANIFEST("manifest"),
        EVALUATE("evaluate"), MAP("map"), REVERIFY("reverify"),
        APPEND_FINALITY("append_finality"), CONFIRM("confirm"), SNAPSHOT("snapshot")
    }

    private val calls = LongArray(Stage.entries.size)
    private val totalNanos = LongArray(Stage.entries.size)
    private val maxNanos = LongArray(Stage.entries.size)
    private var intervalStart = nanoTime()
    private var polls = 0L
    private var committed = 0L
    private var idle = 0L
    private var failures = 0L
    private var resultCount = 0L
    private var pollTotalNanos = 0L
    private var pollMaxNanos = 0L

    fun begin(): Poll = Poll(nanoTime())

    inner class Poll internal constructor(private val started: Long) {
        fun now(): Long = nanoTime()

        fun record(stage: Stage, elapsedNanos: Long) {
            val index = stage.ordinal
            val elapsed = elapsedNanos.coerceAtLeast(0)
            calls[index]++
            totalNanos[index] += elapsed
            maxNanos[index] = maxOf(maxNanos[index], elapsed)
        }

        fun complete(durable: Boolean, failed: Boolean, results: Int) {
            val now = nanoTime()
            val elapsed = (now - started).coerceAtLeast(0)
            polls++
            if (durable) committed++ else if (!failed) idle++
            if (failed) failures++
            resultCount += results
            pollTotalNanos += elapsed
            pollMaxNanos = maxOf(pollMaxNanos, elapsed)
            if (failed || now - intervalStart >= intervalNanos) report(now)
        }
    }

    /** Source-select encloses its source-head/range/read/manifest subtimings. */
    private fun report(now: Long) {
        val line = buildString {
            append("settlement_journal_candidate_timing interval_ms=")
            append((now - intervalStart).coerceAtLeast(0) / 1_000_000)
            append(" polls=").append(polls)
            append(" committed=").append(committed)
            append(" idle=").append(idle)
            append(" failures=").append(failures)
            append(" results=").append(resultCount)
            append(" poll_total_us=").append(pollTotalNanos / 1_000)
            append(" poll_max_us=").append(pollMaxNanos / 1_000)
            Stage.entries.forEach { stage ->
                val index = stage.ordinal
                append(' ').append(stage.field).append("_calls=").append(calls[index])
                append(' ').append(stage.field).append("_total_us=").append(totalNanos[index] / 1_000)
                append(' ').append(stage.field).append("_max_us=").append(maxNanos[index] / 1_000)
            }
        }
        try {
            emit(line)
        } catch (_: Exception) {
            // Diagnostics must not turn an acknowledged financial batch into a worker failure.
        }
        intervalStart = now
        polls = 0
        committed = 0
        idle = 0
        failures = 0
        resultCount = 0
        pollTotalNanos = 0
        pollMaxNanos = 0
        calls.fill(0)
        totalNanos.fill(0)
        maxNanos.fill(0)
    }
}

internal inline fun <T> measuredCandidateStage(
    poll: SettlementCandidatePollTiming.Poll?,
    stage: SettlementCandidatePollTiming.Stage,
    action: () -> T
): T {
    if (poll == null) return action()
    val started = poll.now()
    return try { action() } finally { poll.record(stage, poll.now() - started) }
}
