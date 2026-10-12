package com.reef.platform.calcify.finance

import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * S3-A2: feeds the O4 projector from FINANCE_DECISIONS. Each pass reads the whole read-committed decision log (at most 320 records for a
 * finite binding), first checks the **SQL cut** (the stored progress must be a prefix of the log: not ahead of it, and on the same chain at
 * its position), then hands the log to [FinanceProjector.apply], which skips sequences it already holds after checking them against the
 * stored checksum. Passes are idempotent, so a restart simply runs another pass. Projection only: the runner never decides.
 */
internal class FinanceProjectionRunner(
    private val readLog: () -> List<Pair<Decision, FinanceInput>>, private val projector: FinanceProjector,
    private val bindingDigest: String, private val ownerEpoch: Long,
) {
    constructor(reader: FinanceEvidenceReader, projector: FinanceProjector, bindingDigest: String, ownerEpoch: Long, decisionsTopic: String) :
        this({ reader.readAll(decisionsTopic).map { (_, value) ->
            FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(requireNotNull(value) { "decision record without value" })) } }, projector, bindingDigest, ownerEpoch)

    /** Creates the checkpoint at genesis (or advances the owner fence). Call once before the first pass. */
    fun open() = projector.open(bindingDigest, ownerEpoch)

    /** One pass; returns the number of decisions newly applied to the projection. */
    fun runOnce(): Int {
        val records = readLog()
        records.forEachIndexed { i, (d, _) -> if (d.sequence != i + 1L) throw FinanceProjectionDiverged("decision log is not a contiguous sequence at index $i") }
        val (storedSequence, storedChain) = projector.checkpoint(bindingDigest) ?: throw FinanceProjectionDiverged("projection checkpoint is missing; open() first")
        if (storedSequence > records.size) throw FinanceProjectionDiverged("projection is at sequence $storedSequence but the decision log has ${records.size} records")
        if (storedSequence > 0 && records[(storedSequence - 1).toInt()].first.checksum != storedChain)
            throw FinanceProjectionDiverged("projection chain at sequence $storedSequence differs from the decision log")
        return if (records.isEmpty()) 0 else projector.apply(bindingDigest, ownerEpoch, records)
    }
}
