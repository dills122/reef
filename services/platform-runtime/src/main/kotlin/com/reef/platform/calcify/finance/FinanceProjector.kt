package com.reef.platform.calcify.finance

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** The projection (checkpoint) is not a prefix of the decision log it is being fed: ahead of it, or on a different chain. Never repaired. */
internal class FinanceProjectionDiverged(message: String) : IllegalStateException(message)

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
internal class FinanceProjector(private val chain: DecisionChain, private val dataSource: DataSource) {
    constructor(ledger: FinanceLedger, dataSource: DataSource) : this(FiniteChain(ledger), dataSource)
    companion object {
        /** Migration 0014's bound: 128 captures + 160 settle attempts + 8 controls inside 320. Every finite binding. */
        const val FINITE_SEQUENCE_BOUND = 320L
        /** Migration 0016's ceiling for a capacity binding. */
        const val MAX_SEQUENCE_BOUND = 1_000_000_000L
    }

    /** The stored progress `(lastSequence, lastChain)`, or null before [open]. */
    fun checkpoint(bindingDigest: String): Pair<Long, String>? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps ->
            ps.setString(1, bindingDigest)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
        }
    }

    /**
     * Creates the checkpoint at genesis for [bindingDigest]; repeated calls with an equal or newer epoch only advance the fence. [sequenceBound] is the
     * most decisions the projection of this binding will ever hold: 320 for a finite binding (migration 0014), larger for a capacity binding (migration 0016).
     * It is fixed at creation, and a call that names a different bound for an existing binding is refused.
     */
    fun open(bindingDigest: String, epoch: Long, sequenceBound: Long = FINITE_SEQUENCE_BOUND) {
        require(epoch > 0) { "epoch must be positive" }
        require(sequenceBound in FINITE_SEQUENCE_BOUND..MAX_SEQUENCE_BOUND) { "sequence bound must be in $FINITE_SEQUENCE_BOUND..$MAX_SEQUENCE_BOUND" }
        tx { c ->
            c.prepareStatement("""INSERT INTO boundary.calcify_finance_checkpoint(binding_digest, owner_epoch, last_sequence, last_chain, sequence_bound)
                VALUES (?, ?, 0, ?, ?) ON CONFLICT (binding_digest) DO NOTHING""").use { it.setString(1, bindingDigest); it.setLong(2, epoch); it.setString(3, chain.genesisChain); it.setLong(4, sequenceBound); it.executeUpdate() }
            requireBound(c, bindingDigest, sequenceBound)
            fence(c, bindingDigest, epoch)
        }
    }

    /**
     * Claims the projection for this process and returns the owner epoch every write of this process must carry. The first claim for a binding
     * creates the checkpoint at genesis with epoch 1; every later claim atomically raises the stored epoch by one. A process holding an older epoch
     * is refused with [StaleProjectionEpoch] on its next write, so two instances are never silent: the older one dies. (Docs: CALCIFY_FINITE_P3_OWNER_EPOCH_DESIGN.md, option B.)
     */
    fun claim(bindingDigest: String, sequenceBound: Long = FINITE_SEQUENCE_BOUND): Long = tx { c ->
        require(sequenceBound in FINITE_SEQUENCE_BOUND..MAX_SEQUENCE_BOUND) { "sequence bound must be in $FINITE_SEQUENCE_BOUND..$MAX_SEQUENCE_BOUND" }
        val created = c.prepareStatement("""INSERT INTO boundary.calcify_finance_checkpoint(binding_digest, owner_epoch, last_sequence, last_chain, sequence_bound)
            VALUES (?, 1, 0, ?, ?) ON CONFLICT (binding_digest) DO NOTHING""").use { it.setString(1, bindingDigest); it.setString(2, chain.genesisChain); it.setLong(3, sequenceBound); it.executeUpdate() }
        requireBound(c, bindingDigest, sequenceBound)
        if (created == 1) 1L else c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET owner_epoch = owner_epoch + 1 WHERE binding_digest = ? RETURNING owner_epoch").use { ps ->
            ps.setString(1, bindingDigest); ps.executeQuery().use { r -> check(r.next()) { "checkpoint vanished during claim" }; r.getLong(1) }
        }
    }

    /**
     * Returns the number of records newly applied (replayed records that match the stored decision are skipped).
     *
     * Every record is verified (order, input digest, replay equality, hash link) in memory first; the row deltas of all newly applied records are then written
     * with one set-based statement per table (`INSERT ... SELECT FROM unnest(arrays)`), not one statement per record. Measured at 120 decisions per call, the
     * per-record statements capped the projector at about 740 decisions/s (CAL-FIN-P1); the set-based form is the research variant that reached several thousand.
     */
    fun apply(bindingDigest: String, epoch: Long, records: List<Pair<Decision, FinanceInput>>): Int = tx { c ->
        var (lastSequence, lastChain) = fence(c, bindingDigest, epoch)
        var previous = 0L
        val rows = Rows()
        val replays = ArrayList<Decision>()
        for ((decision, input) in records) {
            require(decision.sequence > previous) { "records must arrive in strictly increasing sequence order" }
            previous = decision.sequence
            require(input.digest() == decision.inputDigest) { "input does not match the decision's input digest" }
            if (decision.sequence <= lastSequence) { replays += decision; continue }
            require(chain.verifyLink(lastChain, lastSequence + 1, decision)) { "decision does not continue the checkpoint chain" }
            rows.add(decision, input)
            lastSequence = decision.sequence; lastChain = decision.checksum
        }
        if (replays.isNotEmpty()) {
            val stored = storedChecksums(c, bindingDigest, replays.map { it.sequence })
            for (d in replays) {
                val checksum = stored[d.sequence]
                require(checksum != null) { "replayed record is missing from the stored decisions" }
                require(checksum == d.checksum) { "replayed record differs from the stored decision" }
            }
        }
        val applied = rows.count
        if (applied > 0) {
            write(c, bindingDigest, rows)
            c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET last_sequence = ?, last_chain = ? WHERE binding_digest = ?")
                .use { it.setLong(1, lastSequence); it.setString(2, lastChain); it.setString(3, bindingDigest); it.executeUpdate() }
        }
        applied
    }

    /** The projection of a binding keeps the sequence bound it was created with; a caller that expects another is looking at the wrong profile. */
    private fun requireBound(c: Connection, bindingDigest: String, expected: Long) {
        val stored = c.prepareStatement("SELECT sequence_bound FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps ->
            ps.setString(1, bindingDigest); ps.executeQuery().use { r -> check(r.next()) { "checkpoint vanished" }; r.getLong(1) }
        }
        check(stored == expected) { "the projection of this binding was created with sequence bound $stored, not $expected" }
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

    private fun storedChecksums(c: Connection, bindingDigest: String, sequences: List<Long>): Map<Long, String> =
        c.prepareStatement("SELECT sequence, checksum FROM boundary.calcify_finance_decision WHERE binding_digest = ? AND sequence = ANY(?)").use { ps ->
            ps.setString(1, bindingDigest); ps.setArray(2, c.createArrayOf("bigint", sequences.toTypedArray()))
            ps.executeQuery().use { r -> val out = HashMap<Long, String>(); while (r.next()) out[r.getLong(1)] = r.getString(2); out }
        }

    /** The row deltas of one batch, in log order, ready to be written as arrays. */
    private inner class Rows {
        var count = 0
        val seq = ArrayList<Long>(); val ns = ArrayList<String>(); val dom = ArrayList<String>(); val aid = ArrayList<String>(); val disp = ArrayList<String>()
        val inDigest = ArrayList<String>(); val prior = ArrayList<String>(); val sum = ArrayList<String>()
        val oRun = ArrayList<String>(); val oVen = ArrayList<String>(); val oIns = ArrayList<String>(); val oEx = ArrayList<String>(); val oBuy = ArrayList<String>(); val oSell = ArrayList<String>()
        val oQty = ArrayList<Long>(); val oPx = ArrayList<Long>(); val oCash = ArrayList<Long>(); val oShares = ArrayList<Long>(); val oStatus = ArrayList<String>()
        /** Obligations inserted by this batch, by execution, so a settlement in the same batch inserts the row already settled instead of updating it afterwards. */
        private val inBatch = HashMap<ExecutionKey, Int>()
        val aRun = ArrayList<String>(); val aVen = ArrayList<String>(); val aIns = ArrayList<String>(); val aEx = ArrayList<String>(); val aNo = ArrayList<Int>(); val aDisp = ArrayList<String>()
        val uRun = ArrayList<String>(); val uVen = ArrayList<String>(); val uIns = ArrayList<String>(); val uEx = ArrayList<String>()
        val net = LinkedHashMap<Pair<String, String>, Long>()

        fun add(d: Decision, input: FinanceInput) {
            count++
            seq += d.sequence; ns += d.action.namespace; dom += d.action.domain; aid += d.action.actionId; disp += d.disposition.name
            inDigest += d.inputDigest; prior += d.priorChain; sum += d.checksum
            for (leg in d.legs) net.merge(leg.account.value to leg.asset.value, leg.amount) { a, b -> Math.addExact(a, b) }
            when (input) {
                is FinanceInput.Capture -> if (d.disposition == Disposition.CAPTURED) {
                    val e = input.execution
                    oRun += e.runId; oVen += e.venueSessionId; oIns += e.instrumentId; oEx += e.executionId; oBuy += input.buyer.value; oSell += input.seller.value
                    oQty += input.quantity; oPx += input.priceNanos; oCash += Math.multiplyExact(input.quantity, input.priceNanos); oShares += input.quantity; oStatus += "PENDING"
                    inBatch[e] = oEx.size - 1
                }
                is FinanceInput.Settle -> if (d.disposition in settleDispositions) {
                    val e = input.execution
                    aRun += e.runId; aVen += e.venueSessionId; aIns += e.instrumentId; aEx += e.executionId; aNo += input.attempt; aDisp += d.disposition.name
                    if (d.disposition == Disposition.SETTLED) {
                        val row = inBatch[e]
                        if (row != null) { oStatus[row] = "SETTLED"; oCash[row] = 0L; oShares[row] = 0L }
                        else { uRun += e.runId; uVen += e.venueSessionId; uIns += e.instrumentId; uEx += e.executionId }
                    }
                }
                is FinanceInput.Fund -> Unit
            }
        }
    }

    private fun write(c: Connection, digest: String, r: Rows) {
        fun text(v: List<String>) = c.createArrayOf("text", v.toTypedArray())
        fun big(v: List<Long>) = c.createArrayOf("bigint", v.toTypedArray())
        c.prepareStatement("""INSERT INTO boundary.calcify_finance_decision(binding_digest, sequence, action_namespace, action_domain, action_id, disposition, input_digest, prior_chain, checksum)
            SELECT ?, t.s, t.n, t.d, t.a, t.p, t.g, t.r, t.k FROM unnest(?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[]) AS t(s,n,d,a,p,g,r,k)""").use {
            it.setString(1, digest); it.setArray(2, big(r.seq)); it.setArray(3, text(r.ns)); it.setArray(4, text(r.dom)); it.setArray(5, text(r.aid))
            it.setArray(6, text(r.disp)); it.setArray(7, text(r.inDigest)); it.setArray(8, text(r.prior)); it.setArray(9, text(r.sum)); it.executeUpdate()
        }
        if (r.net.isNotEmpty()) c.prepareStatement("""INSERT INTO boundary.calcify_finance_position(binding_digest, account_id, asset_id, amount)
            SELECT ?, t.a, t.s, t.m FROM unnest(?::text[], ?::text[], ?::bigint[]) AS t(a,s,m)
            ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount""").use {
            it.setString(1, digest); it.setArray(2, text(r.net.keys.map { k -> k.first })); it.setArray(3, text(r.net.keys.map { k -> k.second })); it.setArray(4, big(r.net.values.toList())); it.executeUpdate()
        }
        if (r.oEx.isNotEmpty()) c.prepareStatement("""INSERT INTO boundary.calcify_finance_obligation(binding_digest, run_id, venue_session_id, instrument_id, execution_id, buyer_account_id,
            seller_account_id, quantity, price_nanos, status, cash_residual, share_residual)
            SELECT ?, t.r, t.v, t.i, t.e, t.b, t.s, t.q, t.p, t.st, t.c, t.sh FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::bigint[], ?::bigint[], ?::bigint[], ?::bigint[], ?::text[])
            AS t(r,v,i,e,b,s,q,p,c,sh,st)""").use {
            it.setString(1, digest); it.setArray(2, text(r.oRun)); it.setArray(3, text(r.oVen)); it.setArray(4, text(r.oIns)); it.setArray(5, text(r.oEx)); it.setArray(6, text(r.oBuy))
            it.setArray(7, text(r.oSell)); it.setArray(8, big(r.oQty)); it.setArray(9, big(r.oPx)); it.setArray(10, big(r.oCash)); it.setArray(11, big(r.oShares)); it.setArray(12, text(r.oStatus)); it.executeUpdate()
        }
        if (r.aEx.isNotEmpty()) c.prepareStatement("""INSERT INTO boundary.calcify_finance_attempt(binding_digest, run_id, venue_session_id, instrument_id, execution_id, attempt, disposition)
            SELECT ?, t.r, t.v, t.i, t.e, t.n, t.d FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::int[], ?::text[]) AS t(r,v,i,e,n,d)""").use {
            it.setString(1, digest); it.setArray(2, text(r.aRun)); it.setArray(3, text(r.aVen)); it.setArray(4, text(r.aIns)); it.setArray(5, text(r.aEx))
            it.setArray(6, c.createArrayOf("int4", r.aNo.toTypedArray())); it.setArray(7, text(r.aDisp)); it.executeUpdate()
        }
        if (r.uEx.isNotEmpty()) {
            val n = c.prepareStatement("""UPDATE boundary.calcify_finance_obligation o SET status = 'SETTLED', cash_residual = 0, share_residual = 0
                FROM unnest(?::text[], ?::text[], ?::text[], ?::text[]) AS t(r,v,i,e)
                WHERE o.binding_digest = ? AND o.run_id = t.r AND o.venue_session_id = t.v AND o.instrument_id = t.i AND o.execution_id = t.e AND o.status = 'PENDING'""").use {
                it.setArray(1, text(r.uRun)); it.setArray(2, text(r.uVen)); it.setArray(3, text(r.uIns)); it.setArray(4, text(r.uEx)); it.setString(5, digest); it.executeUpdate()
            }
            check(n == r.uEx.size) { "settled decision for a missing or already settled obligation" }
        }
    }

    private val settleDispositions = setOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.BALANCE_OVERFLOW)

    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try { block(c).also { c.commit() } } catch (e: Throwable) { try { c.rollback() } catch (_: SQLException) {}; throw e }
    }
}
