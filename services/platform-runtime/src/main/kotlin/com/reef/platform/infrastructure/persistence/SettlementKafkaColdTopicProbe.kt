package com.reef.platform.infrastructure.persistence

import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.StringDeserializer

/** Uses read-uncommitted high watermarks so an open or aborted transaction is not mistaken for zero traffic. */
class SettlementKafkaColdTopicProbe(
    bootstrapServers: String,
    private val timeout: Duration = Duration.ofSeconds(5)
) : SettlementColdKafkaTopicProbe, SettlementSourceTopicVerifier {
    init {
        require(bootstrapServers.isNotBlank() && timeout.toMillis() in 1..60_000)
    }

    private val adminProperties = Properties().apply { put("bootstrap.servers", bootstrapServers) }
    private val consumerProperties = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "reef-settlement-source-enrollment")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
        put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted")
        put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout.toMillis().toString())
    }

    override fun inspectEmpty(commandTopic: String, venueEventTopic: String): SettlementColdKafkaTopics? {
        if (commandTopic.isBlank() || venueEventTopic.isBlank() || commandTopic == venueEventTopic) return null
        return try {
            AdminClient.create(adminProperties).use adminUse@ { admin ->
                val descriptions = admin.describeTopics(listOf(commandTopic, venueEventTopic))
                    .allTopicNames().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                val command = descriptions[commandTopic] ?: return@adminUse null
                val events = descriptions[venueEventTopic] ?: return@adminUse null
                if (command.topicId() in Uuid.RESERVED || events.topicId() in Uuid.RESERVED ||
                    command.partitions().isEmpty() || events.partitions().isEmpty()
                ) return@adminUse null
                val resources = listOf(commandTopic, venueEventTopic).map {
                    ConfigResource(ConfigResource.Type.TOPIC, it)
                }
                val configs = admin.describeConfigs(resources).all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                if (resources.any { configs[it]?.get("cleanup.policy")?.value() != "delete" }) {
                    return@adminUse null
                }
                val partitions = listOf(command, events).flatMap { topic ->
                    topic.partitions().map { TopicPartition(topic.name(), it.partition()) }
                }
                KafkaConsumer<String, String>(consumerProperties).use consumerUse@ { consumer ->
                    consumer.assign(partitions)
                    val beginnings = consumer.beginningOffsets(partitions)
                    val ends = consumer.endOffsets(partitions)
                    if (partitions.any { beginnings[it] != 0L || ends[it] != 0L }) return@consumerUse null
                    SettlementColdKafkaTopics(command.topicId().toString(), events.topicId().toString())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Recovery corroborates both retained topic incarnations without requiring empty topics. */
    override fun verify(binding: SettlementSourceTopicBinding): Boolean = try {
        AdminClient.create(adminProperties).use { admin ->
            val names = listOf(binding.commandTopic, binding.venueEventTopic)
            val descriptions = admin.describeTopics(names).allTopicNames()
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            val command = descriptions[binding.commandTopic]
            val events = descriptions[binding.venueEventTopic]
            if (command == null || events == null ||
                command.topicId().toString() != binding.commandTopicId ||
                events.topicId().toString() != binding.venueEventTopicId ||
                command.partitions().isEmpty() || events.partitions().isEmpty()
            ) return@use false
            val resources = names.map { ConfigResource(ConfigResource.Type.TOPIC, it) }
            val configs = admin.describeConfigs(resources).all()
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            resources.all { configs[it]?.get("cleanup.policy")?.value() == "delete" }
        }
    } catch (_: Exception) {
        false
    }
}
