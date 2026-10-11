package com.reef.platform.calcify

import java.util.Properties
import org.apache.kafka.streams.StreamsConfig
import reef.contracts.calcify.v1.FiniteLifecycleStateV1

/** Explicit model inputs, never durable activation attestations. */
internal sealed interface FiniteLifecycleStartCut {
    data class Genesis(val registeredOffset: Long, val availableBeginning: Long, val availableEnd: Long): FiniteLifecycleStartCut
    data class Restore(val checkpoint: FiniteLifecycleStateV1, val availableBeginning: Long, val availableEnd: Long): FiniteLifecycleStartCut
}

internal object FiniteLifecycleCaptureRuntime {
    fun initializeModel(binding: FiniteLifecycleBinding, cut: FiniteLifecycleStartCut,
                        managed: FiniteLifecycleStateV1? = null): FiniteLifecycleStateV1 {
        val state = when(cut) {
            is FiniteLifecycleStartCut.Genesis -> {
                require(managed == null) { "genesis cannot reset existing managed history" }
                require(cut.registeredOffset == binding.genesisOffset && cut.availableBeginning <= binding.genesisOffset &&
                    cut.availableEnd >= binding.genesisOffset) { "registered genesis/history unavailable" }
                FiniteLifecycleContract.genesis(binding)
            }
            is FiniteLifecycleStartCut.Restore -> {
                require(managed == null || managed == cut.checkpoint) { "managed/certified checkpoint mismatch" }
                val requiredEnd = maxOf(cut.checkpoint.resumeOffset,cut.checkpoint.retainedSuffixList.maxOfOrNull { it.resumeOffset } ?: binding.genesisOffset)
                require(cut.availableBeginning <= binding.genesisOffset && cut.availableEnd >= requiredEnd) { "required source history unavailable" }
                cut.checkpoint
            }
        }
        FiniteLifecycleReducer.validateState(state,binding)
        return state
    }

    fun modelProperties(binding: FiniteLifecycleBinding, bootstrap: String): Properties = Properties().apply {
        put(StreamsConfig.APPLICATION_ID_CONFIG,binding.applicationId)
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap)
        put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,StreamsConfig.EXACTLY_ONCE_V2)
        put(StreamsConfig.NUM_STREAM_THREADS_CONFIG,1)
        put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG,0L)
        put(StreamsConfig.consumerPrefix("isolation.level"),"read_committed")
        put(StreamsConfig.consumerPrefix("auto.offset.reset"),"none")
        put(StreamsConfig.consumerPrefix("allow.auto.create.topics"),false)
        put(StreamsConfig.consumerPrefix("max.poll.records"),binding.budget.publications)
        put(StreamsConfig.consumerPrefix("max.partition.fetch.bytes"),binding.budget.sourceTotalBytes.toInt())
        put(StreamsConfig.consumerPrefix("fetch.max.bytes"),binding.budget.sourceTotalBytes.toInt())
        put(StreamsConfig.producerPrefix("compression.type"),"none")
        put(StreamsConfig.producerPrefix("max.request.size"),binding.budget.captureBytes+1024)
        put(StreamsConfig.topicPrefix("max.message.bytes"),binding.budget.stateBytes+1024)
    }

    fun run(): Nothing = throw IllegalStateException(
        "finite lifecycle live activation unavailable: O2 durable binding, canonical ingress, writer isolation and history/restore verification required"
    )
}
