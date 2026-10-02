package com.reef.platform.calcify

internal enum class ResolverBrokerKind { REDPANDA, KAFKA }

/** Backend-specific acknowledgement requirements; Redpanda uses Raft quorum, not Kafka ISR. */
internal object ResolverTopicDurability {
    fun validate(config: Map<String, String>, broker: ResolverBrokerKind, replication: Int, output: Boolean) {
        val cleanup = requireNotNull(config["cleanup.policy"]) { "Calcify topic cleanup policy missing" }
        require("compact" !in cleanup.split(',').map { it.trim() }) { "Calcify source/output history must not compact" }
        validateAcknowledgement(config, broker, replication, output)
    }

    fun validateAcknowledgement(config: Map<String, String>, broker: ResolverBrokerKind, replication: Int, forceDisk: Boolean) {
        when (broker) {
            ResolverBrokerKind.KAFKA -> require((config["min.insync.replicas"]?.toIntOrNull() ?: 0) >= minOf(2, replication)) {
                "Calcify topic min ISR missing or too small"
            }
            ResolverBrokerKind.REDPANDA -> if (replication > 1 || forceDisk) {
                require(config["write.caching"] in setOf("false", "disabled")) { "Calcify durable topic requires write.caching=false" }
            }
        }
    }

    fun topicConfig(broker: ResolverBrokerKind, replication: Int): Map<String, String> =
        when (broker) {
            ResolverBrokerKind.KAFKA -> mapOf("min.insync.replicas" to minOf(2, replication).toString())
            ResolverBrokerKind.REDPANDA -> mapOf("write.caching" to "false")
        }
}
