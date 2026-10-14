package com.reef.platform.calcify.finance

import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer

/**
 * Writes operator controls (Fund, repair Settle) to a lane's partition of the CONTROL topic. It keeps no state: a control is idempotent by its action key in the
 * lane's ledger (the same key and bytes again is `PRIOR_RESULT`, different bytes `ACTION_CONFLICT`, neither recorded), so a retry after an ambiguous failure is safe
 * and the caller only has to choose stable action ids. Cash is held per lane (design option S1), so a participant's cash is allocated across lanes explicitly.
 */
internal class CapacityControlWriter(clientProperties: Properties, private val controlTopic: String, private val plan: CapacityPlan) : AutoCloseable {
    private val producer = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
        putAll(clientProperties)
        put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
    })

    /** Writes [input] to [lane]'s control partition and returns its offset once the broker has acknowledged it. */
    fun submit(lane: Int, input: FinanceInput): Long {
        require(lane in 0 until plan.lanes) { "lane $lane is outside the plan" }
        require(input !is FinanceInput.Capture) { "CONTROL carries Fund and Settle only" }
        return producer.send(ProducerRecord(controlTopic, lane, ByteArray(0), FinanceWire.toProto(input).toByteArray())).get(30, TimeUnit.SECONDS).offset()
    }

    /** Funds [account] with cash in each lane: `perLane[i]` quote units in lane i (zero entries are skipped). Action ids are `<prefix>/cash/<lane>`. */
    fun fundCash(prefix: String, account: AccountId, perLane: List<Long>) {
        require(perLane.size == plan.lanes) { "one cash amount per lane" }
        perLane.forEachIndexed { lane, amount -> if (amount > 0) submit(lane, FinanceInput.Fund(ActionKey("operator", "fund", "$prefix/cash/$lane"), Authority.OPERATOR, account, plan.cashAsset, amount)) }
    }

    /** Funds [account] with units of [instrument] in the lane that trades it. */
    fun fundSecurity(prefix: String, account: AccountId, instrument: String, units: Long) {
        val lane = requireNotNull(plan.laneOf(instrument)) { "instrument $instrument is outside the plan" }
        submit(lane, FinanceInput.Fund(ActionKey("operator", "fund", "$prefix/$instrument"), Authority.OPERATOR, account, AssetId(instrument), units))
    }

    /** A repair settlement attempt for an execution that stayed pending (attempt 2 or later; attempt 1 is the automatic one). */
    fun repair(actionId: String, execution: ExecutionKey, attempt: Int) {
        val lane = requireNotNull(plan.laneOf(execution.instrumentId)) { "instrument ${execution.instrumentId} is outside the plan" }
        submit(lane, FinanceInput.Settle(ActionKey("operator", "repair", actionId), Authority.OPERATOR, execution, attempt))
    }

    override fun close() = producer.close()
}
