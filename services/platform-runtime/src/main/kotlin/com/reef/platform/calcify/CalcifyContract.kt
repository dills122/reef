package com.reef.platform.calcify

import com.reef.platform.api.JsonCodec
import com.reef.platform.api.JsonDocument
import java.nio.ByteBuffer

/** Wire v1: version:u8, sourceGeneration:i32, sourcePartition:i32, sourceOffset:i64, tradeOrdinal:i32. */
data class CommitmentId(
    val sourceGeneration: Int,
    val sourcePartition: Int,
    val sourceOffset: Long,
    val tradeOrdinal: Int
) {
    init {
        require(sourceGeneration > 0)
        require(sourcePartition >= 0)
        require(sourceOffset >= 0)
        require(tradeOrdinal >= 0)
    }
}

data class CommitmentVerificationPassed(val commitmentId: CommitmentId, val policyVersion: Int) {
    init { require(policyVersion in 1..65535) }
}

object CalcifyWire {
    const val VERSION = 1
    const val COMMITMENT_BYTES = 21
    const val PASSED_BYTES = 23
    const val STUB_POLICY_VERSION = 1

    fun commitment(id: CommitmentId): ByteArray = ByteBuffer.allocate(COMMITMENT_BYTES)
        .put(VERSION.toByte())
        .putInt(id.sourceGeneration)
        .putInt(id.sourcePartition)
        .putLong(id.sourceOffset)
        .putInt(id.tradeOrdinal)
        .array()

    fun readCommitment(bytes: ByteArray): CommitmentId {
        require(bytes.size == COMMITMENT_BYTES) { "invalid commitment length" }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get().toInt() == VERSION) { "unsupported commitment version" }
        return CommitmentId(buffer.int, buffer.int, buffer.long, buffer.int)
    }

    fun passed(record: CommitmentVerificationPassed): ByteArray =
        ByteBuffer.allocate(PASSED_BYTES)
            .put(commitment(record.commitmentId))
            .putShort(record.policyVersion.toShort())
            .array()

    fun readPassed(bytes: ByteArray): CommitmentVerificationPassed {
        require(bytes.size == PASSED_BYTES) { "invalid verification length" }
        return CommitmentVerificationPassed(
            readCommitment(bytes.copyOfRange(0, COMMITMENT_BYTES)),
            ByteBuffer.wrap(bytes, COMMITMENT_BYTES, 2).short.toInt() and 0xffff
        )
    }

    /** Phase 1 policy asserts only a structurally valid link. It does not assert trade eligibility or settlement. */
    fun stubVerify(bytes: ByteArray): CommitmentVerificationPassed =
        CommitmentVerificationPassed(readCommitment(bytes), STUB_POLICY_VERSION)
}

object CalcifySourceBatch {
    private const val CHECKSUM_ALGORITHM = "sha256-reef-canonical-v1"
    private val CHECKSUM_EXCLUDED = setOf(
        "createdAt", "workFinishedAt", "timingChecksum", "payloadChecksum", "payloadChecksumAlgorithm"
    )

    /** Parse and check one committed batch, then walk nested trades in memory. */
    fun extract(
        payloadJson: String,
        sourceTopic: String,
        sourceGeneration: Int,
        sourcePartition: Int,
        sourceOffset: Long
    ): List<CommitmentId> {
        val root = checked(payloadJson, sourceTopic, sourcePartition)
        val outcomes = root.strictObjectDocuments("outcomes")
        val ids = ArrayList<CommitmentId>()
        for (outcome in outcomes) {
            val result = outcome.strictObject("result")
            for (trade in result.strictObjectDocuments("trades", required = false)) {
                trade.strictTextField("tradeId")
                trade.strictTextField("executionId")
                ids.add(CommitmentId(sourceGeneration, sourcePartition, sourceOffset, ids.size))
            }
        }
        return ids
    }

    internal fun checked(payloadJson: String, sourceTopic: String, sourcePartition: Int): JsonDocument {
        return checked(JsonCodec.parseObject(payloadJson), sourceTopic, sourcePartition)
    }

    internal fun checked(payload: ByteArray, sourceTopic: String, sourcePartition: Int): JsonDocument =
        checked(JsonCodec.parseObject(payload), sourceTopic, sourcePartition)

    private fun checked(root: JsonDocument, sourceTopic: String, sourcePartition: Int): JsonDocument {
        require(root.strictTextField("payloadChecksumAlgorithm") == CHECKSUM_ALGORITHM) {
            "unsupported or absent venue event batch checksum"
        }
        require(root.strictTextField("payloadChecksum") == root.semanticSha256(CHECKSUM_EXCLUDED)) {
            "venue event batch checksum mismatch"
        }
        root.strictTextField("batchId")
        require(root.strictIntField("partition") == sourcePartition) {
            "venue event batch source partition mismatch"
        }
        require(root.strictTextField("eventStream") == sourceTopic) { "venue event batch source topic mismatch" }
        val outcomes = root.strictObjectDocuments("outcomes", required = true)
        require(root.strictIntField("commandCount") == outcomes.size) {
            "venue event batch command count mismatch"
        }
        val first = root.strictLongField("firstSequence")
        val last = root.strictLongField("lastSequence")
        if (outcomes.isEmpty()) {
            require(first == 0L && last == 0L) { "empty venue event batch has nonzero sequence bounds" }
        } else {
            val sequences = outcomes.map {
                it.strictLongField("streamSequence")
            }
            require(first > 0 && last >= first)
            require(sequences.first() == first && sequences.last() == last)
            require(sequences.zipWithNext().all { (a, b) -> a < b }) {
                "venue event batch outcomes not strictly ordered"
            }
        }
        return root
    }

}
