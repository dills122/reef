package com.reef.platform.calcify.finance

import com.reef.platform.calcify.StreamsLifecycleKv
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.StoreBuilder
import org.apache.kafka.streams.state.Stores

/** What the decoder makes of one consumed record. */
internal sealed interface CapacityItem {
    /** A ledger input, decided in order. */
    data class Run(val input: FinanceInput) : CapacityItem
    /** The record yields no ledger input (for example it carries only replays); consumed, nothing recorded. */
    data object Skip : CapacityItem
    /** The record or member is unusable; it is refused before the ledger and counted, never recorded. [digest] identifies the bytes. */
    data class Bad(val digest: String) : CapacityItem
}

/**
 * Turns the bytes of a consumed record into ledger inputs. The capacity profile's capture mapping (which accounts, which instrument, which run) belongs to its
 * binding and is supplied here; the finite mapping ([FinanceCaptureMapping]) is built around exactly one buyer, one seller and one instrument and is not reused.
 * [role] is `CAPTURE` or `CONTROL`.
 */
internal fun interface CapacityInputDecoder { fun decode(role: String, partition: Int, offset: Long, raw: ByteArray): List<CapacityItem> }

/** Everything the capacity Streams app is bound to. */
internal data class CapacityProcessorConfig(
    val bindingDigest: String, val capacity: CapacityConfig,
    val captureTopic: String, val controlTopic: String, val decisionsTopic: String, val applicationId: String, val storeName: String = "capacity-finance",
) {
    /** Streams names the store's changelog `<application.id>-<store>-changelog`. */
    val changelogTopic: String get() = "$applicationId-$storeName-changelog"
}

/**
 * The capacity profile's Streams processor (design stage S4b): consumes CAPTURE and CONTROL, decides every ledger input in order with the [CapacityLedger], writes
 * the entities of each recorded decision plus head and meta through [CapacityStore] in one batch per consumed record, and forwards each recorded decision
 * (self-describing: the input travels with it) to the decisions topic.
 *
 * Failure handling: the ledger is mutable and moves as it decides, so ANY exception ends the run (the uncaught-exception handler shuts the client down) and the
 * next start restores from the store, which holds only whole records because the writes are applied in one batch inside the Streams transaction. Nothing in
 * [process] may swallow an exception. There is no per-record history list: redelivery is absorbed by the ledger's action keys (`PRIOR_RESULT`), and the restore
 * cut against committed offsets arrives with the launcher stage.
 */
internal class CapacityProcessor(private val config: CapacityProcessorConfig, private val decoder: CapacityInputDecoder) : Processor<ByteArray, ByteArray, String, ByteArray> {
    private var ledger = CapacityLedger(config.capacity)
    private lateinit var context: ProcessorContext<String, ByteArray>
    private lateinit var kv: StreamsLifecycleKv

    /** Inputs refused before the ledger (unusable records) and refused by it, for operators; not persisted. */
    var badInputs = 0L; private set
    var refusals = 0L; private set

    override fun init(context: ProcessorContext<String, ByteArray>) {
        this.context = context
        kv = StreamsLifecycleKv(context.getStateStore(config.storeName))
        CapacityStore.restore(config.capacity, kv, config.bindingDigest)?.let { ledger = it }
    }

    override fun process(record: Record<ByteArray, ByteArray>) {
        val metadata = context.recordMetadata().orElseThrow { IllegalStateException("record metadata unavailable") }
        val role = when (metadata.topic()) { config.captureTopic -> "CAPTURE"; config.controlTopic -> "CONTROL"; else -> throw IllegalStateException("unexpected input topic") }
        require(metadata.partition() == 0) { "capacity finance is single partition" }
        val items = decoder.decode(role, metadata.partition(), metadata.offset(), record.value() ?: ByteArray(0))
        val writes = LinkedHashMap<String, ByteArray>()
        val forwards = ArrayList<Pair<String, ByteArray>>()
        var head: Decision? = null
        for (item in items) {
            when (item) {
                is CapacityItem.Skip -> Unit
                is CapacityItem.Bad -> badInputs++
                is CapacityItem.Run -> {
                    val outcome = ledger.decide(item.input)
                    val d = outcome.decision
                    if (d == null) { refusals++; continue }
                    for (e in ledger.lastWrites) writes[CapacityStore.entityKey(e)] = CapacityStore.entityValue(e)
                    forwards += d.sequence.toString().padStart(20, '0') to FinanceWire.toProto(d, item.input).toByteArray()
                    head = d
                }
            }
        }
        head?.let {
            writes[CapacityStore.HEAD] = FinanceWire.toProto(it).toByteArray()
            writes[CapacityStore.META] = CapacityStore.Meta(config.bindingDigest, config.capacity.digest(), ledger.sequence, ledger.chain, ledger.controls).bytes()
            kv.apply(writes)
        }
        forwards.forEach { (key, value) -> context.forward(record.withKey(key).withValue(value)) }
    }

    /** The ledger the processor is running on (tests and operators). */
    fun currentLedger(): CapacityLedger = ledger
}

internal object CapacityTopology {
    fun storeBuilder(config: CapacityProcessorConfig, persistent: Boolean): StoreBuilder<KeyValueStore<String, ByteArray>> =
        Stores.keyValueStoreBuilder(
            if (persistent) Stores.persistentKeyValueStore(config.storeName) else Stores.inMemoryKeyValueStore(config.storeName),
            Serdes.String(), Serdes.ByteArray()).withCachingDisabled()

    fun build(config: CapacityProcessorConfig, decoder: CapacityInputDecoder, persistent: Boolean = true): Topology = Topology().apply {
        addSource("capture-in", ByteArrayDeserializer(), ByteArrayDeserializer(), config.captureTopic)
        addSource("control-in", ByteArrayDeserializer(), ByteArrayDeserializer(), config.controlTopic)
        addProcessor("capacity", { CapacityProcessor(config, decoder) }, "capture-in", "control-in")
        addStateStore(storeBuilder(config, persistent), "capacity")
        addSink("decisions-out", config.decisionsTopic, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), "capacity")
    }
}
