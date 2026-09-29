package com.reef.platform.calcify

import java.sql.Connection

/** PostgreSQL receipt is only an idempotent processing record, never settlement. */
object CalcifyReceiptStore {
    fun record(db: Connection, passed: CommitmentVerificationPassed) {
        db.autoCommit = false
        try {
            val id = passed.commitmentId
            val inserted = db.prepareStatement(
                "INSERT INTO runtime.calcify_commitment_receipts " +
                    "(source_generation, source_partition, source_offset, trade_ordinal, policy_version) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
            ).use {
                it.setInt(1, id.sourceGeneration)
                it.setInt(2, id.sourcePartition)
                it.setLong(3, id.sourceOffset)
                it.setInt(4, id.tradeOrdinal)
                it.setInt(5, passed.policyVersion)
                it.executeUpdate() == 1
            }
            if (!inserted) {
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
                        require(rows.next() && rows.getInt(1) == passed.policyVersion) {
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
