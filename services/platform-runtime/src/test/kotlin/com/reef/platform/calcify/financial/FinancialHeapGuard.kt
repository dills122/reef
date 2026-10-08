package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Conservative finite heap contract. Does not bound native/RSS memory or guarantee OOM prevention. */
data class FinancialHeapBounds(
    val identityHeapBytesUpper: Long,
    val pendingHeapBytesUpper: Long,
    val baselineHeapBytesUpper: Long,
    val transientHeapReserveBytes: Long,
    val replayHeapReserveBytes: Long,
    val supportedIdentities: Long,
    val supportedPendingItems: Long,
) {
    init { require(listOf(identityHeapBytesUpper, pendingHeapBytesUpper, baselineHeapBytesUpper,
        transientHeapReserveBytes, replayHeapReserveBytes, supportedIdentities, supportedPendingItems).all { it > 0 }) { "HEAP_CONSERVATIVE_EVIDENCE_REQUIRED" } }

    fun estimate(timed: Long, aged: Long, pending: Long): Long {
        require(timed >= 0 && aged >= 0 && pending >= 0) { "HEAP_INVALID_COUNTS" }
        val identities = Math.addExact(timed, aged)
        require(identities <= supportedIdentities && pending <= supportedPendingItems) { "HEAP_UNSUPPORTED_COUNTS" }
        return Math.addExact(Math.addExact(Math.addExact(baselineHeapBytesUpper,
            Math.multiplyExact(identities, identityHeapBytesUpper)), Math.multiplyExact(pending, pendingHeapBytesUpper)),
            Math.addExact(transientHeapReserveBytes, replayHeapReserveBytes))
    }

    companion object {
        const val AUTHORIZED_MAX = 768L * 1024 * 1024
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        fun limit(max: Long): Long {
            require(max in 1..AUTHORIZED_MAX) { "HEAP_UNAUTHORIZED_OR_UNKNOWN_MAX" }
            return Math.multiplyExact(max, 4L) / 5L
        }
        fun integer(n: JsonNode?, name: String, positive: Boolean = true): Long {
            require(n != null && n.isIntegralNumber && n.canConvertToLong()) { "HEAP_INVALID_$name" }
            val value = n.longValue()
            require(value in (if (positive) 1L else 0L)..MAX_SAFE_INTEGER) { "HEAP_INVALID_$name" }
            return value
        }
        fun from(n: JsonNode): FinancialHeapBounds = FinancialHeapBounds(
            integer(n["identityHeapBytesUpper"], "identityHeapBytesUpper"), integer(n["pendingHeapBytesUpper"], "pendingHeapBytesUpper"),
            integer(n["baselineHeapBytesUpper"], "baselineHeapBytesUpper"), integer(n["transientHeapReserveBytes"], "transientHeapReserveBytes"),
            integer(n["replayHeapReserveBytes"], "replayHeapReserveBytes"), integer(n["supportedIdentities"], "supportedIdentities"),
            integer(n["supportedPendingItems"], "supportedPendingItems"))
    }
}

data class FinancialHeapSnapshot(val maxHeapBytes: Long, val usedHeapBytes: Long, val sampledAtNano: Long)

/** Dedicated heap-only sensor. First breach/error/stale observation is sticky, including after GC. */
class FinancialHeapGuard private constructor(
    private val profile: Profile,
    private val capabilityMax: Long,
    private val sensor: () -> FinancialHeapSnapshot,
    private val clock: () -> Long,
    private val pollMillis: Long,
    private val maxSampleAgeNanos: Long,
) : AutoCloseable {
    private sealed interface Profile {
        data class Conservative(val bounds: FinancialHeapBounds, val estimate: Long) : Profile
        data class DiagnosticBootstrap(val preloadOperationalCeilingBytes: Long?) : Profile
        data class EmpiricalTimed(val authorizedMax: Long) : Profile
    }

    constructor(
        bounds: FinancialHeapBounds,
        estimatedHeapBytes: Long,
        capabilityMax: Long,
        sensor: () -> FinancialHeapSnapshot = ::runtimeSnapshot,
        clock: () -> Long = System::nanoTime,
        pollMillis: Long = 250,
        maxSampleAgeNanos: Long = 5_000_000_000L,
    ) : this(Profile.Conservative(bounds, estimatedHeapBytes), capabilityMax, sensor, clock, pollMillis, maxSampleAgeNanos)

    companion object {
        const val EMPIRICAL_MAX = 4L * 1024 * 1024 * 1024
        const val EMPIRICAL_8G_MAX = 8L * 1024 * 1024 * 1024
        internal fun empiricalTimed(capabilityMax: Long,
            sensor: () -> FinancialHeapSnapshot = ::runtimeSnapshot, clock: () -> Long = System::nanoTime,
            authorizedMax: Long = EMPIRICAL_MAX): FinancialHeapGuard {
            require(authorizedMax in listOf(EMPIRICAL_MAX, EMPIRICAL_8G_MAX) && capabilityMax == authorizedMax) { "EMPIRICAL_PINNED_MAX_REQUIRED" }
            return FinancialHeapGuard(Profile.EmpiricalTimed(authorizedMax), capabilityMax, sensor, clock, 250, 5_000_000_000L)
        }
        private fun runtimeSnapshot() = FinancialHeapSnapshot(Runtime.getRuntime().maxMemory(),
            ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, System.nanoTime())

        /** Empirical fixed-cohort diagnostic only; caller separately validates frozen request identity/config. */
        internal fun diagnosticBootstrap(
            timedTrades: Long,
            pendingItems: Long,
            agedIdentities: Long,
            capabilityMax: Long,
            preloadOperationalCeilingBytes: Long? = null,
            sensor: () -> FinancialHeapSnapshot = ::runtimeSnapshot,
            clock: () -> Long = System::nanoTime,
            pollMillis: Long = 250,
            maxSampleAgeNanos: Long = 5_000_000_000L,
        ): FinancialHeapGuard {
            require(timedTrades == 1000L && pendingItems == 100L && agedIdentities == 0L) { "HEAP_BOOTSTRAP_FIXED_COHORT_REQUIRED" }
            val limit = FinancialHeapBounds.limit(capabilityMax)
            require(preloadOperationalCeilingBytes == null || preloadOperationalCeilingBytes in 1 until limit) { "HEAP_PRELOAD_OPERATIONAL_CEILING_INVALID" }
            return FinancialHeapGuard(Profile.DiagnosticBootstrap(preloadOperationalCeilingBytes), capabilityMax,
                sensor, clock, pollMillis, maxSampleAgeNanos)
        }
    }
    private val firstFailure = AtomicReference<Throwable>()
    private val latest = AtomicReference<FinancialHeapSnapshot>()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val published = AtomicBoolean(false)
    private val peak = AtomicLong()
    private var thread: Thread? = null
    private val baselineObservations = mutableListOf<Map<String, Any>>()
    var actualMaxHeapBytes: Long = 0; private set
    var admissionLimitBytes: Long = 0; private set
    val isClosed get() = closed.get()
    val failure get() = firstFailure.get()

    init {
        require(pollMillis in 1..250 && maxSampleAgeNanos in 1..5_000_000_000L) { "HEAP_SENSOR_CADENCE" }
        profileLimit(capabilityMax)
        if (profile is Profile.Conservative)
            require(profile.estimate > 0 && profile.estimate <= FinancialHeapBounds.MAX_SAFE_INTEGER) { "HEAP_ESTIMATE_INVALID" }
    }
    private fun profileLimit(max: Long): Long {
        if (profile !is Profile.EmpiricalTimed) return FinancialHeapBounds.limit(max)
        require(max == profile.authorizedMax) { "EMPIRICAL_PINNED_MAX_REQUIRED" }
        return Math.multiplyExact(max, 4L) / 5L
    }
    private fun fail(error: Throwable) { firstFailure.compareAndSet(null, error) }
    fun refresh(stage: String) {
        if (closed.get()) { fail(IllegalStateException("HEAP_GUARD_CLOSED stage=$stage")); checkpoint(stage) }
        if (failure != null) checkpoint(stage)
        try {
            val s = sensor(); val now = clock()
            require(s.usedHeapBytes >= 0 && s.usedHeapBytes <= s.maxHeapBytes) { "HEAP_INVALID_USED" }
            val effective = minOf(capabilityMax, s.maxHeapBytes)
            val limit = profileLimit(s.maxHeapBytes).let { minOf(it, profileLimit(effective)) }
            require(now >= s.sampledAtNano && now - s.sampledAtNano < maxSampleAgeNanos) { "HEAP_SAMPLE_STALE" }
            if (actualMaxHeapBytes == 0L) {
                actualMaxHeapBytes = s.maxHeapBytes; admissionLimitBytes = limit
            }
            require(s.maxHeapBytes == actualMaxHeapBytes) { "HEAP_MAX_CHANGED" }
            when (val p = profile) {
                is Profile.EmpiricalTimed -> Unit
                is Profile.Conservative -> require(p.estimate < limit) { "HEAP_ADMISSION_LIMIT" }
                is Profile.DiagnosticBootstrap -> require(p.preloadOperationalCeilingBytes == null || p.preloadOperationalCeilingBytes < limit) { "HEAP_PRELOAD_OPERATIONAL_CEILING_INVALID" }
            }
            require(s.usedHeapBytes < limit) { "HEAP_OBSERVED_LIMIT" }
            latest.set(s); peak.accumulateAndGet(s.usedHeapBytes, ::maxOf)
        } catch (error: Throwable) { fail(IllegalStateException("heap failure stage=$stage", error)) }
        checkpoint(stage)
    }
    fun checkpoint(stage: String) {
        if (closed.get() && stage != "final-publication" && !(stage == "lifecycle-return" && published.get()))
            fail(IllegalStateException("HEAP_GUARD_CLOSED stage=$stage"))
        failure?.let { throw IllegalStateException("heap protection refused stage=$stage", it) }
        if (stage == "lifecycle-return" && published.get()) return
        val snapshot = latest.get() ?: run { fail(IllegalStateException("HEAP_SAMPLE_MISSING stage=$stage")); checkpoint(stage); return }
        val now = clock()
        if (now < snapshot.sampledAtNano || now - snapshot.sampledAtNano >= maxSampleAgeNanos) {
            fail(IllegalStateException("HEAP_SAMPLE_STALE stage=$stage")); checkpoint(stage)
        }
    }
    fun baseline(stage: String) {
        refresh(stage)
        val s = latest.get()
        when (val p = profile) {
            is Profile.EmpiricalTimed -> Unit
            is Profile.Conservative -> if (s.usedHeapBytes > p.bounds.baselineHeapBytesUpper) {
                fail(IllegalStateException("HEAP_BASELINE_BOUND_EXCEEDED stage=$stage")); checkpoint(stage)
            }
            is Profile.DiagnosticBootstrap -> if (p.preloadOperationalCeilingBytes != null && s.usedHeapBytes >= p.preloadOperationalCeilingBytes) {
                fail(IllegalStateException("HEAP_PRELOAD_OPERATIONAL_CEILING_EXCEEDED stage=$stage")); checkpoint(stage)
            }
        }
        synchronized(baselineObservations) {
            require(baselineObservations.size < 16)
            baselineObservations.add(mapOf("stage" to stage, "sampledAtNano" to s.sampledAtNano, "usedHeapBytes" to s.usedHeapBytes, "maxHeapBytes" to s.maxHeapBytes))
        }
    }
    fun start() {
        check(!closed.get() && running.compareAndSet(false, true))
        baseline("before-setup")
        thread = Thread({
            try {
                while (running.get()) { Thread.sleep(pollMillis); if (running.get()) refresh("heap-sensor") }
            } catch (error: InterruptedException) { if (running.get()) fail(error) }
            catch (error: Throwable) { fail(error) }
        }, "financial-heap-guard").apply { isDaemon = true; start() }
    }
    fun telemetry(): Map<String, Any?> = mapOf("schema" to when (profile) { is Profile.Conservative -> "financial-heap-observation-v1"; is Profile.DiagnosticBootstrap -> "financial-bootstrap-heap-observation-v1"; is Profile.EmpiricalTimed -> "financial-empirical-heap-observation-v1" }, "guardOutcome" to if (failure != null) "FAILED" else if (latest.get() == null) "UNSTARTED" else "PASS_SAMPLED", "actualMaxHeapBytes" to actualMaxHeapBytes,
        "admissionLimitBytes" to admissionLimitBytes, "estimatedHeapBytes" to (profile as? Profile.Conservative)?.estimate, "heapPeakBytes" to peak.get(),
        "baselineObservations" to synchronized(baselineObservations) { baselineObservations.toList() },
        "pollMillis" to pollMillis, "maxSampleAgeNanos" to maxSampleAgeNanos, "sampled" to true,
        "limitation" to "Sudden allocation may outrun sampled protection; native/RSS memory unbounded; no OOM guarantee") +
        if (profile is Profile.DiagnosticBootstrap) mapOf("purpose" to "EMPIRICAL_BOUNDED_DIAGNOSTIC", "heapConservativeBound" to false,
            "preloadOperationalCeilingBytes" to profile.preloadOperationalCeilingBytes) else if (profile is Profile.EmpiricalTimed) mapOf("purpose" to "EMPIRICAL_TIMED_DIAGNOSTIC", "heapConservativeBound" to false) else emptyMap()

    /** Stage bytes first; sensor stops and final check succeeds before atomic publication. */
    fun publish(path: Path, contents: String, auxiliary: Pair<Path, String>? = null) {
        refresh("final-result")
        require(!Files.exists(path) && (auxiliary == null || !Files.exists(auxiliary.first))) { "HEAP_RESULT_ALREADY_EXISTS" }
        val temp = Files.createTempFile(path.toAbsolutePath().parent, ".heap-result-", ".tmp")
        var auxiliaryTemp: Path? = null; var auxiliaryMoved = false
        try {
            Files.writeString(temp, contents)
            auxiliary?.let { (file, content) ->
                auxiliaryTemp = Files.createTempFile(file.toAbsolutePath().parent, ".heap-parity-", ".tmp")
                Files.writeString(auxiliaryTemp, content)
            }
            refresh("final-artifact-staged"); close(); checkpoint("final-publication")
            auxiliary?.let { Files.move(auxiliaryTemp!!, it.first, StandardCopyOption.ATOMIC_MOVE); auxiliaryMoved = true }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE); published.set(true)
        } finally {
            Files.deleteIfExists(temp); auxiliaryTemp?.let { Files.deleteIfExists(it) }
            if (auxiliaryMoved && !published.get()) Files.deleteIfExists(auxiliary!!.first)
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false); thread?.interrupt(); thread?.join(2000)
        if (thread?.isAlive == true) fail(IllegalStateException("HEAP_SENSOR_CLOSE_TIMEOUT"))
    }
}
