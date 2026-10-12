package com.reef.platform.calcify.finance

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** A projection write from an owner whose epoch is older than the stored one; nothing was written. */
internal class StaleProjectionEpoch(message: String) : IllegalStateException(message)

/**
 * O4 projector: applies published decision records to the `boundary.calcify_finance_*` read projection. One transaction per call:
 * the checkpoint row is locked, the owner epoch is fenced, each record is chained and verified against the checkpoint, row deltas
 * are applied, and the checkpoint advances with them, so a crash after commit followed by a redelivery applies each sequence once
 * and any failure (stale epoch, gap, bad link, bad input, arithmetic overflow) rolls the whole batch back.
 *
 * Projection only: it carries no financial authority, never re-decides, and a record is trusted only as far as its hash link,
 * its input digest and the checkpoint continuity verify.
 */
internal class FinanceProjector(private val ledger: FinanceLedger, private val dataSource: DataSource) {
    /** Creates the checkpoint at genesis for [bindingDigest]; repeated calls with an equal or newer epoch only advance the fence. */
    fun open(bindingDigest: String, epoch: Long) {
        require(epoch > 0) { "epoch must be positive" }
        tx { c ->
            c.prepareStatement("""INSERT INTO boundary.calcify_finance_checkpoint(binding_digest, owner_epoch, last_sequence, last_chain)
                VALUES (?, ?, 0, ?) ON CONFLICT (binding_digest) DO NOTHING""").use { it.setString(1, bindingDigest); it.setLong(2, epoch); it.setString(3, ledger.genesisChain); it.executeUpdate() }
            fence(c, bindingDigest, epoch)
        }
    }

    /** Returns the number of records newly applied (replayed records that match the stored decision are skipped). */
    fun apply(bindingDigest: String, epoch: Long, records: List<Pair<Decision, FinanceInput>>): Int = tx { c ->
        var (lastSequence, lastChain) = fence(c, bindingDigest, epoch)
        var applied = 0
        for ((decision, input) in records) {
            require(input.digest() == decision.inputDigest) { "input does not match the decision's input digest" }
            if (decision.sequence <= lastSequence) {
                val stored = storedChecksum(c, bindingDigest, decision.sequence)
                require(stored == decision.checksum) { "replayed record differs from the stored decision" }
                continue
            }
            require(ledger.verifyLink(lastChain, lastSequence + 1, decision)) { "decision does not continue the checkpoint chain" }
            project(c, bindingDigest, decision, input)
            lastSequence = decision.sequence; lastChain = decision.checksum; applied++
        }
        if (applied > 0) c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET last_sequence = ?, last_chain = ? WHERE binding_digest = ?")
            .use { it.setLong(1, lastSequence); it.setString(2, lastChain); it.setString(3, bindingDigest); it.executeUpdate() }
        applied
    }

    private fun fence(c: Connection, bindingDigest: String, epoch: Long): Pair<Long, String> =
        c.prepareStatement("SELECT owner_epoch, last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ? FOR UPDATE").use { ps ->
            ps.setString(1, bindingDigest)
            ps.executeQuery().use { r ->
                check(r.next()) { "projection not opened for this binding" }
                val stored = r.getLong(1)
                if (epoch < stored) throw StaleProjectionEpoch("stale projector epoch $epoch < $stored")
                if (epoch > stored) c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET owner_epoch = ? WHERE binding_digest = ?")
                    .use { it.setLong(1, epoch); it.setString(2, bindingDigest); it.executeUpdate() }
                r.getLong(2) to r.getString(3)
            }
        }

    private fun storedChecksum(c: Connection, bindingDigest: String, sequence: Long): String? =
        c.prepareStatement("SELECT checksum FROM boundary.calcify_finance_decision WHERE binding_digest = ? AND sequence = ?").use { ps ->
            ps.setString(1, bindingDigest); ps.setLong(2, sequence)
            ps.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }

    private fun project(c: Connection, digest: String, d: Decision, input: FinanceInput) {
        c.prepareStatement("""INSERT INTO boundary.calcify_finance_decision(binding_digest, sequence, action_namespace, action_domain, action_id,
            disposition, input_digest, prior_chain, checksum) VALUES (?,?,?,?,?,?,?,?,?)""").use {
            it.setString(1, digest); it.setLong(2, d.sequence); it.setString(3, d.action.namespace); it.setString(4, d.action.domain); it.setString(5, d.action.actionId)
            it.setString(6, d.disposition.name); it.setString(7, d.inputDigest); it.setString(8, d.priorChain); it.setString(9, d.checksum); it.executeUpdate()
        }
        for (leg in d.legs) c.prepareStatement("""INSERT INTO boundary.calcify_finance_position(binding_digest, account_id, asset_id, amount) VALUES (?,?,?,?)
            ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount""").use {
            it.setString(1, digest); it.setString(2, leg.account.value); it.setString(3, leg.asset.value); it.setLong(4, leg.amount); it.executeUpdate()
        }
        when (input) {
            is FinanceInput.Capture -> if (d.disposition == Disposition.CAPTURED) c.prepareStatement("""INSERT INTO boundary.calcify_finance_obligation(binding_digest,
                run_id, venue_session_id, instrument_id, execution_id, buyer_account_id, seller_account_id, quantity, price_nanos, status, cash_residual, share_residual)
                VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?)""").use {
                val e = input.execution
                it.setString(1, digest); it.setString(2, e.runId); it.setString(3, e.venueSessionId); it.setString(4, e.instrumentId); it.setString(5, e.executionId)
                it.setString(6, input.buyer.value); it.setString(7, input.seller.value); it.setLong(8, input.quantity); it.setLong(9, input.priceNanos)
                it.setLong(10, Math.multiplyExact(input.quantity, input.priceNanos)); it.setLong(11, input.quantity); it.executeUpdate()
            }
            is FinanceInput.Settle -> if (d.disposition in settleDispositions) {
                val e = input.execution
                c.prepareStatement("""INSERT INTO boundary.calcify_finance_attempt(binding_digest, run_id, venue_session_id, instrument_id, execution_id, attempt, disposition)
                    VALUES (?,?,?,?,?,?,?)""").use {
                    it.setString(1, digest); it.setString(2, e.runId); it.setString(3, e.venueSessionId); it.setString(4, e.instrumentId); it.setString(5, e.executionId)
                    it.setInt(6, input.attempt); it.setString(7, d.disposition.name); it.executeUpdate()
                }
                if (d.disposition == Disposition.SETTLED) check(c.prepareStatement("""UPDATE boundary.calcify_finance_obligation SET status = 'SETTLED', cash_residual = 0, share_residual = 0
                    WHERE binding_digest = ? AND run_id = ? AND venue_session_id = ? AND instrument_id = ? AND execution_id = ? AND status = 'PENDING'""").use {
                    it.setString(1, digest); it.setString(2, e.runId); it.setString(3, e.venueSessionId); it.setString(4, e.instrumentId); it.setString(5, e.executionId); it.executeUpdate()
                } == 1) { "settled decision for a missing or already settled obligation" }
            }
            is FinanceInput.Fund -> Unit
        }
    }

    private val settleDispositions = setOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)

    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try { block(c).also { c.commit() } } catch (e: Throwable) { try { c.rollback() } catch (_: SQLException) {}; throw e }
    }
}
