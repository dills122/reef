package com.reef.platform.calcify

import java.sql.Connection
import java.sql.Statement

/** PostgreSQL receipt is only an idempotent processing record, never settlement. */
object CalcifyReceiptStore {
    fun record(db: Connection, passed: CommitmentVerificationPassed) = recordBatch(db, listOf(passed))

    fun recordBatch(db: Connection, passed: List<CommitmentVerificationPassed>) {
        if (passed.isEmpty()) return
        db.autoCommit = false
        try {
            val inserted = db.prepareStatement(
                "INSERT INTO runtime.calcify_commitment_receipts " +
                    "(source_generation, source_partition, source_offset, trade_ordinal, policy_version) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
            ).use {
                for (item in passed) {
                    val id = item.commitmentId
                    it.setInt(1, id.sourceGeneration)
                    it.setInt(2, id.sourcePartition)
                    it.setLong(3, id.sourceOffset)
                    it.setInt(4, id.tradeOrdinal)
                    it.setInt(5, item.policyVersion)
                    it.addBatch()
                }
                it.executeBatch()
            }
            require(inserted.size == passed.size) { "receipt batch result count mismatch" }
            for ((index, item) in passed.withIndex()) {
                require(inserted[index] != Statement.EXECUTE_FAILED) { "receipt batch insert failed" }
                if (inserted[index] == 1) continue
                val id = item.commitmentId
                db.prepareStatement(
                    "SELECT policy_version FROM runtime.calcify_commitment_receipts " +
                        "WHERE source_generation = ? AND source_partition = ? " +
                        "AND source_offset = ? AND trade_ordinal = ?"
                ).use {
                    it.setInt(1, id.sourceGeneration)
                    it.setInt(2, id.sourcePartition)
                    it.setLong(3, id.sourceOffset)
                    it.setInt(4, id.tradeOrdinal)
                    it.executeQuery().use { rows ->
                        require(rows.next() && rows.getInt(1) == item.policyVersion) {
                            "conflicting policy version for commitment receipt"
                        }
                    }
                }
            }
            db.commit()
        } catch (ex: Exception) {
            db.rollback()
            throw ex
        }
    }
}
