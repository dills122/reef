package com.reef.platform.application.postmatch

data class PlannedLiveEffects(
    val finalOrderStates: List<CanonicalEffectEnvelope>,
    val executions: List<CanonicalEffectEnvelope>,
    val trades: List<CanonicalEffectEnvelope>
)

/** Preserve every immutable fill fact and coalesce order state to its final batch value. */
class LiveEffectBatchPlanner {
    fun plan(effects: List<CanonicalEffectEnvelope>): PlannedLiveEffects {
        if (effects.isEmpty()) return PlannedLiveEffects(emptyList(), emptyList(), emptyList())
        val stream = effects.first().position.eventStream
        val partition = effects.first().position.partitionId
        require(stream.isNotBlank() && partition >= 0) { "live effects lack source identity" }
        val latestStates = linkedMapOf<String, CanonicalEffectEnvelope>()
        val executions = mutableListOf<CanonicalEffectEnvelope>()
        val trades = mutableListOf<CanonicalEffectEnvelope>()
        var previous: CanonicalEffectPosition? = null
        effects.forEach { envelope ->
            val position = envelope.position
            require(position.eventStream == stream && position.partitionId == partition &&
                position.streamSequence > 0 && position.effectOrdinal >= 0) { "live effects mix source partitions" }
            val prior = previous
            require(prior == null || position.streamSequence > prior.streamSequence ||
                (position.streamSequence == prior.streamSequence && position.effectOrdinal > prior.effectOrdinal)) {
                "live effect positions are duplicate or out of order"
            }
            previous = position
            when (val effect = envelope.effect) {
                is CanonicalEffect.OrderStateChanged -> latestStates[effect.orderId] = envelope
                is CanonicalEffect.Execution -> executions += envelope
                is CanonicalEffect.Trade -> trades += envelope
                else -> Unit
            }
        }
        val finalStates = latestStates.values.sortedWith(compareBy(
            { it.position.streamSequence }, { it.position.effectOrdinal },
            { (it.effect as CanonicalEffect.OrderStateChanged).orderId }
        ))
        return PlannedLiveEffects(finalStates, executions, trades)
    }
}
