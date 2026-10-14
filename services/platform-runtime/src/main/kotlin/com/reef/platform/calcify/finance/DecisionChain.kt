package com.reef.platform.calcify.finance

/** What the projector needs to accept a decision log: where the chain starts and whether one decision is the checksum-valid, correctly linked successor. */
internal interface DecisionChain {
    val genesisChain: String
    fun verifyLink(priorChain: String, expectedSequence: Long, d: Decision): Boolean
}

/** The finite profile's chain, from its ledger. */
internal class FiniteChain(private val ledger: FinanceLedger) : DecisionChain {
    override val genesisChain: String get() = ledger.genesisChain
    override fun verifyLink(priorChain: String, expectedSequence: Long, d: Decision): Boolean = ledger.verifyLink(priorChain, expectedSequence, d)
}

/** A capacity lane's chain: the same checksum layout as the finite chain, started from the lane configuration's genesis. Needs no ledger state. */
internal class CapacityChain(config: CapacityConfig) : DecisionChain {
    override val genesisChain: String = genesisOf(config)
    override fun verifyLink(priorChain: String, expectedSequence: Long, d: Decision): Boolean =
        d.sequence == expectedSequence && d.priorChain == priorChain && !d.disposition.neverRecorded &&
            d.checksum == CapacityLedger.checksumOf(d.sequence, d.action, d.inputDigest, d.disposition, d.legs, d.priorChain, d.stateDigest)

    companion object {
        fun genesisOf(config: CapacityConfig): String = Canon.sha(Canon.bytes { str("calcify-finance-genesis-v1"); str(config.digest()) })
    }
}
