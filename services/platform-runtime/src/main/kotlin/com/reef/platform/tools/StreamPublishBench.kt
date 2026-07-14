package com.reef.platform.tools

import com.reef.platform.api.AsyncStreamCommandPublisher
import com.reef.platform.api.StreamCommandConfig
import com.reef.platform.api.StreamCommandEnvelope
import com.reef.platform.api.StreamCommandEnvelopeBuilder
import com.reef.platform.api.StreamCommandHealthCheck
import com.reef.platform.api.StreamCommandIntakeFactory
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.max

fun main() {
    val config = StreamPublishBenchConfig.fromEnv()
    val publisher = StreamCommandIntakeFactory.defaultPublisher()
    val asyncPublisher = publisher as? AsyncStreamCommandPublisher
        ?: error("stream publish bench requires AsyncStreamCommandPublisher")
    val healthCheck = publisher as? StreamCommandHealthCheck

    if (config.warmupMessages > 0) {
        warmup(asyncPublisher, config)
    }

    HotPathMetrics.reset()
    val histogram = MillisecondHistogram(config.maxLatencyBucketMs)
    val errors = ConcurrentHashMap<String, AtomicLong>()
    val inFlight = AtomicInteger(0)
    val scheduled = AtomicLong(0)
    val submitted = AtomicLong(0)
    val completed = AtomicLong(0)
    val succeeded = AtomicLong(0)
    val failed = AtomicLong(0)
    val localBackpressure = AtomicLong(0)

    val startedAt = Instant.now()
    val startedNanos = System.nanoTime()
    val durationNanos = config.duration.toNanos()
    val intervalNanos = 1_000_000_000.0 / config.ratePerSecond.toDouble()
    val targetRequests = ceil(config.duration.toMillis().toDouble() / 1000.0 * config.ratePerSecond.toDouble()).toLong()

    var sequence = 0L
    while (sequence < targetRequests) {
        val deadline = startedNanos + (sequence.toDouble() * intervalNanos).toLong()
        waitUntil(deadline)
        if (System.nanoTime() - startedNanos > durationNanos) break

        scheduled.incrementAndGet()
        while (inFlight.get() >= config.maxInFlight) {
            localBackpressure.incrementAndGet()
            LockSupport.parkNanos(100_000L)
        }

        val envelope = envelope(sequence, config)
        val submittedAt = System.nanoTime()
        inFlight.incrementAndGet()
        submitted.incrementAndGet()
        try {
            asyncPublisher.publishAsync(envelope).whenComplete { _, failure ->
                val elapsedMs = Duration.ofNanos(System.nanoTime() - submittedAt).toMillis()
                histogram.record(elapsedMs)
                completed.incrementAndGet()
                inFlight.decrementAndGet()
                if (failure == null) {
                    succeeded.incrementAndGet()
                } else {
                    failed.incrementAndGet()
                    errors.computeIfAbsent(errorKey(failure)) { AtomicLong(0L) }.incrementAndGet()
                }
            }
        } catch (ex: Exception) {
            histogram.record(Duration.ofNanos(System.nanoTime() - submittedAt).toMillis())
            completed.incrementAndGet()
            failed.incrementAndGet()
            inFlight.decrementAndGet()
            errors.computeIfAbsent(errorKey(ex)) { AtomicLong(0L) }.incrementAndGet()
        }
        sequence++
    }

    val drainDeadline = System.nanoTime() + config.drainWait.toNanos()
    while (inFlight.get() > 0 && System.nanoTime() < drainDeadline) {
        LockSupport.parkNanos(1_000_000L)
    }

    val finishedAt = Instant.now()
    val elapsedSeconds = Duration.between(startedAt, finishedAt).toNanos().toDouble() / 1_000_000_000.0
    val timedOut = inFlight.get().toLong()
    val report = buildReport(
        config = config,
        startedAt = startedAt,
        finishedAt = finishedAt,
        elapsedSeconds = elapsedSeconds,
        targetRequests = targetRequests,
        scheduled = scheduled.get(),
        submitted = submitted.get(),
        completed = completed.get(),
        succeeded = succeeded.get(),
        failed = failed.get(),
        timedOut = timedOut,
        localBackpressure = localBackpressure.get(),
        histogram = histogram,
        errors = errors,
        health = healthCheck?.snapshot()
    )
    println(report)
    config.reportOut?.let { path ->
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, report + "\n")
    }
}

private fun warmup(publisher: AsyncStreamCommandPublisher, config: StreamPublishBenchConfig) {
    val futures = (0 until config.warmupMessages).map { index ->
        publisher.publishAsync(envelope(index.toLong(), config, warmup = true))
    }
    CompletableFuture.allOf(*futures.toTypedArray()).get(config.drainWait.toMillis().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
}

private fun waitUntil(deadlineNanos: Long) {
    while (true) {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) return
        if (remaining > 1_000_000L) {
            LockSupport.parkNanos(remaining - 500_000L)
        } else {
            Thread.onSpinWait()
        }
    }
}

private fun envelope(sequence: Long, config: StreamPublishBenchConfig, warmup: Boolean = false): StreamCommandEnvelope {
    val prefix = if (warmup) "warmup" else "bench"
    val commandId = "${config.runId}-$prefix-$sequence"
    val instrument = when (config.partitionMode) {
        "single" -> config.instrumentPrefix + "001"
        "spread64" -> config.instrumentPrefix + ((sequence % 64L) + 1L).toString().padStart(3, '0')
        else -> config.instrumentPrefix + ((sequence % config.partitionCount.toLong()) + 1L).toString().padStart(3, '0')
    }
    val partition = when (config.partitionMode) {
        "single" -> 0
        "spread64" -> StreamCommandEnvelopeBuilder.partition(config.runId, config.venueSessionId, instrument, config.partitionCount)
        else -> (sequence % config.partitionCount.toLong()).toInt()
    }
    val subject = StreamCommandEnvelopeBuilder.subject(
        config.subjectPrefix,
        partition,
        config.partitionCount,
        config.venueSessionId,
        instrument,
        "SubmitOrder"
    )
    val payload = payloadJson(commandId, sequence, config, instrument)
    return StreamCommandEnvelope(
        clientId = "stream-publish-bench",
        route = "/api/v1/orders/submit",
        idempotencyKey = commandId,
        payloadHash = commandId,
        commandId = commandId,
        commandType = "SubmitOrder",
        runId = config.runId,
        venueSessionId = config.venueSessionId,
        instrumentId = instrument,
        participantId = "bench-participant",
        orderId = "ord-$sequence",
        clientOrderId = "client-ord-$sequence",
        actorId = "bench-actor",
        traceId = "trace-$sequence",
        correlationId = "trace-$sequence",
        causationId = "",
        botId = "",
        botVersion = "",
        subject = subject,
        partition = partition,
        payloadJson = payload
    )
}

private fun payloadJson(commandId: String, sequence: Long, config: StreamPublishBenchConfig, instrument: String): String {
    val base = """{"commandId":"$commandId","runId":"${config.runId}","venueSessionId":"${config.venueSessionId}","instrumentId":"$instrument","participantId":"bench-participant","orderId":"ord-$sequence","actorId":"bench-actor","side":"BUY","quantity":100,"priceNanos":101000000000"""
    val fillerSize = max(0, config.payloadBytes - base.length - 14)
    val filler = if (fillerSize == 0) "" else ""","filler":"${"x".repeat(fillerSize)}""""
    return "$base$filler}"
}

private fun errorKey(failure: Throwable): String {
    val cause = generateSequence(failure) { it.cause }.last()
    return "${cause.javaClass.simpleName}:${cause.message ?: ""}".take(180)
}

private fun buildReport(
    config: StreamPublishBenchConfig,
    startedAt: Instant,
    finishedAt: Instant,
    elapsedSeconds: Double,
    targetRequests: Long,
    scheduled: Long,
    submitted: Long,
    completed: Long,
    succeeded: Long,
    failed: Long,
    timedOut: Long,
    localBackpressure: Long,
    histogram: MillisecondHistogram,
    errors: Map<String, AtomicLong>,
    health: com.reef.platform.api.StreamCommandHealthSnapshot?
): String {
    val successRate = if (completed == 0L) 0.0 else succeeded.toDouble() * 100.0 / completed.toDouble()
    val throughput = if (elapsedSeconds <= 0.0) 0.0 else completed.toDouble() / elapsedSeconds
    val acceptedThroughput = if (elapsedSeconds <= 0.0) 0.0 else succeeded.toDouble() / elapsedSeconds
    return buildString {
        append("{")
        appendJson("startedAt", startedAt.toString()).append(',')
        appendJson("finishedAt", finishedAt.toString()).append(',')
        appendJson("durationSeconds", elapsedSeconds).append(',')
        append("\"config\":").append(config.toJson()).append(',')
        appendJson("targetRequests", targetRequests).append(',')
        appendJson("scheduled", scheduled).append(',')
        appendJson("submitted", submitted).append(',')
        appendJson("completed", completed).append(',')
        appendJson("succeeded", succeeded).append(',')
        appendJson("failed", failed).append(',')
        appendJson("timedOut", timedOut).append(',')
        appendJson("localBackpressure", localBackpressure).append(',')
        appendJson("throughputRps", throughput).append(',')
        appendJson("acceptedRps", acceptedThroughput).append(',')
        appendJson("successRatePct", successRate).append(',')
        append("\"latencyMs\":").append(histogram.toJson()).append(',')
        append("\"errors\":").append(errorsToJson(errors)).append(',')
        append("\"hotPath\":").append(anyToJson(HotPathMetrics.snapshot())).append(',')
        append("\"publisherHealth\":").append(healthToJson(health))
        append("}")
    }
}

private data class StreamPublishBenchConfig(
    val ratePerSecond: Int,
    val duration: Duration,
    val drainWait: Duration,
    val maxInFlight: Int,
    val partitionCount: Int,
    val partitionMode: String,
    val payloadBytes: Int,
    val maxLatencyBucketMs: Int,
    val warmupMessages: Int,
    val runId: String,
    val venueSessionId: String,
    val subjectPrefix: String,
    val instrumentPrefix: String,
    val reportOut: Path?
) {
    fun toJson(): String = buildString {
        append("{")
        appendJson("ratePerSecond", ratePerSecond).append(',')
        appendJson("durationMs", duration.toMillis()).append(',')
        appendJson("drainWaitMs", drainWait.toMillis()).append(',')
        appendJson("maxInFlight", maxInFlight).append(',')
        appendJson("partitionCount", partitionCount).append(',')
        appendJson("partitionMode", partitionMode).append(',')
        appendJson("payloadBytes", payloadBytes).append(',')
        appendJson("warmupMessages", warmupMessages).append(',')
        appendJson("runId", runId).append(',')
        appendJson("venueSessionId", venueSessionId)
        append("}")
    }

    companion object {
        fun fromEnv(): StreamPublishBenchConfig {
            val streamConfig = StreamCommandConfig()
            return StreamPublishBenchConfig(
                ratePerSecond = envInt("STREAM_PUBLISH_BENCH_RATE", 10_000, min = 1),
                duration = envDuration("STREAM_PUBLISH_BENCH_DURATION", Duration.ofSeconds(60)),
                drainWait = envDuration("STREAM_PUBLISH_BENCH_DRAIN_WAIT", Duration.ofSeconds(30)),
                maxInFlight = envInt("STREAM_PUBLISH_BENCH_MAX_IN_FLIGHT", 100_000, min = 1),
                partitionCount = streamConfig.partitionCount,
                partitionMode = envString("STREAM_PUBLISH_BENCH_PARTITION_MODE", "round-robin"),
                payloadBytes = envInt("STREAM_PUBLISH_BENCH_PAYLOAD_BYTES", 768, min = 128),
                maxLatencyBucketMs = envInt("STREAM_PUBLISH_BENCH_MAX_LATENCY_BUCKET_MS", 10_000, min = 100),
                warmupMessages = envInt("STREAM_PUBLISH_BENCH_WARMUP_MESSAGES", 1_000, min = 0),
                runId = envString("STREAM_PUBLISH_BENCH_RUN_ID", "stream-publish-bench"),
                venueSessionId = envString("STREAM_PUBLISH_BENCH_VENUE_SESSION_ID", "bench-session"),
                subjectPrefix = streamConfig.subjectPrefix,
                instrumentPrefix = envString("STREAM_PUBLISH_BENCH_INSTRUMENT_PREFIX", "STK"),
                reportOut = envString("STREAM_PUBLISH_BENCH_REPORT_OUT", "").ifBlank { null }?.let { Path.of(it) }
            )
        }
    }
}

private class MillisecondHistogram(private val maxBucketMs: Int) {
    private val buckets = AtomicLongArray(maxBucketMs + 2)
    private val total = AtomicLong(0L)
    private val maxObserved = AtomicLong(0L)

    fun record(latencyMs: Long) {
        val bucket = latencyMs.coerceIn(0L, (maxBucketMs + 1).toLong()).toInt()
        buckets.incrementAndGet(bucket)
        total.incrementAndGet()
        maxObserved.accumulateAndGet(latencyMs, ::max)
    }

    fun percentile(percentile: Double): Long {
        val count = total.get()
        if (count == 0L) return 0L
        val rank = ceil(count.toDouble() * percentile).toLong().coerceAtLeast(1L)
        var seen = 0L
        for (bucket in 0 until buckets.length()) {
            seen += buckets.get(bucket)
            if (seen >= rank) return if (bucket > maxBucketMs) maxObserved.get() else bucket.toLong()
        }
        return maxObserved.get()
    }

    fun toJson(): String = buildString {
        append("{")
        appendJson("count", total.get()).append(',')
        appendJson("p50", percentile(0.50)).append(',')
        appendJson("p95", percentile(0.95)).append(',')
        appendJson("p99", percentile(0.99)).append(',')
        appendJson("max", maxObserved.get())
        append("}")
    }
}

private fun envString(name: String, fallback: String): String {
    return System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}

private fun envInt(name: String, fallback: Int, min: Int): Int {
    return envString(name, "").toIntOrNull()?.coerceAtLeast(min) ?: fallback
}

private fun envDuration(name: String, fallback: Duration): Duration {
    val raw = envString(name, "")
    if (raw.isBlank()) return fallback
    return when {
        raw.endsWith("ms") -> Duration.ofMillis(raw.removeSuffix("ms").toLong())
        raw.endsWith("s") -> Duration.ofSeconds(raw.removeSuffix("s").toLong())
        raw.endsWith("m") -> Duration.ofMinutes(raw.removeSuffix("m").toLong())
        else -> Duration.ofSeconds(raw.toLong())
    }
}

private fun StringBuilder.appendJson(name: String, value: String): StringBuilder {
    append('"').append(escapeJson(name)).append("\":\"").append(escapeJson(value)).append('"')
    return this
}

private fun StringBuilder.appendJson(name: String, value: Long): StringBuilder {
    append('"').append(escapeJson(name)).append("\":").append(value)
    return this
}

private fun StringBuilder.appendJson(name: String, value: Int): StringBuilder {
    append('"').append(escapeJson(name)).append("\":").append(value)
    return this
}

private fun StringBuilder.appendJson(name: String, value: Double): StringBuilder {
    append('"').append(escapeJson(name)).append("\":").append(value)
    return this
}

private fun StringBuilder.appendJson(name: String, value: Boolean): StringBuilder {
    append('"').append(escapeJson(name)).append("\":").append(value)
    return this
}

private fun errorsToJson(errors: Map<String, AtomicLong>): String {
    return errors.entries
        .sortedByDescending { it.value.get() }
        .joinToString(prefix = "[", postfix = "]") { entry ->
            """{"error":"${escapeJson(entry.key)}","count":${entry.value.get()}}"""
        }
}

private fun healthToJson(health: com.reef.platform.api.StreamCommandHealthSnapshot?): String {
    if (health == null) return "null"
    return buildString {
        append("{")
        appendJson("available", health.available).append(',')
        appendJson("streamName", health.streamName).append(',')
        appendJson("messageCount", health.messageCount).append(',')
        appendJson("publishMode", health.publishMode).append(',')
        appendJson("publishInFlight", health.publishInFlight).append(',')
        appendJson("publishMaxInFlight", health.publishMaxInFlight).append(',')
        appendJson("publishQueueDepth", health.publishQueueDepth).append(',')
        appendJson("publishAccepted", health.publishAccepted).append(',')
        appendJson("publishCompleted", health.publishCompleted).append(',')
        appendJson("publishFailed", health.publishFailed).append(',')
        appendJson("publishRejected", health.publishRejected).append(',')
        appendJson("publishAckLastMs", health.publishAckLastMs).append(',')
        appendJson("publishAckMaxMs", health.publishAckMaxMs).append(',')
        appendJson("publishQueueWaitMaxMs", health.publishQueueWaitMaxMs).append(',')
        appendJson("publishSlotWaitMaxMs", health.publishSlotWaitMaxMs).append(',')
        appendJson("publishDelegateAckMaxMs", health.publishDelegateAckMaxMs).append(',')
        appendJson("publishPipelineTotalMaxMs", health.publishPipelineTotalMaxMs)
        append("}")
    }
}

@Suppress("UNCHECKED_CAST")
private fun anyToJson(value: Any?): String {
    return when (value) {
        null -> "null"
        is String -> "\"${escapeJson(value)}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
            "\"${escapeJson(key.toString())}\":${anyToJson(entryValue)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { anyToJson(it) }
        else -> "\"${escapeJson(value.toString())}\""
    }
}

private fun escapeJson(value: String): String {
    return buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
