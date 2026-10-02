package com.reef.platform.calcify

import kotlin.test.*

class ResolverTopicDurabilityTest {
    @Test fun redpandaQuorumDoesNotRequireKafkaIsrProperty() {
        ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete","write.caching" to "false"),ResolverBrokerKind.REDPANDA,3,false)
        assertFailsWith<IllegalArgumentException> {ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete","write.caching" to "true"),ResolverBrokerKind.REDPANDA,3,false)}
    }
    @Test fun kafkaRequiresConfiguredMinimumIsr() {
        ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete","min.insync.replicas" to "2"),ResolverBrokerKind.KAFKA,3,false)
        assertFailsWith<IllegalArgumentException> {ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete"),ResolverBrokerKind.KAFKA,3,false)}
        assertFailsWith<IllegalArgumentException> {ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete","min.insync.replicas" to "1"),ResolverBrokerKind.KAFKA,3,false)}
    }
    @Test fun exactHistoryCannotCompactAndOutputMustFsyncInLocalDiagnostic() {
        assertFailsWith<IllegalArgumentException> {ResolverTopicDurability.validate(mapOf("cleanup.policy" to "compact","write.caching" to "false"),ResolverBrokerKind.REDPANDA,3,false)}
        ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete","write.caching" to "true"),ResolverBrokerKind.REDPANDA,1,false)
        assertFailsWith<IllegalArgumentException> {ResolverTopicDurability.validate(mapOf("cleanup.policy" to "delete","write.caching" to "true"),ResolverBrokerKind.REDPANDA,1,true)}
    }
    @Test fun compactedChangelogStillRequiresDurableAcknowledgement() {
        ResolverTopicDurability.validateAcknowledgement(mapOf("cleanup.policy" to "compact", "write.caching" to "false"), ResolverBrokerKind.REDPANDA, 3, true)
        assertFailsWith<IllegalArgumentException> {
            ResolverTopicDurability.validateAcknowledgement(mapOf("cleanup.policy" to "compact", "write.caching" to "true"), ResolverBrokerKind.REDPANDA, 3, true)
        }
        assertFailsWith<IllegalArgumentException> {
            ResolverTopicDurability.validateAcknowledgement(mapOf("write.caching" to "true"), ResolverBrokerKind.REDPANDA, 1, true)
        }
        assertFailsWith<IllegalArgumentException> {
            ResolverTopicDurability.validateAcknowledgement(mapOf("min.insync.replicas" to "1"), ResolverBrokerKind.KAFKA, 3, true)
        }
    }

}
