package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleBinding
import com.reef.platform.calcify.FiniteOpeningV2
import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.StreamsLifecycleKv
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.StoreBuilder
import org.apache.kafka.streams.state.Stores
import reef.contracts.calcify.finance.v1.FinanceInputV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1

/** Thrown when the 312-entry history bound would be exceeded: the processor halts fail-closed rather than skip a record. */
internal class FinanceHistoryBudgetExceeded : IllegalStateException("FINANCE_HISTORY_BUDGET_EXCEEDED")

/** Everything the finance Streams app is bound to; derived from the registered v2 opening plus the topic names. */
internal data class FinanceProcessorConfig(
    val bindingDigest: String, val captureBinding: FiniteLifecycleBinding, val finance: FinanceConfig,
    val captureTopic: String, val controlTopic: String, val decisionsTopic: String, val applicationId: String, val storeName: String = "finite-finance",
) {
    /** Streams names the store's changelog `<application.id>-<store>-changelog`; the registered binding topic must equal it. */
    val changelogTopic: String get() = "$applicationId-$storeName-changelog"

    init {
        // A run has exactly one binding digest (spec section 8): the store, the projection and the envelope check must all use it.
        require(bindingDigest == captureBinding.digest) { "finance binding digest must equal the capture lane's binding digest" }
    }

    companion object {
        /** Derives everything from a registered v2 opening, so no digest or topic name can be supplied independently of it. */
        fun fromOpening(opening: FiniteOpeningV2): FinanceProcessorConfig = FinanceProcessorConfig(
            opening.digest, opening.toCaptureBinding(), opening.financeConfig(),
            opening.topic("CAPTURE").name, opening.topic("CONTROL").name, opening.topic("FINANCE_DECISIONS").name, opening.financeApplicationId,
        ).also { require(opening.topic("FINANCE_CHANGELOG").name == it.changelogTopic) { "registered changelog topic differs from the one Streams will use" } }
    }
}

/**
 * S2-A2 finance processor (option B ordering): consumes CAPTURE and CONTROL, assigns every consumed ledger input the next position
 * of its OWN persisted history, decides it with the unchanged O3a ledger, and forwards recorded decisions to FINANCE_DECISIONS.
 *
 * Per consumed record, the full set of store writes (entities, history entries, head, meta) is computed first and applied together;
 * the cached state moves only after the writes succeed; any exception propagates and the Streams transaction aborts, so a record is
 * all-or-nothing (broker-side atomicity is proven on a real broker, not here). Restore is [FinanceStore.verifyAndRestore], once.
 */
internal class FinanceProcessor(private val config: FinanceProcessorConfig) : Processor<ByteArray, ByteArray, String, ByteArray> {
    private val ledger = FinanceLedger(config.finance)
    private lateinit var context: ProcessorContext<String, ByteArray>
    private lateinit var kv: StreamsLifecycleKv
    private var state = ledger.genesis()
    private var historyPosition = 0L
    private var entryHead = FinanceStore.genesisEntryChain(ledger)

    override fun init(context: ProcessorContext<String, ByteArray>) {
        this.context = context
        kv = StreamsLifecycleKv(context.getStateStore(config.storeName))
        FinanceStore.verifyAndRestore(ledger, kv, config.bindingDigest)?.let { (restored, meta) ->
            state = restored; historyPosition = meta.historyPosition; entryHead = meta.entryChainHead
        }
    }

    private sealed interface Item {
        val ordinal: Int
        data class Run(override val ordinal: Int, val input: FinanceInput) : Item
        data class Bad(override val ordinal: Int, val digest: String) : Item
        data object None : Item { override val ordinal = 0 }
    }

    override fun process(record: Record<ByteArray, ByteArray>) {
        val metadata = context.recordMetadata().orElseThrow { IllegalStateException("record metadata unavailable") }
        val role = when (metadata.topic()) { config.captureTopic -> "CAPTURE"; config.controlTopic -> "CONTROL"; else -> throw IllegalStateException("unexpected input topic") }
        require(metadata.partition() == 0) { "finite finance is single partition" }
        val raw = record.value() ?: ByteArray(0)
        val items = if (role == "CAPTURE") capture(raw) else control(raw)
        // Stage the whole record against local copies; nothing is visible until every entry has been computed.
        var staged = state
        var position = historyPosition
        var head = entryHead
        val writes = LinkedHashMap<String, ByteArray>()
        val forwards = ArrayList<Pair<String, ByteArray>>()
        var headDecision: Decision? = null
        for (item in items) {
            if (position + 1 > FinanceStore.MAX_HISTORY_ENTRIES) throw FinanceHistoryBudgetExceeded()
            val (kind, digest, outcome, decision) = when (item) {
                is Item.None -> StagedEntry(FinanceStore.EntryKind.NO_INPUT, FiniteLifecycleContract.sha(raw), "NO_INPUT", null)
                is Item.Bad -> StagedEntry(FinanceStore.EntryKind.INPUT, item.digest, Disposition.INVALID_INPUT.name, null)
                is Item.Run -> {
                    val result = ledger.decide(staged, item.input)
                    result.decision?.let { d ->
                        writes += FinanceStore.entityWrites(staged, result.state)
                        forwards += d.sequence.toString().padStart(20, '0') to FinanceWire.toProto(d, item.input).toByteArray()
                        headDecision = d
                    }
                    staged = result.state
                    StagedEntry(FinanceStore.EntryKind.INPUT, item.input.digest(), result.disposition.name, result.decision)
                }
            }
            position++
            val checksum = decision?.checksum ?: ""
            val chain = FinanceStore.entryChain(head, position, role, metadata.partition(), metadata.offset(), item.ordinal, kind, digest, outcome, checksum)
            val entry = FinanceStore.Entry(position, role, metadata.partition(), metadata.offset(), item.ordinal, kind, digest, outcome, checksum, head, chain)
            writes[FinanceStore.historyKey(position)] = entry.bytes()
            head = chain
        }
        headDecision?.let { writes[FinanceStore.HEAD] = FinanceWire.toProto(it).toByteArray() }
        writes[FinanceStore.META] = FinanceStore.Meta(config.bindingDigest, position, head, staged.sequence, staged.chain, staged.controls).bytes()
        kv.apply(writes)
        state = staged; historyPosition = position; entryHead = head
        forwards.forEach { (key, value) -> context.forward(record.withKey(key).withValue(value)) }
    }

    private data class StagedEntry(val kind: FinanceStore.EntryKind, val digest: String, val outcome: String, val decision: Decision?)

    private fun capture(raw: ByteArray): List<Item> {
        val envelope = try { FiniteLifecycleCaptureV1.parseFrom(raw) } catch (e: com.google.protobuf.InvalidProtocolBufferException) { return listOf(Item.Bad(0, FiniteLifecycleContract.sha(raw))) }
        if (envelope.schema != FiniteLifecycleContract.CAPTURE_SCHEMA || envelope.finiteBindingDigest != config.captureBinding.digest ||
            envelope.contentDigest != FiniteLifecycleContract.digest(envelope)) return listOf(Item.Bad(0, FiniteLifecycleContract.sha(raw)))
        val mapped = FinanceCaptureMapping.map(envelope, config.captureBinding)
        if (mapped.isEmpty()) return listOf(Item.None)
        return mapped.map {
            when (it) {
                is FinanceCaptureMapping.Mapped.Ready -> Item.Run(it.memberOrdinal, it.input)
                is FinanceCaptureMapping.Mapped.Invalid -> Item.Bad(it.memberOrdinal, it.memberDigest)
            }
        }
    }

    /** CONTROL carries Fund and Settle only; a Capture body, an unparseable body or an invalid input is an INVALID_INPUT entry. */
    private fun control(raw: ByteArray): List<Item> {
        val bad = listOf(Item.Bad(0, FiniteLifecycleContract.sha(raw)))
        val input = try { FinanceWire.fromProto(FinanceInputV1.parseFrom(raw)) } catch (e: Exception) { return bad }
        if (input is FinanceInput.Capture || input is FinanceInput.CaptureSettle) return bad
        return listOf(Item.Run(0, input))
    }
}

internal object FinanceTopology {
    const val SESSION_TIMEOUT_MS = 6_000
    const val HEARTBEAT_INTERVAL_MS = 1_000
    /**
     * Producer batching for the changelog, decision and transaction traffic: every store put is one changelog record, and unbatched they leave in many tiny requests.
     * Measured (CAL-FIN-P2, stand-in lane, ten 300-byte puts per input): 9.3k to 11.8k inputs/s at the Kafka defaults, 23.8k to 35.0k at 256 KiB and 20 ms.
     * Cost: up to 20 ms added to the time before a decision is visible (the 100 ms commit interval already dominates it).
     */
    const val PRODUCER_BATCH_BYTES = 262_144
    const val PRODUCER_LINGER_MS = 20

    /**
     * Spec section 8 pinned Streams configuration. The processor's all-or-nothing-per-record claim holds only under exactly-once, so
     * the launcher must take its properties from here; [verifyPinned] refuses anything weaker.
     */
    fun streamsProperties(applicationId: String, bootstrap: String, stateDir: String): java.util.Properties = java.util.Properties().apply {
        put("application.id", applicationId); put("bootstrap.servers", bootstrap); put("state.dir", stateDir)
        put("processing.guarantee", "exactly_once_v2"); put("num.stream.threads", 1); put("num.standby.replicas", 0)
        put("commit.interval.ms", 100); put("max.task.idle.ms", 0); put("auto.offset.reset", "earliest")
        put("replication.factor", 1)
        // Owner decision 2026-10-13: a crashed member is evicted after 6 s, not the 45 s default (measured SIGKILL restart: 44.1 s default, 6.1 s at 6,000).
        put("consumer.session.timeout.ms", SESSION_TIMEOUT_MS); put("consumer.heartbeat.interval.ms", HEARTBEAT_INTERVAL_MS)
        put("producer.batch.size", PRODUCER_BATCH_BYTES); put("producer.linger.ms", PRODUCER_LINGER_MS)
    }

    /**
     * Stops a finance Streams client AND leaves its consumer group. The default `KafkaStreams.close(Duration)` does not send LeaveGroup, so
     * a restarted member waits out the previous member's 45 s session timeout (measured 43.1 s to RUNNING, versus 0.4 to 0.5 s with this).
     * A crash still waits out the session timeout; this only fixes the clean-stop path.
     */
    fun closeLeavingGroup(streams: KafkaStreams, timeout: java.time.Duration): Boolean =
        streams.close(KafkaStreams.CloseOptions().timeout(timeout).leaveGroup(true))

    fun verifyPinned(props: java.util.Properties) {
        val pinned = mapOf("processing.guarantee" to "exactly_once_v2", "num.stream.threads" to "1", "num.standby.replicas" to "0", "auto.offset.reset" to "earliest")
        for ((k, v) in pinned) require(props[k]?.toString() == v) { "finance Streams property $k must be $v" }
        require(props["commit.interval.ms"] != null && props["max.task.idle.ms"] != null) { "commit.interval.ms and max.task.idle.ms must be pinned" }
        require(props["producer.batch.size"]?.toString()?.toIntOrNull() == PRODUCER_BATCH_BYTES) { "producer.batch.size must be pinned at $PRODUCER_BATCH_BYTES" }
        require(props["producer.linger.ms"]?.toString()?.toIntOrNull() == PRODUCER_LINGER_MS) { "producer.linger.ms must be pinned at $PRODUCER_LINGER_MS" }
    }

    fun storeBuilder(config: FinanceProcessorConfig, persistent: Boolean): StoreBuilder<KeyValueStore<String, ByteArray>> =
        Stores.keyValueStoreBuilder(
            if (persistent) Stores.persistentKeyValueStore(config.storeName) else Stores.inMemoryKeyValueStore(config.storeName),
            Serdes.String(), Serdes.ByteArray()).withCachingDisabled()

    fun build(config: FinanceProcessorConfig, persistent: Boolean = true): Topology = Topology().apply {
        addSource("capture-in", ByteArrayDeserializer(), ByteArrayDeserializer(), config.captureTopic)
        addSource("control-in", ByteArrayDeserializer(), ByteArrayDeserializer(), config.controlTopic)
        addProcessor("finance", { FinanceProcessor(config) }, "capture-in", "control-in")
        addStateStore(storeBuilder(config, persistent), "finance")
        addSink("decisions-out", config.decisionsTopic, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "finance")
    }
}
