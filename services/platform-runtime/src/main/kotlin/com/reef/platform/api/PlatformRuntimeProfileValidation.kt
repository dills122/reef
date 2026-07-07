package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv

/**
 * Explicit, fail-fast validation of the runtime "profile" implied by the current environment
 * configuration: the stream-ack command processing mode, the resolved stream-ack publisher
 * (no-op / JetStream / Redpanda-Kafka), the stream-ack intake store (in-memory no-DB / Postgres),
 * and the venue-event-batch materializer / market-data / order-lifecycle projector chain built on
 * top of it.
 *
 * See docs/WORK_PLAN.md "Active Now" item 1: "no-op publisher, stream-direct no-DB, JetStream
 * stream-ack, Redpanda/Kafka-compatible stream-ack, and materializer profiles must reject unsafe
 * settings before runs. Bounded in-memory intake retention is required for no-DB ceiling
 * profiles."
 *
 * This intentionally derives the "profile" from the existing, already-scattered env vars rather
 * than introducing a new profile-selector env var: [PlatformRuntimeProfileConfig.fromEnv] mirrors
 * the exact resolution logic used by [StreamCommandIntakeFactory] and [PlatformHttpServer], so the
 * validated config is guaranteed to match what actually gets constructed at startup.
 */
data class PlatformRuntimeProfileConfig(
    val commandProcessingMode: CommandProcessingMode,
    val streamAckPublisherRaw: String,
    val streamAckLogProvider: StreamCommandLogProvider,
    val streamAckIntakeStoreRaw: String,
    val inMemoryIntakeMaxEntries: Int,
    val venueEventMaterializerEnabled: Boolean,
    val streamAckProjectorEnabled: Boolean,
    val streamAckProjectionSource: CanonicalProjectionSource,
    val marketDataProjectorEnabled: Boolean,
    val orderLifecycleProjectorEnabled: Boolean
) {
    /** Resolved publisher kind, mirroring [StreamCommandIntakeFactory.defaultPublisher] selection. */
    val resolvedPublisherKind: PlatformRuntimeProfilePublisherKind
        get() = when (streamAckPublisherRaw) {
            "", "log", "stream" -> when (streamAckLogProvider) {
                StreamCommandLogProvider.JetStream -> PlatformRuntimeProfilePublisherKind.JetStream
                StreamCommandLogProvider.Redpanda -> PlatformRuntimeProfilePublisherKind.Redpanda
            }
            "noop" -> PlatformRuntimeProfilePublisherKind.Noop
            else -> throw IllegalArgumentException(
                "Unsupported STREAM_ACK_PUBLISHER '$streamAckPublisherRaw'; expected empty/log/stream or noop"
            )
        }

    /** Resolved intake store kind, mirroring [StreamCommandIntakeFactory.defaultStore] selection. */
    val resolvedIntakeStoreKind: PlatformRuntimeProfileIntakeStoreKind
        get() = when (streamAckIntakeStoreRaw) {
            "inmemory" -> PlatformRuntimeProfileIntakeStoreKind.InMemory
            "postgres" -> PlatformRuntimeProfileIntakeStoreKind.Postgres
            else -> throw IllegalArgumentException("Unsupported STREAM_ACK_INTAKE_STORE '$streamAckIntakeStoreRaw'")
        }

    companion object {
        fun fromEnv(lookup: (String) -> String? = { key -> System.getenv(key) }): PlatformRuntimeProfileConfig {
            return PlatformRuntimeProfileConfig(
                commandProcessingMode = CommandProcessingMode.fromEnv(lookup),
                streamAckPublisherRaw = RuntimeEnv.string("STREAM_ACK_PUBLISHER", "", lookup).trim().lowercase(),
                streamAckLogProvider = StreamCommandLogProvider.fromEnv(lookup),
                streamAckIntakeStoreRaw = RuntimeEnv.string("STREAM_ACK_INTAKE_STORE", "postgres", lookup).trim().lowercase(),
                inMemoryIntakeMaxEntries = RuntimeEnv.int("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES", 0, min = 0, lookup = lookup),
                venueEventMaterializerEnabled = RuntimeEnv.bool("VENUE_EVENT_MATERIALIZER_ENABLED", false, lookup),
                streamAckProjectorEnabled = RuntimeEnv.bool("STREAM_ACK_PROJECTOR_ENABLED", true, lookup),
                streamAckProjectionSource = CanonicalProjectionSource.fromConfig(
                    RuntimeEnv.string(
                        "STREAM_ACK_PROJECTION_SOURCE",
                        CanonicalProjectionSource.CanonicalSubmit.configValue,
                        lookup
                    )
                ),
                marketDataProjectorEnabled = RuntimeEnv.bool("MARKET_DATA_PROJECTOR_ENABLED", false, lookup),
                orderLifecycleProjectorEnabled = RuntimeEnv.bool("ORDER_LIFECYCLE_PROJECTOR_ENABLED", false, lookup)
            )
        }
    }
}

enum class PlatformRuntimeProfilePublisherKind { Noop, JetStream, Redpanda }

enum class PlatformRuntimeProfileIntakeStoreKind { InMemory, Postgres }

class PlatformRuntimeProfileValidationException(val violations: List<String>) : IllegalStateException(
    "Unsafe platform runtime profile configuration:\n" + violations.joinToString("\n") { "  - $it" }
)

object PlatformRuntimeProfileValidator {
    /**
     * Returns every validation violation for the given config; an empty list means the profile is
     * safe to run. Only meaningful when [CommandProcessingMode.StreamAck] is selected: other
     * processing modes never build a stream-ack publisher/intake store or materializer/projector
     * chain on top of one, so there is nothing for this validator to reject.
     */
    fun violations(config: PlatformRuntimeProfileConfig): List<String> {
        if (config.commandProcessingMode != CommandProcessingMode.StreamAck) {
            return emptyList()
        }

        val issues = mutableListOf<String>()
        val publisherKind = config.resolvedPublisherKind
        val intakeStoreKind = config.resolvedIntakeStoreKind

        // Bounded in-memory intake retention is required for no-DB ceiling profiles: an unbounded
        // in-memory dedupe/reservation store grows without limit for the lifetime of the process,
        // which is exactly the "silent misconfiguration" this checkpoint exists to catch.
        if (intakeStoreKind == PlatformRuntimeProfileIntakeStoreKind.InMemory && config.inMemoryIntakeMaxEntries <= 0) {
            issues += "STREAM_ACK_INTAKE_STORE=inmemory (no-DB profile) requires a bounded " +
                "STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES > 0; got ${config.inMemoryIntakeMaxEntries} (unbounded)"
        }

        // The no-op publisher profile exists to measure the intake/ack throughput ceiling in
        // isolation: it never hands commands to the matching engine durably, so nothing
        // downstream of it (materializer, venue-event-batch projection, market-data projection,
        // order-lifecycle projection) can ever receive real data. Leaving those enabled alongside
        // a no-op publisher would look configured/healthy while silently doing nothing.
        if (publisherKind == PlatformRuntimeProfilePublisherKind.Noop) {
            if (config.venueEventMaterializerEnabled) {
                issues += "STREAM_ACK_PUBLISHER=noop is incompatible with VENUE_EVENT_MATERIALIZER_ENABLED=true: " +
                    "the matching engine never receives durably-published commands to turn into venue event batches"
            }
            if (config.streamAckProjectorEnabled && config.streamAckProjectionSource == CanonicalProjectionSource.VenueEventBatch) {
                issues += "STREAM_ACK_PUBLISHER=noop is incompatible with " +
                    "STREAM_ACK_PROJECTION_SOURCE=venue-event-batch: no durable venue event batches will ever exist to project"
            }
            if (config.marketDataProjectorEnabled) {
                issues += "STREAM_ACK_PUBLISHER=noop is incompatible with MARKET_DATA_PROJECTOR_ENABLED=true: " +
                    "market-data projection reads durable venue outcomes that a noop publisher never produces"
            }
            if (config.orderLifecycleProjectorEnabled) {
                issues += "STREAM_ACK_PUBLISHER=noop is incompatible with ORDER_LIFECYCLE_PROJECTOR_ENABLED=true: " +
                    "order-lifecycle projection reads durable outcomes that a noop publisher never produces"
            }
        }

        return issues
    }

    /** Throws [PlatformRuntimeProfileValidationException] listing every violation, or returns silently. */
    fun requireValidProfile(config: PlatformRuntimeProfileConfig) {
        val issues = violations(config)
        if (issues.isNotEmpty()) {
            throw PlatformRuntimeProfileValidationException(issues)
        }
    }
}
