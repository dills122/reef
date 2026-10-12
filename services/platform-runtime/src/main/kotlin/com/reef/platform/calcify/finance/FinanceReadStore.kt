package com.reef.platform.calcify.finance

import java.sql.Connection
import javax.sql.DataSource

data class FinanceCheckpointRow(val bindingDigest: String, val ownerEpoch: Long, val lastSequence: Long, val lastChain: String)
data class FinanceDecisionRow(val sequence: Long, val actionNamespace: String, val actionDomain: String, val actionId: String,
                              val disposition: String, val inputDigest: String, val priorChain: String, val checksum: String)
data class FinancePositionRow(val accountId: String, val assetId: String, val amount: Long)
data class FinanceObligationRow(val runId: String, val venueSessionId: String, val instrumentId: String, val executionId: String,
                                val buyerAccountId: String, val sellerAccountId: String, val quantity: Long, val priceNanos: Long,
                                val status: String, val cashResidual: Long, val shareResidual: Long)
data class FinanceAttemptRow(val runId: String, val venueSessionId: String, val instrumentId: String, val executionId: String,
                             val attempt: Int, val disposition: String)

/**
 * Rows read together with the projection progress they reflect, from ONE consistent snapshot (a single repeatable-read, read-only
 * transaction), so a client can tell which decision sequence a response corresponds to and reconcile the resources with each other.
 * [progress] is null when the binding has no projection at all.
 */
data class FinanceSnapshot<T>(val progress: FinanceCheckpointRow?, val rows: List<T>)

/**
 * Read seam over the O4 projection tables (boundary.calcify_finance_*). [accounts] == null reads everything for the binding (operator
 * routes); a non-null set restricts the rows to those accounts (participant routes) and an empty set reads nothing.
 */
interface FinanceReadStore {
    fun checkpoint(bindingDigest: String): FinanceCheckpointRow?
    fun decisions(bindingDigest: String, afterSequence: Long, limit: Int): FinanceSnapshot<FinanceDecisionRow>
    fun positions(bindingDigest: String, accounts: Set<String>?): FinanceSnapshot<FinancePositionRow>
    fun obligations(bindingDigest: String, accounts: Set<String>?): FinanceSnapshot<FinanceObligationRow>
    fun attempts(bindingDigest: String, accounts: Set<String>?): FinanceSnapshot<FinanceAttemptRow>
}

/** Postgres implementation. Intended to run as a separate read-only role with SELECT on the five projection tables only. */
class JdbcFinanceReadStore(private val dataSource: DataSource) : FinanceReadStore {
    private fun <T> snapshot(bindingDigest: String, rows: (Connection) -> List<T>): FinanceSnapshot<T> = dataSource.connection.use { c ->
        c.autoCommit = false
        c.isReadOnly = true
        c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        try {
            val progress = checkpointOn(c, bindingDigest)
            val result = if (progress == null) emptyList() else rows(c)
            c.commit()
            FinanceSnapshot(progress, result)
        } catch (e: Throwable) { runCatching { c.rollback() }; throw e }
    }

    private fun checkpointOn(c: Connection, bindingDigest: String): FinanceCheckpointRow? =
        c.prepareStatement("SELECT binding_digest, owner_epoch, last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps ->
            ps.setString(1, bindingDigest)
            ps.executeQuery().use { rs -> if (rs.next()) FinanceCheckpointRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) else null }
        }

    private inline fun <T> query(c: Connection, sql: String, bind: (java.sql.PreparedStatement) -> Unit, map: (java.sql.ResultSet) -> T): List<T> =
        c.prepareStatement(sql).use { ps -> bind(ps); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    override fun checkpoint(bindingDigest: String): FinanceCheckpointRow? = snapshot(bindingDigest) { emptyList<Unit>() }.progress

    override fun decisions(bindingDigest: String, afterSequence: Long, limit: Int) = snapshot(bindingDigest) { c ->
        query(c, "SELECT sequence, action_namespace, action_domain, action_id, disposition, input_digest, prior_chain, checksum " +
            "FROM boundary.calcify_finance_decision WHERE binding_digest = ? AND sequence > ? ORDER BY sequence LIMIT ?",
            { it.setString(1, bindingDigest); it.setLong(2, afterSequence); it.setInt(3, limit) },
            { FinanceDecisionRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8)) })
    }

    override fun positions(bindingDigest: String, accounts: Set<String>?) = snapshot(bindingDigest) { c ->
        if (accounts != null && accounts.isEmpty()) emptyList() else {
            val filter = if (accounts == null) "" else " AND account_id = ANY (?)"
            query(c, "SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ?$filter ORDER BY account_id, asset_id",
                { ps -> ps.setString(1, bindingDigest); if (accounts != null) ps.setArray(2, c.createArrayOf("text", accounts.sorted().toTypedArray())) },
                { FinancePositionRow(it.getString(1), it.getString(2), it.getLong(3)) })
        }
    }

    override fun obligations(bindingDigest: String, accounts: Set<String>?) = snapshot(bindingDigest) { c ->
        if (accounts != null && accounts.isEmpty()) emptyList() else {
            val filter = if (accounts == null) "" else " AND (buyer_account_id = ANY (?) OR seller_account_id = ANY (?))"
            query(c, "SELECT run_id, venue_session_id, instrument_id, execution_id, buyer_account_id, seller_account_id, quantity, price_nanos, status, cash_residual, share_residual " +
                "FROM boundary.calcify_finance_obligation WHERE binding_digest = ?$filter ORDER BY run_id, venue_session_id, instrument_id, execution_id",
                { ps -> ps.setString(1, bindingDigest); if (accounts != null) { val a = c.createArrayOf("text", accounts.sorted().toTypedArray()); ps.setArray(2, a); ps.setArray(3, a) } },
                { FinanceObligationRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7), it.getLong(8), it.getString(9), it.getLong(10), it.getLong(11)) })
        }
    }

    override fun attempts(bindingDigest: String, accounts: Set<String>?) = snapshot(bindingDigest) { c ->
        if (accounts != null && accounts.isEmpty()) emptyList() else {
            val join = if (accounts == null) "" else
                " JOIN boundary.calcify_finance_obligation o ON o.binding_digest = a.binding_digest AND o.run_id = a.run_id AND o.venue_session_id = a.venue_session_id" +
                    " AND o.instrument_id = a.instrument_id AND o.execution_id = a.execution_id"
            val filter = if (accounts == null) "" else " AND (o.buyer_account_id = ANY (?) OR o.seller_account_id = ANY (?))"
            query(c, "SELECT a.run_id, a.venue_session_id, a.instrument_id, a.execution_id, a.attempt, a.disposition FROM boundary.calcify_finance_attempt a$join " +
                "WHERE a.binding_digest = ?$filter ORDER BY a.run_id, a.venue_session_id, a.instrument_id, a.execution_id, a.attempt",
                { ps -> ps.setString(1, bindingDigest); if (accounts != null) { val arr = c.createArrayOf("text", accounts.sorted().toTypedArray()); ps.setArray(2, arr); ps.setArray(3, arr) } },
                { FinanceAttemptRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6)) })
        }
    }
}
