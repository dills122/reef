package com.reef.platform.calcify

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import reef.contracts.calcify.v1.*

/** Read-only view of the keyed lifecycle state. Keys: `meta`, `order/<id>`, `exec/<id>`, `batch/<id>`, `done/<20-digit offset>`. */
internal interface LifecycleKv {
    fun get(key: String): ByteArray?
    /** All entries whose key starts with [prefix], ascending by key. Used only by the once-per-restore verification, never per record. */
    fun scan(prefix: String): List<Pair<String, ByteArray>>
    /** Like [scan] but the entries are handed to [use] one at a time and valid only inside it, so a large range is never materialized (restore of a large store). */
    fun <R> scanStreaming(prefix: String, use: (Sequence<Pair<String, ByteArray>>) -> R): R = use(scan(prefix).asSequence())
}

internal class MemoryLifecycleKv : LifecycleKv {
    val entries = sortedMapOf<String, ByteArray>()
    override fun get(key: String): ByteArray? = entries[key]
    override fun scan(prefix: String): List<Pair<String, ByteArray>> = entries.tailMap(prefix).entries.takeWhile { it.key.startsWith(prefix) }.map { it.key to it.value }
    fun apply(writes: Map<String, ByteArray>) { entries.putAll(writes) }
}

/**
 * S1-D2: the frozen [FiniteLifecycleReducer] with its single whole-state value replaced by per-entity keys, so the work per record
 * is proportional to the entities the record touches instead of the whole history (G9 lesson: no whole-state value).
 * Transition semantics are NOT re-implemented: command application, trade validation, fills and replay members call the same
 * helpers as the frozen reducer. What differs is storage and cross-record validation:
 *  - state is read through [LifecycleKv] and every write is returned as an atomic batch; a failure returns no writes at all;
 *  - the frozen reducer re-derives and re-checks the entire certified history on every record (`validateState`); this reducer
 *    validates only the touched entities plus running counters, so a corrupt history that the frozen reducer would catch on a later
 *    record is out of scope here (restore verification is a separate, once-per-restore pass; see the S2-B1 plan);
 *  - the serialized whole-state byte budget (`stateBytes`) has no whole state to measure; entity counts and running source/capture
 *    byte totals are enforced instead.
 * Differential tests assert byte-equal envelopes and equal entity content against the frozen reducer on valid schedules.
 */
/** Adapter over a Kafka Streams key-value store (the changelog-backed managed store) behind the same keyed view. */
internal class StreamsLifecycleKv(private val store: org.apache.kafka.streams.state.KeyValueStore<String, ByteArray>) : LifecycleKv {
    override fun get(key: String): ByteArray? = store.get(key)
    override fun scan(prefix: String): List<Pair<String, ByteArray>> =
        store.range(prefix, prefix + "\uffff").use { it.asSequence().map { kv -> kv.key to kv.value }.toList() }
    override fun <R> scanStreaming(prefix: String, use: (Sequence<Pair<String, ByteArray>>) -> R): R =
        store.range(prefix, prefix + "\uffff").use { use(it.asSequence().map { kv -> kv.key to kv.value }) }
    /** Applies one reduction's write batch; the surrounding Streams transaction makes it atomic with the output record. */
    fun apply(writes: Map<String, ByteArray>) = writes.forEach { (k, v) -> store.put(k, v) }
}

internal object IncrementalLifecycleReducer {
    data class Meta(val base: FiniteLifecycleStateV1, val orders: Int, val executions: Int, val batches: Int, val completed: Int) {
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                val b = base.toByteArray(); d.writeInt(b.size); d.write(b)
                d.writeInt(orders); d.writeInt(executions); d.writeInt(batches); d.writeInt(completed)
            }
            return out.toByteArray()
        }
        companion object {
            fun parse(bytes: ByteArray): Meta = DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val b = ByteArray(d.readInt()); d.readFully(b)
                Meta(FiniteLifecycleStateV1.parseFrom(b), d.readInt(), d.readInt(), d.readInt(), d.readInt())
            }
        }
    }

    data class Reduction(val envelope: FiniteLifecycleCaptureV1?, val writes: Map<String, ByteArray>)

    fun genesis(binding: FiniteLifecycleBinding): Map<String, ByteArray> =
        mapOf("meta" to Meta(FiniteLifecycleContract.genesis(binding), 0, 0, 0, 0).bytes())

    fun doneKey(offset: Long) = "done/%020d".format(offset)

    fun reduce(kv: LifecycleKv, payload: ByteArray, offset: Long, resume: Long, binding: FiniteLifecycleBinding): Reduction {
        val meta = Meta.parse(requireNotNull(kv.get("meta")) { "managed state missing; refuse inferred genesis" })
        val state = meta.base
        require(state.stateVersion == FiniteLifecycleContract.STATE_VERSION && state.finiteBindingDigest == binding.digest &&
            state.sourceProfileHash == binding.profileHash && state.bindingIdentity == com.google.protobuf.ByteString.copyFrom(binding.identity())) { "state binding/version mismatch" }
        require(state.fault.isEmpty()) { "capture lane faulted" }
        require(offset >= binding.genesisOffset && resume > offset) { "invalid source position" }
        val rawDigest = FiniteLifecycleContract.sha(payload)
        kv.get(doneKey(offset))?.let { bytes ->
            val done = FiniteLifecycleCaptureV1.parseFrom(bytes)
            require(done.sourceContentDigest == rawDigest && done.resumeOffset == resume) { "changed physical source replay" }
            return Reduction(null, emptyMap())
        }
        require(offset >= state.resumeOffset) { "source order regressed/missing completed record" }
        require(meta.completed < binding.budget.publications) { "source publication budget exceeded" }
        val batch = FiniteLifecycleContract.parse(payload, binding, offset)
        val prior = kv.get("batch/" + batch.source.batchId)?.let(FiniteLifecycleBatchStateV1::parseFrom)
        require(prior == null || prior.batchChecksum == batch.source.batchChecksum) { "changed batch replay" }

        val touched = linkedMapOf<String, FiniteLifecycleOrderStateV1>()
        fun order(id: String): FiniteLifecycleOrderStateV1? = touched[id] ?: kv.get("order/$id")?.let(FiniteLifecycleOrderStateV1::parseFrom)
        var newOrders = 0
        val newExecutions = linkedSetOf<String>()
        val executions = object : AbstractMutableSet<String>() {
            override val size get() = newExecutions.size
            override fun iterator() = newExecutions.iterator()
            override fun add(element: String): Boolean = if (kv.get("exec/$element") != null || element in newExecutions) false else newExecutions.add(element)
        }
        val members = ArrayList<LifecycleMemberV1>()
        var flat = 0
        if (prior != null) {
            for (original in prior.firstCapture.membersList) members += FiniteLifecycleReducer.replayMember(original, batch.source)
            flat = prior.firstCapture.tradeCount
        } else batch.outcomes.forEachIndexed { ordinal, outcome ->
            val cmd = outcome.command
            val source = batch.source.toBuilder().setOutcomeOrdinal(ordinal).setCommandId(cmd.commandId).build()
            val commandId = FiniteLifecycleReducer.memberId(source, LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND, 0, 0)
            val row = order(cmd.orderId)
            if (row != null) require(row.acceptance.fact.participantId == cmd.participantId && row.acceptance.fact.accountId == cmd.accountId) { "command order ownership mismatch" }
            val member = LifecycleMemberV1.newBuilder().setId(commandId).setCommand(cmd)
                .setCommandPayloadHash(outcome.payloadHash).setOutcomeStatus(outcome.status)
            if (outcome.rejected != null) {
                member.setDisposition(LifecycleDispositionV1.LIFECYCLE_REJECTED).setRejected(outcome.rejected)
                row?.let { member.addDependencies(FiniteLifecycleReducer.dependency(it)) }
            } else {
                member.setDisposition(LifecycleDispositionV1.LIFECYCLE_APPLIED).setAccepted(outcome.accepted!!)
                row?.let { member.addDependencies(FiniteLifecycleReducer.dependency(it)) }
                val next = FiniteLifecycleReducer.appliedOrder(cmd, commandId, outcome.accepted, outcome.acceptance ?: row?.acceptance,
                    row, meta.orders + newOrders, binding)
                if (row == null) newOrders++
                touched[cmd.orderId] = next
                member.setResultingRevision(next.revision).setImmutableAcceptance(next.acceptance)
            }
            members += member.build()
            outcome.trades.forEachIndexed { within, trade ->
                val fact = trade.fact
                val buy = order(fact.buyOrderId); val sell = order(fact.sellOrderId)
                val pair = outcome.executions.subList(within * 2, within * 2 + 2)
                val quantity = FiniteLifecycleReducer.validateCurrentTrade(cmd, source, trade, pair, buy, sell, executions, flat, binding)
                requireNotNull(buy); requireNotNull(sell)
                val id = FiniteLifecycleReducer.memberId(source, LifecycleMemberKindV1.LIFECYCLE_MEMBER_TRADE, within, flat++)
                members += LifecycleMemberV1.newBuilder().setId(id).setDisposition(LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION)
                    .setCommand(cmd).setCommandPayloadHash(outcome.payloadHash).setOutcomeStatus(outcome.status).setTrade(trade)
                    .addAllExecutions(outcome.executions.subList(within * 2, within * 2 + 2))
                    .addDependencies(FiniteLifecycleReducer.dependency(buy)).addDependencies(FiniteLifecycleReducer.dependency(sell)).build()
                FiniteLifecycleReducer.fillTrade(touched, buy, sell, id, quantity)
            }
        }
        require(members.size <= binding.budget.outcomes * (1 + binding.budget.trades)) { "member window budget exceeded" }
        val envelopeBuilder = FiniteLifecycleCaptureV1.newBuilder().setSchema(FiniteLifecycleContract.CAPTURE_SCHEMA)
            .setFiniteBindingDigest(binding.digest).setSource(batch.source).addAllMembers(members)
            .setOutcomeCount(batch.outcomes.size).setTradeCount(flat).setMemberCount(members.size)
            .setSourceEncodedBytes(batch.bytes.toLong()).setSourceContentDigest(batch.digest).setCompletedFrontier(batch.source)
            .setResumeOffset(resume).setPrefixClosed(true)
        val envelope = envelopeBuilder.setContentDigest(FiniteLifecycleContract.digest(envelopeBuilder.build())).build()
        require(envelope.serializedSize <= binding.budget.captureBytes) { "capture envelope byte budget exceeded" }

        val sourceBytes = Math.addExact(state.sourceBytes, batch.bytes.toLong())
        val captureBytes = Math.addExact(state.captureBytes, envelope.serializedSize.toLong())
        val next = Meta(state.toBuilder().setCompletedFrontier(batch.source).setResumeOffset(resume)
            .setSourceBytes(sourceBytes).setCaptureBytes(captureBytes).build(),
            meta.orders + newOrders, meta.executions + newExecutions.size, meta.batches + if (prior == null) 1 else 0, meta.completed + 1)
        require(next.orders <= binding.budget.orderIds && next.batches <= binding.budget.publications && next.completed <= binding.budget.publications &&
            next.executions <= binding.budget.publications * binding.budget.trades) { "managed state count budget exceeded" }
        require(sourceBytes <= binding.budget.sourceTotalBytes && captureBytes <= binding.budget.captureTotalBytes) { "managed state byte budget exceeded" }

        val writes = linkedMapOf<String, ByteArray>()
        touched.forEach { (id, o) -> writes["order/$id"] = o.toByteArray() }
        newExecutions.forEach { writes["exec/$it"] = byteArrayOf(1) }
        if (prior == null) writes["batch/" + batch.source.batchId] = FiniteLifecycleBatchStateV1.newBuilder().setBatchId(batch.source.batchId)
            .setBatchChecksum(batch.source.batchChecksum).setFirstCapture(envelope).build().toByteArray()
        writes[doneKey(offset)] = envelope.toByteArray()
        writes["meta"] = next.bytes()
        return Reduction(envelope, writes)
    }

    /**
     * Once-per-restore verification: assembles the frozen whole-state view from the keys and runs the frozen certified-history
     * validation over it, so a corrupt, missing or tampered keyed history is refused before the first record is reduced. This is
     * the cost the per-record path avoids; it runs at activation, not per record.
     */
    fun assemble(kv: LifecycleKv, binding: FiniteLifecycleBinding): FiniteLifecycleStateV1 {
        val meta = Meta.parse(requireNotNull(kv.get("meta")) { "managed state missing; refuse inferred genesis" })
        val completed = kv.scan("done/").map { FiniteLifecycleCaptureV1.parseFrom(it.second) }
        val batches = kv.scan("batch/").associate { FiniteLifecycleBatchStateV1.parseFrom(it.second).let { b -> b.batchId to b } }
        val ordered = LinkedHashMap<String, FiniteLifecycleBatchStateV1>()
        for (record in completed) batches[record.source.batchId]?.let { ordered.putIfAbsent(record.source.batchId, it) }
        val state = meta.base.toBuilder()
            .addAllOrders(kv.scan("order/").map { FiniteLifecycleOrderStateV1.parseFrom(it.second) })
            .addAllExecutionIds(kv.scan("exec/").map { it.first.removePrefix("exec/") })
            .addAllBatches(ordered.values).addAllCompletedRecords(completed).build()
        require(state.ordersCount == meta.orders && state.executionIdsCount == meta.executions &&
            batches.size == meta.batches && ordered.size == meta.batches && state.completedRecordsCount == meta.completed) { "keyed counts differ from meta" }
        return state
    }

    fun verifyHistory(kv: LifecycleKv, binding: FiniteLifecycleBinding): FiniteLifecycleStateV1 =
        assemble(kv, binding).also { FiniteLifecycleReducer.validateState(it, binding) }
}
