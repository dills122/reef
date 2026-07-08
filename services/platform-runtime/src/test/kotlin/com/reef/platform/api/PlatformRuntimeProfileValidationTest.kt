package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun envLookup(vararg pairs: Pair<String, String>): (String) -> String? {
    val values = pairs.toMap()
    return { key -> values[key] }
}

class PlatformRuntimeProfileValidationTest {

    // --- settlement profile selection applies before stream-ack-specific validation ---

    @Test
    fun localRuntimeDefaultsPostTradeProfile() {
        val config = PlatformRuntimeProfileConfig.fromEnv(envLookup())

        assertEquals("ops-realistic-v1", config.effectivePostTradeProfileId)
        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    @Test
    fun nonLocalRuntimeRequiresExplicitPostTradeProfile() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup("REEF_ENV" to "prod")
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("POST_TRADE_PROFILE"))
    }

    @Test
    fun nonLocalRuntimeAcceptsExplicitPostTradeProfile() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "REEF_ENV" to "prod",
                "POST_TRADE_PROFILE" to "ops-realistic-v1"
            )
        )

        assertEquals("ops-realistic-v1", config.effectivePostTradeProfileId)
        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    // --- non-stream-ack modes skip stream-ack-specific validation ---

    @Test
    fun nonStreamAckModesSkipStreamAckValidation() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "sync-result",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "inmemory",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "0",
                "VENUE_EVENT_MATERIALIZER_ENABLED" to "true"
            )
        )

        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
        PlatformRuntimeProfileValidator.requireValidProfile(config)
    }

    // --- no-DB ceiling profile: bounded in-memory intake retention required ---

    @Test
    fun rejectsUnboundedInMemoryIntakeStore() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_INTAKE_STORE" to "inmemory",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "0"
            )
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES"))

        val ex = assertFailsWith<PlatformRuntimeProfileValidationException> {
            PlatformRuntimeProfileValidator.requireValidProfile(config)
        }
        assertEquals(issues, ex.violations)
        assertTrue(ex.message!!.contains("Unsafe platform runtime profile configuration"))
    }

    @Test
    fun acceptsBoundedInMemoryIntakeStore() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_INTAKE_STORE" to "inmemory",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "50000",
                "STREAM_ACK_PROJECTOR_ENABLED" to "false"
            )
        )

        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
        PlatformRuntimeProfileValidator.requireValidProfile(config)
    }

    @Test
    fun postgresIntakeStoreNeverTriggersTheBoundedRetentionRule() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "0",
                "STREAM_ACK_PROJECTOR_ENABLED" to "false"
            )
        )

        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    // --- no-op publisher profile: incompatible with anything expecting durable output ---

    @Test
    fun rejectsNoopPublisherWithVenueEventMaterializerEnabled() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "STREAM_ACK_PROJECTOR_ENABLED" to "false",
                "VENUE_EVENT_MATERIALIZER_ENABLED" to "true"
            )
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("VENUE_EVENT_MATERIALIZER_ENABLED"))
    }

    @Test
    fun rejectsNoopPublisherWithVenueEventBatchProjectionSource() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "STREAM_ACK_PROJECTOR_ENABLED" to "true",
                "STREAM_ACK_PROJECTION_SOURCE" to "venue-event-batch"
            )
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("STREAM_ACK_PROJECTION_SOURCE=venue-event-batch"))
    }

    @Test
    fun noopPublisherWithCanonicalSubmitProjectionSourceIsAllowed() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "STREAM_ACK_PROJECTOR_ENABLED" to "true",
                "STREAM_ACK_PROJECTION_SOURCE" to "canonical-submit"
            )
        )

        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    @Test
    fun rejectsNoopPublisherWithMarketDataProjectorEnabled() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "STREAM_ACK_PROJECTOR_ENABLED" to "false",
                "MARKET_DATA_PROJECTOR_ENABLED" to "true"
            )
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("MARKET_DATA_PROJECTOR_ENABLED"))
    }

    @Test
    fun rejectsNoopPublisherWithOrderLifecycleProjectorEnabled() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "STREAM_ACK_PROJECTOR_ENABLED" to "false",
                "ORDER_LIFECYCLE_PROJECTOR_ENABLED" to "true"
            )
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("ORDER_LIFECYCLE_PROJECTOR_ENABLED"))
    }

    @Test
    fun rejectsNoopPublisherWithMultipleDownstreamConsumersReportingAllViolations() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "inmemory",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "0",
                "STREAM_ACK_PROJECTOR_ENABLED" to "true",
                "STREAM_ACK_PROJECTION_SOURCE" to "venue-event-batch",
                "VENUE_EVENT_MATERIALIZER_ENABLED" to "true",
                "MARKET_DATA_PROJECTOR_ENABLED" to "true",
                "ORDER_LIFECYCLE_PROJECTOR_ENABLED" to "true"
            )
        )

        val issues = PlatformRuntimeProfileValidator.violations(config)
        // unbounded in-memory retention + materializer + projection-source + market-data + order-lifecycle
        assertEquals(5, issues.size)
    }

    @Test
    fun cleanNoopThroughputCeilingProfilePassesValidation() {
        // The intended "no-op publisher" profile: measure intake ceiling only, everything
        // downstream of durable publish is explicitly turned off.
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "noop",
                "STREAM_ACK_INTAKE_STORE" to "inmemory",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "100000",
                "STREAM_ACK_PROJECTOR_ENABLED" to "false",
                "VENUE_EVENT_MATERIALIZER_ENABLED" to "false",
                "MARKET_DATA_PROJECTOR_ENABLED" to "false",
                "ORDER_LIFECYCLE_PROJECTOR_ENABLED" to "false"
            )
        )

        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
        PlatformRuntimeProfileValidator.requireValidProfile(config)
    }

    // --- durable JetStream / Redpanda stream-ack profiles pass when properly bounded ---

    @Test
    fun jetStreamDurablePublisherProfileWithPostgresStoreIsValid() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "log",
                "STREAM_ACK_LOG_PROVIDER" to "jetstream",
                "STREAM_ACK_INTAKE_STORE" to "postgres",
                "VENUE_EVENT_MATERIALIZER_ENABLED" to "true",
                "STREAM_ACK_PROJECTOR_ENABLED" to "true",
                "STREAM_ACK_PROJECTION_SOURCE" to "venue-event-batch"
            )
        )

        assertEquals(PlatformRuntimeProfilePublisherKind.JetStream, config.resolvedPublisherKind)
        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    @Test
    fun redpandaDurablePublisherProfileWithBoundedInMemoryStoreIsValid() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "stream",
                "STREAM_ACK_LOG_PROVIDER" to "redpanda",
                "STREAM_ACK_INTAKE_STORE" to "inmemory",
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES" to "250000",
                "VENUE_EVENT_MATERIALIZER_ENABLED" to "true"
            )
        )

        assertEquals(PlatformRuntimeProfilePublisherKind.Redpanda, config.resolvedPublisherKind)
        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    @Test
    fun redpandaAliasKafkaResolvesToRedpandaProvider() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "",
                "STREAM_ACK_LOG_PROVIDER" to "kafka"
            )
        )

        assertEquals(PlatformRuntimeProfilePublisherKind.Redpanda, config.resolvedPublisherKind)
    }

    @Test
    fun defaultPublisherAndProviderResolveToJetStreamWhenUnset() {
        val config = PlatformRuntimeProfileConfig.fromEnv(envLookup())

        assertEquals(PlatformRuntimeProfilePublisherKind.JetStream, config.resolvedPublisherKind)
        assertEquals(PlatformRuntimeProfileIntakeStoreKind.Postgres, config.resolvedIntakeStoreKind)
        // stream-ack is not even the default processing mode, so this config is valid regardless.
        assertEquals(emptyList(), PlatformRuntimeProfileValidator.violations(config))
    }

    // --- unsupported raw values fail fast with a clear error, same as existing enum parsing ---

    @Test
    fun rejectsUnsupportedPublisherOverride() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_PUBLISHER" to "bogus"
            )
        )

        val ex = assertFailsWith<IllegalArgumentException> { config.resolvedPublisherKind }
        assertTrue(ex.message!!.contains("STREAM_ACK_PUBLISHER"))
    }

    @Test
    fun rejectsUnsupportedIntakeStoreOverride() {
        val config = PlatformRuntimeProfileConfig.fromEnv(
            envLookup(
                "EXTERNAL_API_COMMAND_PROCESSING_MODE" to "stream-ack",
                "STREAM_ACK_INTAKE_STORE" to "bogus"
            )
        )

        val ex = assertFailsWith<IllegalArgumentException> { config.resolvedIntakeStoreKind }
        assertTrue(ex.message!!.contains("STREAM_ACK_INTAKE_STORE"))
    }
}
