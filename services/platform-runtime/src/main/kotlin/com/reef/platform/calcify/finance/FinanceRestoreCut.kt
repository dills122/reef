package com.reef.platform.calcify.finance

import com.reef.platform.calcify.LifecycleKv
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

internal enum class RestoreRefusal {
    FINANCE_DECISION_LOG_INVALID, FINANCE_STORE_INVALID, FINANCE_STORE_LOST, FINANCE_STORE_DISAGREES_WITH_DECISIONS,
    FINANCE_HISTORY_CROSS_CHECK, FINANCE_OFFSET_AHEAD_OF_HISTORY, FINANCE_HISTORY_AHEAD_OF_OFFSET, FINANCE_GROUP_OFFSETS_EXPIRED,
}

internal class FinanceRestoreRefused(val code: RestoreRefusal, detail: String, cause: Throwable? = null) : IllegalStateException("${code.name}: $detail", cause)

/** What an activation verifier hands the finance app: the contents it read, never anything it computed. */
internal class FinanceRestoreEvidence(
    val bindingDigest: String,
    val store: LifecycleKv,
    /** Raw record values of the read-committed FINANCE_DECISIONS log, from offset 0. */
    val decisionRecords: List<ByteArray>,
    /** The finance group's committed next offsets; null when the broker reports none. */
    val captureCommittedNext: Long?, val controlCommittedNext: Long?,
    val captureReadCommittedEnd: Long, val controlReadCommittedEnd: Long,
)

internal data class FinanceRestoredCut(val state: FinanceState, val meta: FinanceStore.Meta, val headDecision: Decision?)

/**
 * Spec section 8 restore cut as a pure function over observed evidence. Refuses with a stable code; never repairs.
 * Does not read Kafka: the activation verifier reads the topics and the group and passes the evidence in, so every rule is testable
 * without a broker, and the broker-facing reading is the S2-A4 real-broker test.
 */
internal object FinanceRestoreCut {
    fun verify(ledger: FinanceLedger, evidence: FinanceRestoreEvidence): FinanceRestoredCut {
        val log = try {
            evidence.decisionRecords.map { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it)).first }
        } catch (e: Exception) { throw FinanceRestoreRefused(RestoreRefusal.FINANCE_DECISION_LOG_INVALID, "undecodable decision record", e) }
        if (!ledger.verifyChain(log)) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_DECISION_LOG_INVALID, "decision chain does not verify from genesis")
        val head = log.lastOrNull()

        val restored = try { FinanceStore.verifyAndRestore(ledger, evidence.store, evidence.bindingDigest) }
        catch (e: Exception) { if (e is FinanceRestoreRefused) throw e; throw FinanceRestoreRefused(RestoreRefusal.FINANCE_STORE_INVALID, e.message ?: "store verification failed", e) }
        if (restored == null) {
            if (log.isNotEmpty()) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_STORE_LOST, "decisions exist but the finance store is empty")
            checkOffsets(emptyList(), evidence)
            return FinanceRestoredCut(ledger.genesis(), FinanceStore.Meta(evidence.bindingDigest, 0, FinanceStore.genesisEntryChain(ledger), 0, ledger.genesisChain, 0), null)
        }
        val (state, meta) = restored
        if (state.sequence != log.size.toLong() || state.chain != (head?.checksum ?: ledger.genesisChain))
            throw FinanceRestoreRefused(RestoreRefusal.FINANCE_STORE_DISAGREES_WITH_DECISIONS, "store sequence/chain differs from the decision log head")
        val entries = FinanceStore.entries(evidence.store)
        val recorded = entries.filter { it.decisionChecksum.isNotEmpty() }
        if (recorded.size != log.size || recorded.zip(log).any { (e, d) -> e.decisionChecksum != d.checksum || e.inputDigest != d.inputDigest })
            throw FinanceRestoreRefused(RestoreRefusal.FINANCE_HISTORY_CROSS_CHECK, "recorded history entries do not map one-to-one onto the decision log")
        checkOffsets(entries, evidence)
        return FinanceRestoredCut(state, meta, head)
    }

    private fun checkOffsets(entries: List<FinanceStore.Entry>, e: FinanceRestoreEvidence) {
        for ((role, committed, end) in listOf(Triple("CAPTURE", e.captureCommittedNext, e.captureReadCommittedEnd), Triple("CONTROL", e.controlCommittedNext, e.controlReadCommittedEnd))) {
            val last = entries.filter { it.sourceRole == role }.maxOfOrNull { it.sourceOffset } ?: -1L
            if (last < 0) {
                // No history for this topic: an absent committed offset means 0; a present nonzero one is ahead of history.
                if ((committed ?: 0L) != 0L) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY, "$role committed $committed with no history")
                continue
            }
            if (committed == null || committed == 0L) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_GROUP_OFFSETS_EXPIRED, "$role has history but no committed offset")
            if (committed < last + 1) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_HISTORY_AHEAD_OF_OFFSET, "$role committed $committed, history through $last")
            if (committed > last + 1 || committed > end) throw FinanceRestoreRefused(RestoreRefusal.FINANCE_OFFSET_AHEAD_OF_HISTORY, "$role committed $committed, history through $last, end $end")
        }
    }
}
