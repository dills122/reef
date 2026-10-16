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
    /**
     * When true, a CAPTURE record (or member) the decoder calls unusable stops the lane instead of being counted and skipped: it is an executed trade the books would
     * silently lack (a routing fault, a corrupt or foreign record), and the design requires such a fault to block the lane with the source retained, never to erase the
     * trade. The exception ends the run; the supervisor restarts it and it stops at the same record until an operator resolves it. Bad CONTROL records are operator
     * mistakes and are only counted. The launcher turns this on; it is off here so the processor's own tests can exercise the counting path.
     */
    val failOnBadCapture: Boolean = false,
    /**
     * When true, each lane keeps in memory only what its next decision can still change (positions, pending trades) and reads every settled trade and old action back
     * from its store through an in-heap filter, so memory grows only by the commitment trie (about 350 bytes per combined trade) and the filter (about 3 bytes per key), not by the
     * trades themselves. Decisions, chains, commitments and stored entities are byte-identical
     * either way; only restore, which then streams the entities instead of building every object, and the lane's memory differ.
     */
    val evictSettled: Boolean = false,
) {
    /** One lane, lane 0. */
    constructor(bindingDigest: String, capacity: CapacityConfig, captureTopic: String, controlTopic: String, decisionsTopic: String, applicationId: String, storeName: String = "capacity-finance", evictSettled: Boolean = false) :
        this(1, { lane -> require(lane == 0) { "single-lane configuration" }; CapacityLaneSpec(bindingDigest, capacity) }, captureTopic, controlTopic, decisionsTopic, applicationId, storeName, evictSettled = evictSettled)

    constructor(plan: CapacityPlan, captureTopic: String, controlTopic: String, decisionsTopic: String, applicationId: String, storeName: String = "capacity-finance", logHead: ((Int) -> Pair<Long, String>?)? = null, failOnBadCapture: Boolean = false, evictSettled: Boolean = false) :
        this(plan.lanes, plan::spec, captureTopic, controlTopic, decisionsTopic, applicationId, storeName, logHead, failOnBadCapture, evictSettled)

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
    private lateinit var configDigest: String
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
        configDigest = laneSpec.capacity.digest()      // fixed for the run: not recomputed (sorting and hashing the account and security sets) for every record
        ledger = CapacityStore.restore(laneSpec.capacity, kv, laneSpec.bindingDigest, config.evictSettled) ?: CapacityLedger(laneSpec.capacity, if (config.evictSettled) EntityBacking { kv.get(it) } else null)
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
        var head: ByteArray? = null
        for (item in items) {
            when (item) {
                is CapacityItem.Skip -> Unit
                is CapacityItem.Bad -> {
                    badInputs++
                    check(!(config.failOnBadCapture && role == "CAPTURE")) { "lane $lane: unusable capture record at offset ${metadata.offset()} (digest ${item.digest}); the lane stops with the source retained" }
                }
                is CapacityItem.Run -> {
                    val outcome = ledger.decide(item.input)
                    val d = outcome.decision
                    if (d == null) {
                        refusals++
                        // A refused input that came from CAPTURE is an executed trade (or its settlement) the books do not hold: unknown account, conflicting economics, exhausted budget.
                        // Only a redelivery the ledger already holds is benign. Stop the lane, as for an unusable record.
                        check(!(config.failOnBadCapture && role == "CAPTURE" && outcome.disposition !in BENIGN_REFUSALS)) {
                            "lane $lane: the ledger refused a captured input with ${outcome.disposition} at offset ${metadata.offset()}; the lane stops with the source retained"
                        }
                        continue
                    }
                    for (e in ledger.lastWrites) writes[CapacityStore.entityKey(e)] = CapacityStore.entityValue(e)
                    // The decision is encoded once: those bytes are the store's head record if this is the record's last decision, and the published record is them plus the input.
                    // `d` was made from `item.input` a moment ago, so the published record does not hash the input again to check it.
                    val bytes = FinanceWire.encode(d)
                    forwards += LaneByKey.key(lane, d.sequence) to FinanceWire.withInput(bytes, item.input)
                    head = bytes
                }
            }
        }
        head?.let {
            writes[CapacityStore.HEAD] = it
            writes[CapacityStore.META] = CapacityStore.Meta(laneSpec.bindingDigest, configDigest, ledger.sequence, ledger.chain, ledger.controls).bytes()
            kv.apply(writes)
            ledger.persisted()      // the store now holds everything the ledger decided: settled objects may leave memory
        }
        forwards.forEach { (key, value) -> context.forward(record.withKey(key).withValue(value)) }
    }

    /** The ledger the processor is running on (tests and operators). */
    fun currentLedger(): CapacityLedger = ledger
}

/** Refusals that only mean "the ledger already holds this": a redelivered capture or settlement. Every other refusal of a captured input is a trade missing from the books. */
private val BENIGN_REFUSALS = setOf(Disposition.PRIOR_RESULT, Disposition.EXECUTION_ALREADY_CAPTURED, Disposition.ALREADY_SETTLED, Disposition.PRIOR_ATTEMPT)

internal object CapacityTopology {
    fun storeBuilder(config: CapacityProcessorConfig, persistent: Boolean): StoreBuilder<KeyValueStore<String, ByteArray>> =
        Stores.keyValueStoreBuilder(
            if (persistent) Stores.persistentKeyValueStore(config.storeName) else Stores.inMemoryKeyValueStore(config.storeName),
            Serdes.String(), Serdes.ByteArray()).withCachingDisabled()

    fun build(config: CapacityProcessorConfig, decoder: CapacityInputDecoder, persistent: Boolean = true): Topology = Topology().apply {
        // Streams merges the two inputs of a task by record timestamp. Controls (funding, repairs) must win whenever both are buffered, whatever the capture timestamps are
        // (the resolver writes its records with timestamp 0): otherwise a restart or a backlog could present a trade before the funding that makes its accounts known, and
        // a lane stopped on an unfunded account could never be unstuck by funding it. Controls are stamped 0 and captures at least 1.
        addSource(org.apache.kafka.streams.AutoOffsetReset.earliest(), "capture-in", org.apache.kafka.streams.processor.TimestampExtractor { record, _ -> maxOf(1L, record.timestamp()) }, ByteArrayDeserializer(), ByteArrayDeserializer(), config.captureTopic)
        addSource(org.apache.kafka.streams.AutoOffsetReset.earliest(), "control-in", org.apache.kafka.streams.processor.TimestampExtractor { _, _ -> 0L }, ByteArrayDeserializer(), ByteArrayDeserializer(), config.controlTopic)
        addProcessor("capacity", { CapacityProcessor(config, decoder) }, "capture-in", "control-in")
        addStateStore(storeBuilder(config, persistent), "capacity")
        addSink("decisions-out", config.decisionsTopic, org.apache.kafka.common.serialization.StringSerializer(), ByteArraySerializer(), LaneByKey, "capacity")
    }
}

/** Decision keys are `<lane>/<sequence>`; the decision log partition is the lane, so each lane's log is read, projected and replayed on its own. */
internal object LaneByKey : org.apache.kafka.streams.processor.StreamPartitioner<String, ByteArray> {
    /** `%04d/%020d` of lane and sequence (zero padded to at least that width; longer numbers are not truncated), built in one pass. */
    fun key(lane: Int, sequence: Long): String {
        if (lane < 0 || sequence < 0) return "${lane.toString().padStart(4, '0')}/${sequence.toString().padStart(20, '0')}"
        val laneWidth = maxOf(4, digits(lane.toLong())); val sequenceWidth = maxOf(20, digits(sequence))
        val out = ByteArray(laneWidth + 1 + sequenceWidth); java.util.Arrays.fill(out, '0'.code.toByte())
        var n = lane; var at = laneWidth - 1
        while (n > 0) { out[at--] = ('0'.code + n % 10).toByte(); n /= 10 }
        out[laneWidth] = '/'.code.toByte()
        var s = sequence; at = out.size - 1
        while (s > 0) { out[at--] = ('0'.code + (s % 10).toInt()).toByte(); s /= 10 }
        return String(out, java.nio.charset.StandardCharsets.ISO_8859_1)
    }
    private fun digits(v: Long): Int { var n = 1; var x = v; while (x >= 10) { x /= 10; n++ }; return n }
    fun laneOf(key: String): Int = key.substring(0, key.indexOf('/')).toInt()
    override fun partitions(topic: String, key: String, value: ByteArray, numPartitions: Int): java.util.Optional<Set<Int>> =
        java.util.Optional.of(setOf(laneOf(key)))
}
