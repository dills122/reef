package com.reef.platform.calcify.finance

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.streams.KafkaStreams

/**
 * The finite P3 activation sequence (S3-A2 launcher), in the only order the restore design allows:
 *  1. gather broker evidence with the READ-ONLY principal ([FinanceEvidenceReader], spec section 9 `observer`);
 *  2. verify the restore cut ([FinanceRestoreCut]); on any refusal NOTHING is started: no Streams app, no projector, no database access;
 *  3. build and open the owner-fenced projection (the only database access), then start the finance Streams app under the `finance`
 *     principal with the spec's pinned configuration ([FinanceTopology.verifyPinned]);
 *  4. run projection passes until closed, retrying transient failures and stopping on fatal ones.
 * The application id, store and every topic come from the single [FinanceProcessorConfig] (derived from the registered opening), never
 * from separate arguments. The read routes are part of the platform server and only serve what the projection holds.
 */
internal class FiniteFinanceLauncher(
    private val config: FinanceProcessorConfig, private val evidenceProperties: Properties, private val streamsClientProperties: Properties,
    private val stateDir: String, private val projectorFactory: () -> FinanceProjector, private val ownerEpoch: Long,
    private val projectionIntervalMs: Long = 500, private val maxTransientProjectionFailures: Int = 10,
) {
    class Running internal constructor(
        private val streams: KafkaStreams, private val loop: Thread, private val failure: AtomicReference<Throwable?>,
        private val streamsFailed: AtomicBoolean, private val closing: AtomicBoolean,
    ) : AutoCloseable {
        /** The fatal failure that stopped the projection loop, if any (a stale owner epoch, a diverged projection, a broken log, or too many transient errors). */
        fun projectionFailure(): Throwable? = failure.get()
        /** The Streams client state; it can die asynchronously after start (a restore refusal, the history bound, any processing error). */
        fun streamsState(): KafkaStreams.State = streams.state()
        /** True only while the app is alive and the projection loop has not failed. A caller or supervisor must poll this: death is otherwise silent. */
        fun healthy(): Boolean = failure.get() == null && !streamsFailed.get() && streams.state().let { it == KafkaStreams.State.RUNNING || it == KafkaStreams.State.REBALANCING || it == KafkaStreams.State.CREATED }
        /** True once [close] has started: a supervisor must treat an unhealthy state after this as a clean stop, not a failure. */
        fun isClosing(): Boolean = closing.get()
        override fun close() { if (closing.getAndSet(true)) return; loop.interrupt(); loop.join(10_000); FinanceTopology.closeLeavingGroup(streams, java.time.Duration.ofSeconds(30)) }
    }

    fun start(): Running {
        val reader = FinanceEvidenceReader(evidenceProperties, config.applicationId, config.bindingDigest, config.captureTopic, config.controlTopic, config.decisionsTopic, config.changelogTopic)
        val ledger = FinanceLedger(config.finance)
        FinanceRestoreCut.verify(ledger, reader.read())                      // throws FinanceRestoreRefused: nothing below has run

        val properties = FinanceTopology.streamsProperties(config.applicationId, requireNotNull(streamsClientProperties["bootstrap.servers"]) { "bootstrap.servers required" }.toString(), stateDir)
            .apply { streamsClientProperties.forEach { (k, v) -> if (k != "bootstrap.servers") put(k, v) } }
        FinanceTopology.verifyPinned(properties)

        // Everything that can fail before the app exists is done first, so a failure here leaves nothing running.
        val runner = FinanceProjectionRunner(reader, projectorFactory(), config.bindingDigest, ownerEpoch, config.decisionsTopic).also { it.open() }
        val streams = KafkaStreams(FinanceTopology.build(config, persistent = true), properties)
        val streamsFailed = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        streams.setUncaughtExceptionHandler { streamsFailed.set(true); org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
        streams.setStateListener { newState, _ -> if (newState == KafkaStreams.State.ERROR && !closing.get()) streamsFailed.set(true) }
        val failure = AtomicReference<Throwable?>(null)
        try {
            streams.start()
            val loop = Thread {
                var consecutive = 0
                while (!closing.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        runner.runOnce(); consecutive = 0
                        Thread.sleep(projectionIntervalMs)
                    } catch (_: InterruptedException) { break }
                    catch (e: Throwable) {
                        if (closing.get()) break                                   // an interrupt delivered by close() is not a failure
                        if (isFatal(e) || ++consecutive >= maxTransientProjectionFailures) { failure.set(e); break }
                        try { Thread.sleep(minOf(projectionIntervalMs * (1L shl minOf(consecutive, 6)), 10_000)) } catch (_: InterruptedException) { break }
                    }
                }
            }.apply { isDaemon = true; name = "finance-projection"; start() }
            return Running(streams, loop, failure, streamsFailed, closing)
        } catch (e: Throwable) {
            runCatching { FinanceTopology.closeLeavingGroup(streams, java.time.Duration.ofSeconds(10)) }       // no half-started service
            throw e
        }
    }

    companion object {
        /** Fatal: retrying cannot help and continuing would hide a real problem. Everything else (Kafka, JDBC, timeouts) is retried with backoff. */
        internal fun isFatal(e: Throwable): Boolean = e is StaleProjectionEpoch || e is FinanceProjectionDiverged || e is IllegalArgumentException
    }
}
