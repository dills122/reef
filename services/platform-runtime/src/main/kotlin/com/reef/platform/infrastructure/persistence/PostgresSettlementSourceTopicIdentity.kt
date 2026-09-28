package com.reef.platform.infrastructure.persistence

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource
import org.apache.kafka.common.Uuid

data class SettlementColdKafkaTopics(val commandTopicId: String, val venueEventTopicId: String)

data class SettlementSourceTopicBinding(
    val sourceGeneration: String,
    val eventStream: String,
    val commandTopic: String,
    val commandTopicId: String,
    val venueEventTopic: String,
    val venueEventTopicId: String
) {
    /** Exact six-field source identity pinned by independent finality authority. */
    fun digest(): String {
        val sha = MessageDigest.getInstance("SHA-256")
        listOf("reef.settlement.source-topic-binding.v1", sourceGeneration, eventStream,
            commandTopic, commandTopicId, venueEventTopic, venueEventTopicId).forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            sha.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            sha.update(bytes)
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A cold probe sees both topic UUIDs and zero beginning/high-watermark offsets on all partitions. */
fun interface SettlementColdKafkaTopicProbe {
    fun inspectEmpty(commandTopic: String, venueEventTopic: String): SettlementColdKafkaTopics?
}

fun interface SettlementSourceTopicVerifier {
    fun verify(binding: SettlementSourceTopicBinding): Boolean
}

/**
 * Source-side immutable binding. Enrollment is valid only for a new, empty source generation
 * before ingress/matching starts. Populated generations cannot be retroactively authenticated.
 */
class PostgresSettlementSourceTopicIdentity(
    private val sourceDataSource: DataSource,
    private val coldProbe: SettlementColdKafkaTopicProbe
) : SettlementCommandTopicIdentity {
    override fun topicId(eventStream: String, sourceGeneration: String,
        commandTopic: String): String? = readBinding(eventStream, sourceGeneration)
        ?.takeIf { it.commandTopic == commandTopic }?.commandTopicId

    fun readBinding(eventStream: String, sourceGeneration: String): SettlementSourceTopicBinding? =
        sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT source_generation::text, event_stream, command_topic, command_topic_id,
                          venue_event_topic, venue_event_topic_id
                   FROM runtime.settlement_source_topic_identity
                   WHERE source_generation = ?::uuid AND event_stream = ?"""
            ).use { statement ->
                statement.setString(1, sourceGeneration)
                statement.setString(2, eventStream)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else SettlementSourceTopicBinding(
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getString(6))
                }
            }
        }

    fun bindingDigest(eventStream: String, sourceGeneration: String,
        commandTopic: String, venueEventTopic: String): String? =
        readBinding(eventStream, sourceGeneration)
            ?.takeIf { it.commandTopic == commandTopic && it.venueEventTopic == venueEventTopic }
            ?.digest()

    fun verifiedBinding(eventStream: String, sourceGeneration: String,
        verifier: SettlementSourceTopicVerifier): SettlementSourceTopicBinding? =
        readBinding(eventStream, sourceGeneration)?.takeIf(verifier::verify)

    fun enrollFresh(eventStream: String, commandTopic: String,
        venueEventTopic: String): SettlementColdKafkaTopics {
        require(eventStream.isNotBlank() && commandTopic.isNotBlank() &&
            venueEventTopic.isNotBlank() && commandTopic != venueEventTopic)
        val first = coldProbe.inspectEmpty(commandTopic, venueEventTopic)
            ?: error("settlement source topic enrollment requires two empty retained Kafka topics")
        check(Uuid.fromString(first.commandTopicId) !in Uuid.RESERVED &&
            Uuid.fromString(first.venueEventTopicId) !in Uuid.RESERVED) {
            "settlement source topic enrollment requires non-reserved Kafka topic IDs"
        }
        return sourceDataSource.connection.use { connection ->
            check(connection.autoCommit) { "source topic enrollment requires an idle connection" }
            connection.autoCommit = false
            try {
                // Cold-start lock prevents source materialization between empty checks and binding.
                connection.createStatement().use { statement ->
                    statement.execute("LOCK TABLE runtime.canonical_command_outcomes, " +
                        "runtime.canonical_venue_event_batches IN SHARE MODE")
                }
                val generation = currentGeneration(connection)
                check(sourceTablesEmpty(connection)) {
                    "populated canonical source cannot receive retrospective Kafka topic identity"
                }
                val second = coldProbe.inspectEmpty(commandTopic, venueEventTopic)
                check(second == first) { "Kafka source topics changed during enrollment" }
                connection.prepareStatement(
                    """INSERT INTO runtime.settlement_source_topic_identity
                       (source_generation,event_stream,command_topic,command_topic_id,
                        venue_event_topic,venue_event_topic_id)
                       VALUES (?::uuid,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setString(1, generation)
                    statement.setString(2, eventStream)
                    statement.setString(3, commandTopic)
                    statement.setString(4, first.commandTopicId)
                    statement.setString(5, venueEventTopic)
                    statement.setString(6, first.venueEventTopicId)
                    check(statement.executeUpdate() == 1)
                }
                connection.commit()
                first
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun currentGeneration(connection: Connection): String =
        connection.prepareStatement(
            "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE FOR UPDATE"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "canonical source generation missing" }
                rows.getString(1)
            }
        }

    private fun sourceTablesEmpty(connection: Connection): Boolean =
        connection.prepareStatement(
            """SELECT NOT EXISTS (SELECT 1 FROM runtime.canonical_command_outcomes)
                  AND NOT EXISTS (SELECT 1 FROM runtime.canonical_venue_event_batches)"""
        ).use { statement ->
            statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
}
