package com.reef.platform.infrastructure.persistence

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ProjectionBatchIdentityCandidate(
    val partitionId: Int,
    val streamSequence: Long,
    val commandId: String,
    val canonicalBatchId: String,
    val commandType: String,
    val payloadHash: String
)

/**
 * Stable identity for one ordered canonical projection batch.
 *
 * The format is deliberately independent of JSON and platform-default encodings:
 * a big-endian version integer, length-prefixed UTF-8 strings, a one-byte boolean,
 * a candidate count, and then candidates in their actual processing order.
 */
object ProjectionBatchIdentityV1 {
    private const val EncodingVersion = 1

    fun digest(
        projectionName: String,
        eventStream: String,
        projectionStage: ProjectionStage,
        includeFills: Boolean,
        candidates: List<ProjectionBatchIdentityCandidate>
    ): String {
        require(candidates.isNotEmpty()) { "Projection batch identity requires at least one candidate" }

        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(EncodingVersion)
                output.writeLengthPrefixedUtf8(projectionName)
                output.writeLengthPrefixedUtf8(eventStream)
                output.writeLengthPrefixedUtf8(projectionStage.configValue)
                output.writeBoolean(includeFills)
                output.writeInt(candidates.size)
                candidates.forEach { candidate ->
                    output.writeInt(candidate.partitionId)
                    output.writeLong(candidate.streamSequence)
                    output.writeLengthPrefixedUtf8(candidate.commandId)
                    output.writeLengthPrefixedUtf8(candidate.canonicalBatchId)
                    output.writeLengthPrefixedUtf8(candidate.commandType)
                    output.writeLengthPrefixedUtf8(candidate.payloadHash)
                }
            }
            bytes.toByteArray()
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun DataOutputStream.writeLengthPrefixedUtf8(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }
}
