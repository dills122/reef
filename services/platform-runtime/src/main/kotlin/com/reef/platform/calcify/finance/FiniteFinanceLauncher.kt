package com.reef.platform.calcify.finance

import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.streams.KafkaStreams

/**
 * The finite P3 activation sequence (S3-A2 launcher), in the only order the restore design allows:
 *  1. gather broker evidence with the read-only principal ([FinanceEvidenceReader]);
 *  2. verify the restore cut ([FinanceRestoreCut]); on any refusal NOTHING is started: no Streams app, no projector, no database access;
 *  3. start the finance Streams app with the spec's pinned configuration ([FinanceTopology.verifyPinned]);
 *  4. open the projection (owner-epoch fenced) and run passes until closed.
 * The read routes are part of the platform server and only serve what the projection holds, so they need no ordering here.
 */
internal class FiniteFinanceLauncher(
    private val config: FinanceProcessorConfig, private val applicationId: String, private val clientProperties: Properties,
    private val stateDir: String, private val projectorFactory: () -> FinanceProjector, private val ownerEpoch: Long,
    private val projectionIntervalMs: Long = 500,
) {
    class Running internal constructor(private val streams: KafkaStreams, private val loop: Thread, private val failure: AtomicReference<Throwable?>) : AutoCloseable {
        /** The first failure of the projection loop, if any; the loop stops on it (a stale owner epoch or a broken log must be seen, not retried silently). */
        fun projectionFailure(): Throwable? = failure.get()
        override fun close() { loop.interrupt(); loop.join(10_000); streams.close(java.time.Duration.ofSeconds(30)) }
    }

    fun start(): Running {
        val changelog = "$applicationId-${config.storeName}-changelog"
        val reader = FinanceEvidenceReader(clientProperties, applicationId, config.bindingDigest, config.captureTopic, config.controlTopic, config.decisionsTopic, changelog)
        val ledger = FinanceLedger(config.finance)
        FinanceRestoreCut.verify(ledger, reader.read())                      // throws FinanceRestoreRefused: nothing below has run

        val properties = FinanceTopology.streamsProperties(applicationId, requireNotNull(clientProperties["bootstrap.servers"]) { "bootstrap.servers required" }.toString(), stateDir)
            .apply { clientProperties.forEach { (k, v) -> if (k != "bootstrap.servers") put(k, v) } }
        FinanceTopology.verifyPinned(properties)
        val streams = KafkaStreams(FinanceTopology.build(config, persistent = true), properties)
        streams.setUncaughtExceptionHandler { org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
        streams.start()

        val runner = FinanceProjectionRunner(reader, projectorFactory(), config.bindingDigest, ownerEpoch, config.decisionsTopic).also { it.open() }
        val failure = AtomicReference<Throwable?>(null)
        val loop = Thread {
            try { while (!Thread.currentThread().isInterrupted) { runner.runOnce(); Thread.sleep(projectionIntervalMs) } }
            catch (_: InterruptedException) { /* closing */ }
            catch (e: Throwable) { failure.set(e) }
        }.apply { isDaemon = true; name = "finance-projection"; start() }
        return Running(streams, loop, failure)
    }
}
