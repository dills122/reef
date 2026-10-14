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

/**
 * Everything the capacity Streams app is bound to. A lane is a partition: task N of the app runs lane N, over partition N of the capture and control topics,
 * with its own store partition, and writes partition N of the decisions topic. [spec] gives each lane its binding and configuration.
 */
internal class CapacityProcessorConfig(
    val lanes: Int, val spec: (Int) -> CapacityLaneSpec,
    val captureTopic: String, val controlTopic: String, val decisionsTopic: String, val applicationId: String, val storeName: String = "capacity-finance",
    /**
     * The last decision of each lane's published log as `(sequence, checksum)`, or null for an empty log, read by the launcher before the app starts. When supplied, a lane
     * whose restored store is not exactly at that head refuses to run (lost or rolled-back store, or a tampered one). Exactly-once makes the two agree whenever nothing
     * has been lost; null for the function itself means "not checked" (tests).
     */
    val logHead: ((Int) -> Pair<Long, String>?)? = null,
) {
    /** One lane, lane 0. */
    constructor(bindingDigest: String, capacity: CapacityConfig, captureTopic: String, controlTopic: String, decisionsTopic: String, applicationId: String, storeName: String = "capacity-finance") :
        this(1, { lane -> require(lane == 0) { "single-lane configuration" }; CapacityLaneSpec(bindingDigest, capacity) }, captureTopic, controlTopic, decisionsTopic, applicationId, storeName)

    constructor(plan: CapacityPlan, captureTopic: String, controlTopic: String, decisionsTopic: String, applicationId: String, storeName: String = "capacity-finance", logHead: ((Int) -> Pair<Long, String>?)? = null) :
        this(plan.lanes, plan::spec, captureTopic, controlTopic, decisionsTopic, applicationId, storeName, logHead)

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
    private lateinit var ledger: CapacityLedger
    private var lane = 0
    private lateinit var laneSpec: CapacityLaneSpec
    private lateinit var context: ProcessorContext<String, ByteArray>
    private lateinit var kv: StreamsLifecycleKv

    /** Inputs refused before the ledger (unusable records) and refused by it, for operators; not persisted. */
    var badInputs = 0L; private set
    var refusals = 0L; private set

    override fun init(context: ProcessorContext<String, ByteArray>) {
        this.context = context
        kv = StreamsLifecycleKv(context.getStateStore(config.storeName))
        lane = context.taskId().partition()
        require(lane in 0 until config.lanes) { "partition $lane is outside the ${config.lanes} configured lanes" }
        laneSpec = config.spec(lane)
        ledger = CapacityStore.restore(laneSpec.capacity, kv, laneSpec.bindingDigest) ?: CapacityLedger(laneSpec.capacity)
        config.logHead?.let { head ->
            val expected = head(lane)
            check(if (expected == null) ledger.sequence == 0L else ledger.sequence == expected.first && ledger.chain == expected.second) {
                "lane $lane: the restored store (sequence ${ledger.sequence}) is not the head of the decision log (${expected?.first ?: 0})"
            }
        }
    }

    override fun process(record: Record<ByteArray, ByteArray>) {
        val metadata = context.recordMetadata().orElseThrow { IllegalStateException("record metadata unavailable") }
        val role = when (metadata.topic()) { config.captureTopic -> "CAPTURE"; config.controlTopic -> "CONTROL"; else -> throw IllegalStateException("unexpected input topic") }
        require(metadata.partition() == lane) { "record from partition ${metadata.partition()} reached lane $lane" }
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
                    forwards += LaneByKey.key(lane, d.sequence) to FinanceWire.toProto(d, item.input).toByteArray()
                    head = d
                }
            }
        }
        head?.let {
            writes[CapacityStore.HEAD] = FinanceWire.toProto(it).toByteArray()
            writes[CapacityStore.META] = CapacityStore.Meta(laneSpec.bindingDigest, laneSpec.capacity.digest(), ledger.sequence, ledger.chain, ledger.controls).bytes()
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
        addSink("decisions-out", config.decisionsTopic, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), LaneByKey, "capacity")
    }
}

/** Decision keys are `<lane>/<sequence>`; the decision log partition is the lane, so each lane's log is read, projected and replayed on its own. */
internal object LaneByKey : org.apache.kafka.streams.processor.StreamPartitioner<String, ByteArray> {
    fun key(lane: Int, sequence: Long): String = "${lane.toString().padStart(4, '0')}/${sequence.toString().padStart(20, '0')}"
    fun laneOf(key: String): Int = key.substring(0, key.indexOf('/')).toInt()
    override fun partitions(topic: String, key: String, value: ByteArray, numPartitions: Int): java.util.Optional<Set<Int>> =
        java.util.Optional.of(setOf(laneOf(key)))
}
