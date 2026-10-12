package com.reef.platform.calcify.finance

import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import reef.contracts.calcify.finance.v1.FinanceInputV1

/** Outcome of a submit call. The ledger's disposition is read later by action key from the finance history; the writer has no reply channel. */
internal sealed interface ControlSubmission {
    /** Written in a committed transaction at [offset]. */
    data class Submitted(val offset: Long) : ControlSubmission
    /** The same action key with an identical payload is already in the log; nothing was written. */
    data class AlreadySubmitted(val offset: Long) : ControlSubmission
    /** The same action key exists with a different payload; refused, nothing written. */
    data class ActionConflict(val offset: Long) : ControlSubmission
    /** A writer budget is exhausted (8 Fund writes, 168 control writes); refused, nothing written. */
    data class BudgetExceeded(val reason: String) : ControlSubmission
}

/**
 * The pure state of the CONTROL log as the writer sees it: action key to payload digest and offset, plus write counts. Rebuilt from the
 * read-committed log on every recovery; there is no other writer state (spec section 7: durability without a database).
 */
internal class ControlLogState private constructor(
    private val byKey: MutableMap<ActionKey, Pair<String, Long>>, var funds: Int, var writes: Int,
) {
    constructor() : this(LinkedHashMap(), 0, 0)

    fun lookup(key: ActionKey): Pair<String, Long>? = byKey[key]

    fun admit(input: FinanceInput): ControlSubmission? {
        byKey[input.action]?.let { (digest, offset) -> return if (digest == input.digest()) ControlSubmission.AlreadySubmitted(offset) else ControlSubmission.ActionConflict(offset) }
        if (writes >= MAX_CONTROL_WRITES) return ControlSubmission.BudgetExceeded("control writes exhausted ($MAX_CONTROL_WRITES)")
        if (input is FinanceInput.Fund && funds >= MAX_FUND_WRITES) return ControlSubmission.BudgetExceeded("fund writes exhausted ($MAX_FUND_WRITES)")
        return null
    }

    fun record(input: FinanceInput, offset: Long) {
        check(byKey.put(input.action, input.digest() to offset) == null) { "action key already recorded" }
        writes++; if (input is FinanceInput.Fund) funds++
    }

    companion object {
        const val MAX_FUND_WRITES = 8
        /** 8 funds + 160 settle attempts, equal to the ledger's own pinned budgets so the ledger can never be asked to exceed them. */
        const val MAX_CONTROL_WRITES = 168

        /** Rebuilds the state from raw read-committed records; a record that is not a valid Fund/Settle input or repeats a key is corruption. */
        fun rebuild(records: List<Pair<Long, ByteArray>>): ControlLogState {
            val state = ControlLogState()
            for ((offset, value) in records) {
                val input = try { FinanceWire.fromProto(FinanceInputV1.parseFrom(value)) } catch (e: Exception) { throw IllegalStateException("CONTROL log holds an undecodable record at $offset", e) }
                check(input !is FinanceInput.Capture) { "CONTROL log holds a Capture at $offset" }
                check(input.action !in state.byKey) { "CONTROL log repeats action key at $offset" }
                state.record(input, offset)
            }
            check(state.funds <= MAX_FUND_WRITES && state.writes <= MAX_CONTROL_WRITES) { "CONTROL log exceeds the writer budgets" }
            return state
        }
    }
}

/**
 * S2-A3: the only writer of the CONTROL topic. A separate component with its own Kafka principal and transactional id (spec section 7).
 * Authority is the control credential; the writer stamps OPERATOR itself and never copies a caller-supplied authority, and it can only
 * build Fund and Settle inputs, so a Capture can never be written. Not thread-safe: one operator action at a time.
 */
internal class FiniteControlWriter(
    private val clientProperties: Properties, private val controlTopic: String, private val transactionalId: String,
    private val timeout: Duration = Duration.ofSeconds(30),
) : AutoCloseable {
    private var producer: KafkaProducer<ByteArray, ByteArray>? = null
    private var state: ControlLogState? = null

    private fun newProducer() = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
        putAll(clientProperties)
        put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId); put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout.toMillis()); put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout.toMillis().toInt())
        put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (timeout.toMillis() / 2).toInt())
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
    })

    /** The mandatory recovery order: fence first, then read, then accept calls. Safe to call again after any failure. */
    fun recover() {
        runCatching { producer?.close(Duration.ZERO) }
        state = null
        val fresh = newProducer()
        fresh.initTransactions()                       // (1) fences any zombie and aborts its open transaction before the read below
        producer = fresh
        state = ControlLogState.rebuild(readLog())     // (3)+(4) read from offset 0 without a group, rebuild the key set and counts
    }

    private fun readLog(): List<Pair<Long, ByteArray>> = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        putAll(clientProperties)
        put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none"); put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    }).use { c ->
        val tp = TopicPartition(controlTopic, 0)
        c.assign(listOf(tp)); c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp), timeout).getValue(tp)
        val out = ArrayList<Pair<Long, ByteArray>>()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (c.position(tp, timeout) < end) {
            c.poll(Duration.ofMillis(200)).forEach { out += it.offset() to it.value() }
            check(System.nanoTime() < deadline) { "timed out reading $controlTopic" }
        }
        out
    }

    fun fund(action: ActionKey, account: AccountId, asset: AssetId, amount: Long): ControlSubmission {
        require(amount > 0) { "fund amount must be positive" }
        return submit(FinanceInput.Fund(action, Authority.OPERATOR, account, asset, amount))
    }

    fun settle(action: ActionKey, execution: ExecutionKey, attempt: Int): ControlSubmission {
        require(attempt >= 1) { "attempt must be at least 1" }
        return submit(FinanceInput.Settle(action, Authority.OPERATOR, execution, attempt))
    }

    private fun submit(input: FinanceInput): ControlSubmission {
        val current = state ?: error("writer not recovered: call recover() first")
        current.admit(input)?.let { return it }
        val p = producer!!
        val bytes = FinanceWire.toProto(input).toByteArray()
        try {
            p.beginTransaction()
            val metadata = p.send(ProducerRecord(controlTopic, 0, null, bytes)).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            p.commitTransaction()
            current.record(input, metadata.offset())
            return ControlSubmission.Submitted(metadata.offset())
        } catch (e: Exception) {
            // Outcome unknown (the commit may or may not have happened): never resend blindly. Drop state so the next call must recover
            // by fencing and re-reading the log, which then reports the key as already submitted or not.
            state = null
            runCatching { p.abortTransaction() }
            throw IllegalStateException("control write failed; call recover() before retrying", e)
        }
    }

    override fun close() { runCatching { producer?.close(Duration.ofSeconds(5)) }; producer = null; state = null }
}
