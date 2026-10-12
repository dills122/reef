package com.reef.platform.calcify.finance

import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * S3-A2: feeds the O4 projector from FINANCE_DECISIONS. Each pass reads the whole read-committed decision log (at most 320 records for a
 * finite binding) and hands it to [FinanceProjector.apply], which skips every sequence it already holds after checking it against the
 * stored checksum, so passes are idempotent and a restart simply runs another pass. Projection only: the runner never decides, and a record
 * is trusted no further than the projector's hash link, input digest and checkpoint continuity checks.
 */
internal class FinanceProjectionRunner(
    private val reader: FinanceEvidenceReader, private val projector: FinanceProjector,
    private val bindingDigest: String, private val ownerEpoch: Long, private val decisionsTopic: String,
) {
    /** Creates the checkpoint at genesis (or advances the owner fence). Call once before the first pass. */
    fun open() = projector.open(bindingDigest, ownerEpoch)

    /** One pass; returns the number of decisions newly applied to the projection. */
    fun runOnce(): Int {
        val records = reader.readAll(decisionsTopic).map { (_, value) ->
            FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(requireNotNull(value) { "decision record without value" }))
        }
        return if (records.isEmpty()) 0 else projector.apply(bindingDigest, ownerEpoch, records)
    }
}
