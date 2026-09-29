package com.reef.platform.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresSettlementSourceTopicIdentity
import com.reef.platform.infrastructure.persistence.SettlementColdKafkaTopicProbe
import com.reef.platform.infrastructure.persistence.SettlementKafkaColdTopicProbe
import com.reef.platform.infrastructure.persistence.SettlementSourceTopicBinding
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.apache.kafka.clients.admin.AdminClient
import org.postgresql.ds.PGSimpleDataSource

private const val SOURCE_PARTITIONS = 16

internal interface SourceEnrollmentRegistry {
    fun readBinding(eventStream: String, sourceGeneration: String): SettlementSourceTopicBinding?
    fun enrollFresh(eventStream: String, commandTopic: String, venueEventTopic: String)
}

internal data class SourceEnrollmentReceipt(
    val eventStream: String,
    val sourceGeneration: String,
    val commandTopic: String,
    val commandTopicId: String,
    val venueEventTopic: String,
    val venueEventTopicId: String,
    val bindingDigest: String,
    val partitionCount: Int,
    val frontiers: Map<Int, Long>
) {
    fun jsonLine(): String = ObjectMapper().writeValueAsString(mapOf(
        "type" to "settlement_source_enrollment",
        "eventStream" to eventStream,
        "sourceGeneration" to sourceGeneration,
        "commandTopic" to commandTopic,
        "commandTopicId" to commandTopicId,
        "venueEventTopic" to venueEventTopic,
        "venueEventTopicId" to venueEventTopicId,
        "bindingDigest" to bindingDigest,
        "partitionCount" to partitionCount,
        "frontiers" to frontiers.toSortedMap()
    ))
}

/** Run only before source ingress starts. Both Kafka topics and canonical source must remain cold. */
internal class PostMatchCandidateSourceEnrollment(
    private val catalog: PostMatchReadSourceCatalog,
    private val registry: SourceEnrollmentRegistry,
    private val coldProbe: SettlementColdKafkaTopicProbe,
    private val sourceEmpty: () -> Boolean,
    private val topicPartitionsMatch: (SettlementSourceTopicBinding, Int) -> Boolean
) {
    fun enroll(eventStream: String, commandTopic: String,
        venueEventTopic: String): SourceEnrollmentReceipt {
        require(eventStream.isNotBlank() && commandTopic.isNotBlank() &&
            venueEventTopic.isNotBlank() && commandTopic != venueEventTopic)
        val generation = catalog.generation()
        check(sourceEmpty()) { "canonical source is populated; cold enrollment is closed" }
        checkColdFrontiers(eventStream)
        val coldTopics = coldProbe.inspectEmpty(commandTopic, venueEventTopic)
            ?: error("command and venue event topics must be empty and delete-retained")
        val proposedBinding = SettlementSourceTopicBinding(generation, eventStream,
            commandTopic, coldTopics.commandTopicId, venueEventTopic,
            coldTopics.venueEventTopicId)
        check(topicPartitionsMatch(proposedBinding, SOURCE_PARTITIONS)) {
            "both cold Kafka topics must have exactly $SOURCE_PARTITIONS partitions"
        }

        if (registry.readBinding(eventStream, generation) == null) {
            try {
                registry.enrollFresh(eventStream, commandTopic, venueEventTopic)
            } catch (error: SQLException) {
                // A concurrent identical enrollment may win the immutable binding insert.
                if (error.sqlState != "23505") throw error
            }
        }

        val binding = registry.readBinding(eventStream, generation)
            ?: error("cold enrollment did not retain a source topic binding")
        check(binding.sourceGeneration == generation && binding.eventStream == eventStream &&
            binding.commandTopic == commandTopic && binding.venueEventTopic == venueEventTopic &&
            binding.commandTopicId == coldTopics.commandTopicId &&
            binding.venueEventTopicId == coldTopics.venueEventTopicId) {
            "retained source binding differs from live Kafka topic identity"
        }
        check(topicPartitionsMatch(binding, SOURCE_PARTITIONS)) {
            "both bound Kafka topics must have exactly $SOURCE_PARTITIONS partitions"
        }
        check(coldProbe.inspectEmpty(commandTopic, venueEventTopic) == coldTopics &&
            sourceEmpty() && catalog.generation() == generation) {
            "source or Kafka topics changed during cold enrollment"
        }
        val frontiers = checkColdFrontiers(eventStream)
        check(catalog.generation() == generation) { "source generation changed during enrollment" }
        return SourceEnrollmentReceipt(eventStream, generation, commandTopic,
            binding.commandTopicId, venueEventTopic, binding.venueEventTopicId,
            binding.digest(), SOURCE_PARTITIONS, frontiers)
    }

    private fun checkColdFrontiers(eventStream: String): Map<Int, Long> =
        catalog.partitionHeads(eventStream, SOURCE_PARTITIONS).also { heads ->
            check(heads.keys == (0 until SOURCE_PARTITIONS).toSet() &&
                heads.all { (partition, sequence) ->
                    sequence == CanonicalStreamPosition.origin(partition)
                }) { "canonical source frontiers are not cold across all 16 partitions" }
        }
}

/** Explicit one-shot command; caller must keep ingress stopped until its JSON receipt is captured. */
object PostMatchCandidateSourceEnrollmentMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "source enrollment accepts environment configuration only" }
        val jdbcUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "").also {
            require(it.isNotBlank()) { "RUNTIME_POSTGRES_JDBC_URL is required" }
        }
        val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "").also {
            require(it.isNotBlank()) { "POSTMATCH_EVENT_STREAM is required" }
        }
        val commandTopic = RuntimeEnv.string("STREAM_ACK_COMMAND_STREAM", "").also {
            require(it.isNotBlank()) { "STREAM_ACK_COMMAND_STREAM is required" }
        }
        val venueTopic = RuntimeEnv.string("MATCHING_ENGINE_EVENT_STREAM", "").also {
            require(it.isNotBlank()) { "MATCHING_ENGINE_EVENT_STREAM is required" }
        }
        val bootstrap = RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "").also {
            require(it.isNotBlank()) { "STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS is required" }
        }
        val source = PGSimpleDataSource().apply {
            setUrl(jdbcUrl)
            user = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            password = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
        }
        val probe = SettlementKafkaColdTopicProbe(bootstrap)
        val identity = PostgresSettlementSourceTopicIdentity(source, probe)
        val registry = object : SourceEnrollmentRegistry {
            override fun readBinding(eventStream: String, sourceGeneration: String) =
                identity.readBinding(eventStream, sourceGeneration)
            override fun enrollFresh(eventStream: String, commandTopic: String,
                venueEventTopic: String) {
                identity.enrollFresh(eventStream, commandTopic, venueEventTopic)
            }
        }
        val enrollment = PostMatchCandidateSourceEnrollment(PostMatchSourceCatalog(source),
            registry, probe, { canonicalSourceEmpty(source) },
            { binding, count -> kafkaTopicPartitionsMatch(bootstrap, binding, count) })
        println(enrollment.enroll(stream, commandTopic, venueTopic).jsonLine())
    }
}

private fun canonicalSourceEmpty(source: DataSource): Boolean = source.connection.use { connection ->
    connection.prepareStatement(
        """SELECT NOT EXISTS (SELECT 1 FROM runtime.canonical_command_outcomes)
                  AND NOT EXISTS (SELECT 1 FROM runtime.canonical_venue_event_batches)"""
    ).use { statement ->
        statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
    }
}

private fun kafkaTopicPartitionsMatch(bootstrap: String, binding: SettlementSourceTopicBinding,
    count: Int): Boolean = try {
    AdminClient.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
        val descriptions = admin.describeTopics(listOf(binding.commandTopic,
            binding.venueEventTopic)).allTopicNames().get(5, TimeUnit.SECONDS)
        val command = descriptions[binding.commandTopic]
        val venue = descriptions[binding.venueEventTopic]
        command != null && venue != null &&
            command.topicId().toString() == binding.commandTopicId &&
            venue.topicId().toString() == binding.venueEventTopicId &&
            command.partitions().size == count && venue.partitions().size == count
    }
} catch (_: Exception) {
    false
}
