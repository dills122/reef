package com.reef.platform.application.postmatch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class VerifiedCanonicalOutcome(
    val source: CanonicalOutcomeSource,
    val effects: List<CanonicalEffectEnvelope>,
    val resultDigest: String
)

/** Only the verifier can construct a window eligible for target-store progress. */
class VerifiedCanonicalSourceWindow internal constructor(
    val consumerName: String,
    val eventStream: String,
    val partitionId: Int,
    val sourceGeneration: String,
    val fromExclusiveSequence: Long,
    val throughInclusiveSequence: Long,
    val outcomes: List<VerifiedCanonicalOutcome>,
    val sourceDigest: String
)

/**
 * Covers every source position with an exact v1 outcome. Kafka offset holes
 * require a separate broker-backed absence proof; until then they fail closed.
 */
class CanonicalSourceCoverageVerifier(
    private val decoder: CanonicalEffectDecoder = CanonicalEffectDecoder()
) {
    private val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    fun verify(
        consumerName: String,
        eventStream: String,
        partitionId: Int,
        sourceGeneration: String,
        fromExclusiveSequence: Long,
        throughInclusiveSequence: Long,
        sources: List<CanonicalOutcomeSource>
    ): VerifiedCanonicalSourceWindow {
        require(consumerName.isNotBlank() && eventStream.isNotBlank() && partitionId >= 0 && sourceGeneration.isNotBlank()) {
            "consumer and source identity are required"
        }
        require(fromExclusiveSequence >= 0 && throughInclusiveSequence > fromExclusiveSequence) {
            "invalid source coverage range"
        }
        require(sources.isNotEmpty() && sources.size <= 5000) { "source coverage must be bounded and nonempty" }
        require(throughInclusiveSequence - fromExclusiveSequence == sources.size.toLong()) {
            "source coverage has a missing position"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.field("reef.postmatch.source-coverage.v1")
        digest.field(eventStream)
        digest.field(partitionId.toString())
        digest.field(sourceGeneration)
        digest.field(fromExclusiveSequence.toString())
        digest.field(throughInclusiveSequence.toString())

        val outcomes = sources.mapIndexed { index, source ->
            val expectedSequence = fromExclusiveSequence + index + 1L
            require(source.eventStream == eventStream && source.partitionId == partitionId) {
                "source coverage mixes streams or partitions"
            }
            require(source.streamSequence == expectedSequence) { "source coverage has a gap or duplicate" }
            val effects = decoder.decode(source)
            val canonicalResult = try {
                canonicalJson(mapper.readTree(source.resultPayloadJson))
            } catch (error: Exception) {
                throw IllegalArgumentException("invalid canonical result JSON", error)
            }
            val resultDigest = hex(MessageDigest.getInstance("SHA-256").digest(canonicalResult.toByteArray(StandardCharsets.UTF_8)))
            listOf(
                source.streamSequence.toString(), source.batchId, source.commandId,
                source.commandType, source.payloadHash, source.instrumentId,
                source.orderId, source.resultStatus, resultDigest, effects.size.toString()
            ).forEach { digest.field(it) }
            VerifiedCanonicalOutcome(source, effects.toList(), resultDigest)
        }
        return VerifiedCanonicalSourceWindow(
            consumerName, eventStream, partitionId, sourceGeneration,
            fromExclusiveSequence, throughInclusiveSequence, outcomes, hex(digest.digest())
        )
    }

    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> node.properties().sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${mapper.writeValueAsString(key)}:${canonicalJson(value)}"
        }
        node.isArray -> node.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        node.isNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
        else -> mapper.writeValueAsString(node)
    }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
