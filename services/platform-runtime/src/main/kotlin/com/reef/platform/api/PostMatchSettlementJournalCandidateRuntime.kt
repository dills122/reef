package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.PostgresSettlementReplaySourceAuthority
import com.reef.platform.infrastructure.persistence.PostgresSettlementSourceBindingDigestReader
import com.reef.platform.infrastructure.persistence.PostgresSettlementSourceTopicIdentity
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementControlLogStore
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityProtocol
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import com.reef.platform.infrastructure.persistence.SettlementJournalSnapshotProof
import com.reef.platform.infrastructure.persistence.SettlementKafkaColdTopicProbe
import com.reef.platform.infrastructure.persistence.SettlementKafkaEmptyRangeAttestor
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** One default-off ordered journal owner. Any ambiguous decision stops its process. */
internal object PostMatchSettlementJournalCandidateRuntime {
    fun start() {
        val worker = fromEnv()
        val pollMs = RuntimeEnv.long("POSTMATCH_LEDGER_CANDIDATE_POLL_MS", 50, min = 1)
        thread(name = "reef-settlement-journal-candidate", isDaemon = true) {
            while (true) {
                try {
                    if (worker.pollOnce() == null) Thread.sleep(pollMs)
                } catch (failure: Throwable) {
                    System.err.println("settlement_journal_candidate_stopped reason=${failure.message ?: failure::class.simpleName}")
                    exitProcess(1)
                }
            }
        }
    }

    private fun fromEnv(): PostMatchSettlementJournalCandidateWorker {
        val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
        val controlUrl = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_JDBC_URL", "")
        val settlementUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
        val finalityUrl = RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_JDBC_URL", "")
        require(listOf(sourceUrl, controlUrl, settlementUrl, finalityUrl).all(String::isNotBlank) &&
            listOf(sourceUrl, controlUrl, settlementUrl, finalityUrl).distinct().size == 4) {
            "candidate journal requires four distinct source, control, settlement, and finality JDBC URLs"
        }
        val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "").also { require(it.isNotBlank()) }
        val commandTopic = RuntimeEnv.string("STREAM_ACK_COMMAND_STREAM", "").also {
            require(it.isNotBlank())
        }
        val venueTopic = RuntimeEnv.string("MATCHING_ENGINE_EVENT_STREAM", "").also {
            require(it.isNotBlank() && it != commandTopic)
        }
        val bootstrap = RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "").also {
            require(it.isNotBlank())
        }
        val partitions = RuntimeEnv.string("POSTMATCH_JOURNAL_PARTITIONS", "")
            .split(',').map { it.trim().toInt() }
        val source = RuntimeDataSources.dataSource(sourceUrl,
            RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef"),
            RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef"), "candidate-journal-source")
        val control = RuntimeDataSources.dataSource(controlUrl,
            RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_USER", "reef"),
            RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_PASSWORD", "reef"), "candidate-journal-control")
        val settlement = RuntimeDataSources.dataSource(settlementUrl,
            RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", "reef"),
            RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", "reef"), "candidate-journal-settlement")
        val finality = RuntimeDataSources.dataSource(finalityUrl,
            RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_USER", "reef"),
            RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_PASSWORD", "reef"), "candidate-journal-finality")
        val catalog = PostMatchSourceCatalog(source)
        val probe = SettlementKafkaColdTopicProbe(bootstrap)
        val identity = PostgresSettlementSourceTopicIdentity(source, probe)
        val generation = catalog.generation()
        val binding = identity.verifiedBinding(stream, generation, probe)
            ?: error("candidate journal requires a verified retained source topic binding")
        check(binding.commandTopic == commandTopic && binding.venueEventTopic == venueTopic) {
            "candidate journal topic configuration differs from source binding"
        }
        val bindingReader = PostgresSettlementSourceBindingDigestReader(catalog, identity, probe)
        val reader = PostgresCanonicalOutcomeSourceReader(source)
        val sourceAuthority = PostgresSettlementReplaySourceAuthority(source, catalog, reader,
            SettlementKafkaEmptyRangeAttestor(stream, commandTopic, bootstrap, identity))
        val journal = SettlementJournalStore(settlement)
        val snapshots = SettlementJournalSnapshotProof(settlement, journal)
        val finalityAuthority = PostgresSettlementJournalFinalityAuthority(finality)
        return PostMatchSettlementJournalCandidateWorker.open(source, catalog, reader,
            sourceAuthority, SettlementControlLogStore(control),
            RuntimeEnv.string("POSTMATCH_LEDGER_CONTROL_INCARNATION", "").also { require(it.isNotBlank()) },
            journal, SettlementJournalFinalityProtocol(journal, finalityAuthority), finalityAuthority,
            bindingReader, stream, partitions,
            RuntimeEnv.string("POSTMATCH_JOURNAL_INCARNATION", "").also { require(it.isNotBlank()) },
            snapshots = snapshots)
    }
}
