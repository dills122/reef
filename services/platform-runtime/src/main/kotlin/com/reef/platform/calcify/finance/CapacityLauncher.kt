package com.reef.platform.calcify.finance

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import org.apache.kafka.streams.KafkaStreams
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

internal data class CapacityTopics(val capture: String, val control: String, val decisions: String)

/** The Streams settings of the capacity app: the finite profile's pinned exactly-once configuration with one stream thread per lane. */
internal object CapacityStreams {
    fun properties(applicationId: String, bootstrap: String, stateDir: String, threads: Int): Properties =
        FinanceTopology.streamsProperties(applicationId, bootstrap, stateDir).apply { put("num.stream.threads", threads) }

    fun verifyPinned(props: Properties, lanes: Int) {
        val pinned = mapOf("processing.guarantee" to "exactly_once_v2", "num.standby.replicas" to "0", "auto.offset.reset" to "earliest")
        for ((k, v) in pinned) require(props[k]?.toString() == v) { "capacity Streams property $k must be $v" }
        val threads = props["num.stream.threads"]?.toString()?.toIntOrNull()
        require(threads != null && threads in 1..lanes) { "num.stream.threads must be between 1 and the $lanes lanes" }
        require(props["commit.interval.ms"] != null && props["max.task.idle.ms"] != null) { "commit.interval.ms and max.task.idle.ms must be pinned" }
        require(props["producer.batch.size"]?.toString()?.toIntOrNull() == FinanceTopology.PRODUCER_BATCH_BYTES) { "producer.batch.size must be pinned at ${FinanceTopology.PRODUCER_BATCH_BYTES}" }
        require(props["producer.linger.ms"]?.toString()?.toIntOrNull() == FinanceTopology.PRODUCER_LINGER_MS) { "producer.linger.ms must be pinned at ${FinanceTopology.PRODUCER_LINGER_MS}" }
    }
}

/**
 * Starts the capacity profile as one service (design stage S4c): per lane a decision-log head is read first and the Streams app refuses to run a lane whose restored
 * store is not exactly at it; each lane's projection is then claimed (owner epoch) and tailed on its own thread into the lane's own binding; then the app starts with one
 * stream thread per lane (task N is lane N). Any failure before the app exists leaves nothing running. The topics must exist with at least [CapacityPlan.lanes] partitions
 * (capture, control and decisions all co-partitioned by lane); creating them is the operator's step, as in the finite profile.
 *
 * What the finite launcher checks that this one does not: it verified the whole decision log from genesis and cross-checked a per-record history list. The capacity
 * profile has neither (a 5-minute slice holds millions of decisions); the store verifies itself instead (its entities must hash to the head decision's committed
 * state, [CapacityStore.restore]) and must be the head of the published log (above).
 */
internal class CapacityLauncher(
    private val plan: CapacityPlan, private val decoder: CapacityInputDecoder, private val topics: CapacityTopics, private val applicationId: String,
    private val evidenceProperties: Properties, private val streamsClientProperties: Properties, private val stateDir: String, private val dataSource: DataSource,
    private val threads: Int = plan.lanes, private val projectionIntervalMs: Long = 100,
    private val maxTransientProjectionFailures: Int = 10, private val persistent: Boolean = true,
    /** Decisions per projector transaction. Position rows are coalesced per transaction, so a larger chunk writes fewer rows per decision (and makes each commit, and the read lag behind it, longer). */
    private val maxApply: Int = 2_000,
    /** Head and meta are written to the store every this many decisions instead of with every record; see [CapacityProcessorConfig.checkpointEvery]. */
    private val checkpointEvery: Int = 1,
) {
    class Running internal constructor(
        private val streams: KafkaStreams, private val loops: List<Thread>, private val failure: AtomicReference<Throwable?>,
        private val streamsFailed: AtomicBoolean, private val closing: AtomicBoolean, private val logs: List<AutoCloseable>,
        /** Nanoseconds the projection threads spent reading and decoding the decision logs (`readNanos`) and in the projector's apply (`applyNanos`), summed over lanes. */
        val readNanos: java.util.concurrent.atomic.AtomicLong, val applyNanos: () -> Long, val projectorNanos: () -> Pair<Long, Long> = { 0L to 0L },
    ) : AutoCloseable {
        fun projectionFailure(): Throwable? = failure.get()
        fun streamsState(): KafkaStreams.State = streams.state()
        fun healthy(): Boolean = failure.get() == null && !streamsFailed.get() && streams.state().let { it == KafkaStreams.State.RUNNING || it == KafkaStreams.State.REBALANCING || it == KafkaStreams.State.CREATED }
        fun isClosing(): Boolean = closing.get()
        override fun close() {
            if (closing.getAndSet(true)) return
            try { loops.forEach { it.interrupt() }; loops.forEach { it.join(10_000) } } finally {
                logs.forEach { runCatching { it.close() } }
                FinanceTopology.closeLeavingGroup(streams, java.time.Duration.ofSeconds(30))
            }
        }
    }

    fun start(): Running {
        val lanes = plan.lanes
        val reader = FinanceEvidenceReader(evidenceProperties, applicationId, plan.planId, topics.capture, topics.control, topics.decisions, "$applicationId-capacity-finance-changelog")
        val heads: List<Decision?> = (0 until lanes).map { lane ->
            reader.lastValue(topics.decisions, lane)?.let { raw -> FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(raw)).first }
        }
        val properties = CapacityStreams.properties(applicationId, requireNotNull(streamsClientProperties["bootstrap.servers"]) { "bootstrap.servers required" }.toString(), stateDir, threads)
            .apply { streamsClientProperties.forEach { (k, v) -> if (k != "bootstrap.servers") put(k, v) } }
        CapacityStreams.verifyPinned(properties, lanes)

        val logs = ArrayList<AutoCloseable>()
        val readNanos = java.util.concurrent.atomic.AtomicLong()
        val runners: List<FinanceProjectionRunner>
        val projectors = ArrayList<FinanceProjector>()
        val streams: KafkaStreams
        try {
            runners = (0 until lanes).map { lane ->
                val digest = plan.bindingDigest(lane)
                val projector = FinanceProjector(CapacityChain(plan.capacity(lane)), dataSource).also { projectors += it }
                val epoch = projector.claim(digest, plan.sequenceBound)
                val log = reader.logReader(topics.decisions, lane).also { logs += it }
                FinanceProjectionRunner.tailing({ val s = System.nanoTime(); FinanceProjectionRunner.decode(log.readNext()).also { readNanos.addAndGet(System.nanoTime() - s) } }, projector, digest, epoch, maxApply = maxApply, sequenceBound = plan.sequenceBound).also { it.open() }
            }
            val config = CapacityProcessorConfig(plan, topics.capture, topics.control, topics.decisions, applicationId, logHead = { heads[it] }, failOnBadCapture = true, checkpointEvery = checkpointEvery)
            streams = KafkaStreams(CapacityTopology.build(config, decoder, persistent), properties)
        } catch (e: Throwable) { logs.forEach { runCatching { it.close() } }; throw e }
        val streamsFailed = AtomicBoolean(false); val closing = AtomicBoolean(false); val failure = AtomicReference<Throwable?>(null)
        streams.setUncaughtExceptionHandler { streamsFailed.set(true); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
        streams.setStateListener { newState, _ -> if (newState == KafkaStreams.State.ERROR && !closing.get()) streamsFailed.set(true) }
        try {
            streams.start()
            val loops = runners.mapIndexed { lane, runner ->
                Thread {
                    var consecutive = 0
                    while (!closing.get() && !Thread.currentThread().isInterrupted) {
                        try {
                            if (runner.runOnce() == 0) Thread.sleep(projectionIntervalMs)
                            consecutive = 0
                        } catch (_: InterruptedException) { break }
                        catch (e: Throwable) {
                            if (closing.get()) break
                            if (FiniteFinanceLauncher.isFatal(e) || ++consecutive >= maxTransientProjectionFailures) { failure.compareAndSet(null, e); break }
                            try { Thread.sleep(minOf(projectionIntervalMs * (1L shl minOf(consecutive, 6)), 10_000)) } catch (_: InterruptedException) { break }
                        }
                    }
                }.apply { isDaemon = true; name = "capacity-projection-$lane"; start() }
            }
            return Running(streams, loops, failure, streamsFailed, closing, logs, readNanos, { runners.sumOf { it.applyNanos.get() } }, { projectors.sumOf { it.verifyNanos.get() } to projectors.sumOf { it.sqlNanos.get() } })
        } catch (e: Throwable) {
            runCatching { FinanceTopology.closeLeavingGroup(streams, java.time.Duration.ofSeconds(10)) }
            logs.forEach { runCatching { it.close() } }
            throw e
        }
    }
}
