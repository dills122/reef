package com.reef.platform.calcify.finance

import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * S3-A2: feeds the O4 projector from FINANCE_DECISIONS. Two modes, one contract (the stored progress must be a prefix of the log, on the same chain at its
 * position: the **SQL cut**; passes are idempotent, so a restart simply runs another pass; projection only, the runner never decides):
 *  - **whole-log** (the original constructor): each pass reads the whole read-committed decision log (at most 320 records for a finite binding), checks
 *    the cut, and hands the log to [FinanceProjector.apply], which skips sequences it already holds after checking them against the stored checksum.
 *  - **tailing** ([tailing]): the source returns only records it has not returned before (the first call starts at sequence 1). The cut is checked as the
 *    log streams past the stored position, only records after the stored progress are applied, in chunks of [maxApply] (each chunk is one projector
 *    transaction, so progress is durable at chunk boundaries), and records read but not yet applied are kept for the next pass if a write fails.
 *    A pass costs the new records, not the whole log, and a restart costs one read of the log with no re-application.
 */
internal class FinanceProjectionRunner private constructor(
    private val readLog: () -> List<Pair<Decision, FinanceInput>>, private val projector: FinanceProjector,
    private val bindingDigest: String, private val ownerEpoch: Long, private val tailing: Boolean, private val maxApply: Int,
    private val sequenceBound: Long = FinanceProjector.FINITE_SEQUENCE_BOUND,
) {
    constructor(readLog: () -> List<Pair<Decision, FinanceInput>>, projector: FinanceProjector, bindingDigest: String, ownerEpoch: Long) :
        this(readLog, projector, bindingDigest, ownerEpoch, false, Int.MAX_VALUE)

    constructor(reader: FinanceEvidenceReader, projector: FinanceProjector, bindingDigest: String, ownerEpoch: Long, decisionsTopic: String) :
        this({ decode(reader.readAll(decisionsTopic)) }, projector, bindingDigest, ownerEpoch)

    /** Nanoseconds spent inside [FinanceProjector.apply] (database time plus its in-memory verification), for operators and measurements. */
    val applyNanos = java.util.concurrent.atomic.AtomicLong()
    private var nextSequence = 1L
    private val pending = ArrayList<Pair<Decision, FinanceInput>>()

    companion object {
        /** A tailing runner: [readNext] returns the decisions after the ones it returned before (empty when caught up). */
        fun tailing(readNext: () -> List<Pair<Decision, FinanceInput>>, projector: FinanceProjector, bindingDigest: String, ownerEpoch: Long, maxApply: Int = 2_000, sequenceBound: Long = FinanceProjector.FINITE_SEQUENCE_BOUND): FinanceProjectionRunner {
            require(maxApply > 0) { "maxApply must be positive" }
            return FinanceProjectionRunner(readNext, projector, bindingDigest, ownerEpoch, true, maxApply, sequenceBound)
        }

        fun decode(records: List<Pair<ByteArray?, ByteArray?>>): List<Pair<Decision, FinanceInput>> = records.map { (_, value) ->
            FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(requireNotNull(value) { "decision record without value" }))
        }
    }

    /** Creates the checkpoint at genesis (or advances the owner fence). Call once before the first pass. */
    fun open() = projector.open(bindingDigest, ownerEpoch, sequenceBound)

    /** One pass; returns the number of decisions newly applied to the projection. */
    fun runOnce(): Int {
        if (tailing) return tailOnce()
        val records = readLog()
        records.forEachIndexed { i, (d, _) -> if (d.sequence != i + 1L) throw FinanceProjectionDiverged("decision log is not a contiguous sequence at index $i") }
        val (storedSequence, storedChain) = projector.checkpoint(bindingDigest) ?: throw FinanceProjectionDiverged("projection checkpoint is missing; open() first")
        if (storedSequence > records.size) throw FinanceProjectionDiverged("projection is at sequence $storedSequence but the decision log has ${records.size} records")
        if (storedSequence > 0 && records[(storedSequence - 1).toInt()].first.checksum != storedChain)
            throw FinanceProjectionDiverged("projection chain at sequence $storedSequence differs from the decision log")
        return if (records.isEmpty()) 0 else projector.apply(bindingDigest, ownerEpoch, records)
    }

    private fun tailOnce(): Int {
        var (stored, chain) = projector.checkpoint(bindingDigest) ?: throw FinanceProjectionDiverged("projection checkpoint is missing; open() first")
        var applied = 0
        // Records already read are applied BEFORE more are read, so a write that keeps failing holds the reader back instead of letting the backlog grow;
        // [pending] never holds more than one read plus what a failed write left behind.
        fun applyPending() {
            // Records at or before the stored progress are already projected; the one at the stored position must be on the stored chain.
            var done = 0
            while (done < pending.size && pending[done].first.sequence <= stored) {
                if (pending[done].first.sequence == stored && pending[done].first.checksum != chain) throw FinanceProjectionDiverged("projection chain at sequence $stored differs from the decision log")
                done++
            }
            if (done > 0) pending.subList(0, done).clear()
            while (pending.isNotEmpty()) {
                val chunk = pending.subList(0, minOf(maxApply, pending.size)).toList()
                val started = System.nanoTime()
                applied += projector.apply(bindingDigest, ownerEpoch, chunk)
                applyNanos.addAndGet(System.nanoTime() - started)
                stored = chunk.last().first.sequence; chain = chunk.last().first.checksum
                pending.subList(0, chunk.size).clear()
            }
        }
        while (true) {
            applyPending()
            val fresh = readLog()
            if (fresh.isEmpty()) break
            for (record in fresh) {
                if (record.first.sequence != nextSequence) throw FinanceProjectionDiverged("decision log is not a contiguous sequence at sequence ${record.first.sequence}, expected $nextSequence")
                nextSequence++; pending += record
            }
        }
        if (stored > nextSequence - 1) throw FinanceProjectionDiverged("projection is at sequence $stored but the decision log has ${nextSequence - 1} records")
        return applied
    }
}
