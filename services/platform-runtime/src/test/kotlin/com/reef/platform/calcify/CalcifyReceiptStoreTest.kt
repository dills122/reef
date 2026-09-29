package com.reef.platform.calcify

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalcifyReceiptStoreTest {
    @Test
    fun replayIsIdempotentAndConflictingPolicyFails() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val id = CommitmentId(1, 2, System.currentTimeMillis(), 0)
        val passed = CommitmentVerificationPassed(id, 1)
        DriverManager.getConnection(url, user, password).use { db ->
            try {
                CalcifyReceiptStore.record(db, passed)
                CalcifyReceiptStore.record(db, passed)
                db.prepareStatement(
                    "SELECT count(*), min(policy_version) FROM runtime.calcify_commitment_receipts " +
                        "WHERE source_generation = ? AND source_partition = ? AND source_offset = ? AND trade_ordinal = ?"
                ).use {
                    it.setInt(1, id.sourceGeneration)
                    it.setInt(2, id.sourcePartition)
                    it.setLong(3, id.sourceOffset)
                    it.setInt(4, id.tradeOrdinal)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1L, rows.getLong(1))
                        assertEquals(1, rows.getInt(2))
                    }
                }
                assertFailsWith<IllegalArgumentException> {
                    CalcifyReceiptStore.record(db, CommitmentVerificationPassed(id, 2))
                }
            } finally {
                db.autoCommit = true
                db.prepareStatement(
                    "DELETE FROM runtime.calcify_commitment_receipts WHERE " +
                        "source_generation = ? AND source_partition = ? AND source_offset = ? AND trade_ordinal = ?"
                ).use {
                    it.setInt(1, id.sourceGeneration)
                    it.setInt(2, id.sourcePartition)
                    it.setLong(3, id.sourceOffset)
                    it.setInt(4, id.tradeOrdinal)
                    it.executeUpdate()
                }
            }
        }
    }
}
