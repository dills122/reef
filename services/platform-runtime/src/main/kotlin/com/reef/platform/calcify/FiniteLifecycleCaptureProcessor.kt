package com.reef.platform.calcify

import com.google.protobuf.ByteString
import org.apache.kafka.common.serialization.*
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.OrderSide

/** Pure bounded record reduction. State is immutable; any failure discards all proposed changes. */
internal object FiniteLifecycleReducer {
    data class Reduction(val state: FiniteLifecycleStateV1, val envelope: FiniteLifecycleCaptureV1?)

    fun reduce(state: FiniteLifecycleStateV1, payload: ByteArray, offset: Long, resume: Long,
               binding: FiniteLifecycleBinding): Reduction {
        validateState(state,binding)
        require(state.fault.isEmpty()) { "capture lane faulted" }
        require(offset >= binding.genesisOffset && resume > offset) { "invalid source position" }
        val rawDigest = FiniteLifecycleContract.sha(payload)
        val done = state.completedRecordsList.singleOrNull { it.source.sourceOffset == offset }
        if (done != null) {
            require(done.sourceContentDigest == rawDigest && done.resumeOffset == resume) { "changed physical source replay" }
            return Reduction(state,null)
        }
        require(offset >= state.resumeOffset) { "source order regressed/missing completed record" }
        require(state.completedRecordsCount < binding.budget.publications) { "source publication budget exceeded" }
        val batch = FiniteLifecycleContract.parse(payload,binding,offset)
        val prior = state.batchesList.singleOrNull { it.batchId == batch.source.batchId }
        require(prior == null || prior.batchChecksum == batch.source.batchChecksum) { "changed batch replay" }
        val orders = state.ordersList.associateBy { it.acceptance.fact.orderId }.toMutableMap()
        val executions = state.executionIdsList.toMutableSet()
        val members = ArrayList<LifecycleMemberV1>()
        var flat = 0
        if (prior != null) {
            for (original in prior.firstCapture.membersList) {
                members += replayMember(original,batch.source)
            }
            flat = prior.firstCapture.tradeCount
        } else batch.outcomes.forEachIndexed { ordinal, outcome ->
            val cmd = outcome.command
            val source = batch.source.toBuilder().setOutcomeOrdinal(ordinal).setCommandId(cmd.commandId).build()
            val commandId = memberId(source,LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND,0,0)
            val row = orders[cmd.orderId]
            if (row != null) require(row.acceptance.fact.participantId == cmd.participantId && row.acceptance.fact.accountId == cmd.accountId) { "command order ownership mismatch" }
            val member = LifecycleMemberV1.newBuilder().setId(commandId).setCommand(cmd)
                .setCommandPayloadHash(outcome.payloadHash).setOutcomeStatus(outcome.status)
            if (outcome.rejected != null) {
                member.setDisposition(LifecycleDispositionV1.LIFECYCLE_REJECTED).setRejected(outcome.rejected)
                row?.let { member.addDependencies(dependency(it)) }
            } else {
                member.setDisposition(LifecycleDispositionV1.LIFECYCLE_APPLIED).setAccepted(outcome.accepted!!)
                row?.let { member.addDependencies(dependency(it)) }
                val next = appliedOrder(cmd,commandId,outcome.accepted,outcome.acceptance ?: row?.acceptance,
                    row,orders.size,binding)
                orders[cmd.orderId] = next
                member.setResultingRevision(next.revision).setImmutableAcceptance(next.acceptance)
            }
            members += member.build()
            outcome.trades.forEachIndexed { within, trade ->
                val fact = trade.fact
                val buy = orders[fact.buyOrderId]; val sell = orders[fact.sellOrderId]
                val pair = outcome.executions.subList(within*2,within*2+2)
                val quantity = validateCurrentTrade(cmd,source,trade,pair,buy,sell,executions,flat,binding)
                requireNotNull(buy); requireNotNull(sell)
                val id = memberId(source,LifecycleMemberKindV1.LIFECYCLE_MEMBER_TRADE,within,flat++)
                members += LifecycleMemberV1.newBuilder().setId(id).setDisposition(LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION)
                    .setCommand(cmd).setCommandPayloadHash(outcome.payloadHash).setOutcomeStatus(outcome.status).setTrade(trade)
                    .addAllExecutions(outcome.executions.subList(within*2,within*2+2))
                    .addDependencies(dependency(buy)).addDependencies(dependency(sell)).build()
                fillTrade(orders,buy,sell,id,quantity)
            }
        }
        require(members.size <= binding.budget.outcomes*(1+binding.budget.trades)) { "member window budget exceeded" }
        val envelopeBuilder = FiniteLifecycleCaptureV1.newBuilder().setSchema(FiniteLifecycleContract.CAPTURE_SCHEMA)
            .setFiniteBindingDigest(binding.digest).setSource(batch.source).addAllMembers(members)
            .setOutcomeCount(batch.outcomes.size).setTradeCount(flat).setMemberCount(members.size)
            .setSourceEncodedBytes(batch.bytes.toLong()).setSourceContentDigest(batch.digest).setCompletedFrontier(batch.source)
            .setResumeOffset(resume).setPrefixClosed(true)
        val envelope = envelopeBuilder.setContentDigest(FiniteLifecycleContract.digest(envelopeBuilder.build())).build()
        require(envelope.serializedSize <= binding.budget.captureBytes) { "capture envelope byte budget exceeded" }
        val builder = state.toBuilder().clearOrders().addAllOrders(orders.toSortedMap().values)
            .clearExecutionIds().addAllExecutionIds(executions.sorted()).addCompletedRecords(envelope)
            .setCompletedFrontier(batch.source).setResumeOffset(resume)
            .setSourceBytes(Math.addExact(state.sourceBytes,batch.bytes.toLong()))
            .setCaptureBytes(Math.addExact(state.captureBytes,envelope.serializedSize.toLong()))
        if (prior == null) builder.addBatches(FiniteLifecycleBatchStateV1.newBuilder().setBatchId(batch.source.batchId)
            .setBatchChecksum(batch.source.batchChecksum).setFirstCapture(envelope))
        val next = builder.build(); validateState(next,binding)
        return Reduction(next,envelope)
    }

    fun validateState(state: FiniteLifecycleStateV1, binding: FiniteLifecycleBinding) {
        require(state.stateVersion == FiniteLifecycleContract.STATE_VERSION && state.finiteBindingDigest == binding.digest &&
            state.sourceProfileHash == binding.profileHash && state.bindingIdentity == ByteString.copyFrom(binding.identity())) { "state binding/version mismatch" }
        require(state.ordersCount <= binding.budget.orderIds && state.batchesCount <= binding.budget.publications &&
            state.completedRecordsCount <= binding.budget.publications && state.executionIdsCount <= binding.budget.publications*binding.budget.trades &&
            state.retainedSuffixCount + state.completedRecordsCount <= binding.budget.publications) { "managed state count budget exceeded" }
        require(state.sourceBytes in 0..binding.budget.sourceTotalBytes && state.captureBytes in 0..binding.budget.captureTotalBytes &&
            state.retainedSuffixList.sumOf { it.payload.size().toLong() } <= binding.budget.sourceTotalBytes &&
            state.serializedSize <= binding.budget.stateBytes) { "managed state byte budget exceeded" }
        require(state.ordersList.map { it.acceptance.fact.orderId }.distinct().size == state.ordersCount &&
            state.batchesList.map { it.batchId }.distinct().size == state.batchesCount &&
            state.executionIdsList.distinct().size == state.executionIdsCount &&
            state.completedRecordsList.map { it.source.sourceOffset }.distinct().size == state.completedRecordsCount) { "duplicate checkpoint identities" }
        require(state.resumeOffset >= binding.genesisOffset) { "checkpoint before genesis" }
        require(state.retainedSuffixCount == 0 || state.fault.isNotEmpty()) { "pending fault suffix lacks lane barrier" }
        require(state.retainedSuffixList.map { it.offset }.distinct().size == state.retainedSuffixCount &&
            state.retainedSuffixList.zipWithNext().all { (a,b) -> a.offset < b.offset } &&
            state.retainedSuffixList.all {
                (it.offset >= state.resumeOffset || state.completedRecordsList.any { done -> done.source.sourceOffset == it.offset }) &&
                    it.offset >= binding.genesisOffset && it.resumeOffset > it.offset && it.payload.size() <= binding.budget.sourceBytes
            }) { "fault suffix positions/bytes invalid" }
        state.completedRecordsList.forEach { record ->
            require(record.schema == FiniteLifecycleContract.CAPTURE_SCHEMA && record.contentDigest == FiniteLifecycleContract.digest(record) && record.finiteBindingDigest == binding.digest &&
                record.prefixClosed && record.memberCount == record.membersCount && record.memberCount == record.outcomeCount+record.tradeCount &&
                record.serializedSize <= binding.budget.captureBytes) { "invalid completed capture checkpoint" }
        }
        if (state.completedRecordsCount == 0) require(!state.hasCompletedFrontier() && state.resumeOffset == binding.genesisOffset) { "missing completed prefix" }
        else require(state.completedFrontier == state.completedRecordsList.last().source && state.resumeOffset == state.completedRecordsList.last().resumeOffset) { "incomplete prefix frontier" }
        validateCertifiedHistory(state,binding)
    }

    /** Bounded receipts must reconstruct every retained dependency, never certify a partial checkpoint. */
    private fun validateCertifiedHistory(state: FiniteLifecycleStateV1, binding: FiniteLifecycleBinding) {
        val orders = sortedMapOf<String,FiniteLifecycleOrderStateV1>()
        val batches = linkedMapOf<String,FiniteLifecycleCaptureV1>()
        val executions = sortedSetOf<String>()
        var previousResume = binding.genesisOffset
        for(record in state.completedRecordsList) {
            val source = record.source
            require(source.sourceGeneration == binding.generation && source.sourcePartition == binding.partition &&
                source.sourceTopic == binding.sourceTopic && source.sourceTopicId == binding.sourceTopicId &&
                source.sourceOffset >= previousResume && source.outcomeOrdinal == 0 && source.commandId.isEmpty() &&
                record.resumeOffset > source.sourceOffset && record.completedFrontier == source) { "certified source cut/lane mismatch" }
            previousResume = record.resumeOffset
            require(record.outcomeCount in 0..binding.budget.outcomes && record.tradeCount in 0..binding.budget.trades &&
                record.sourceEncodedBytes in 1..binding.budget.sourceBytes.toLong()) { "certified source count/byte cap exceeded" }
            FiniteLifecycleContract.hex(source.batchChecksum); FiniteLifecycleContract.hex(record.sourceContentDigest)
            val original = batches[source.batchId]
            if(original != null) {
                require(source.batchChecksum == original.source.batchChecksum && record.membersCount == original.membersCount &&
                    record.outcomeCount == original.outcomeCount && record.tradeCount == original.tradeCount) { "certified replay contradiction" }
                for((index,member) in record.membersList.withIndex()) {
                    val first = original.getMembers(index)
                    require(member == replayMember(first,source)) { "certified replay member contradiction" }
                }
                continue
            }
            batches[source.batchId] = record
            var commands = 0; var trades = 0
            var parent: LifecycleMemberV1? = null
            for(member in record.membersList) {
                val id = member.id; val cmd = member.command
                require(id.source.toBuilder().clearCommandId().clearOutcomeOrdinal().build() == source &&
                    id.source.commandId == cmd.commandId && id.source.outcomeOrdinal in 0 until record.outcomeCount &&
                    cmd.schema == FiniteLifecycleContract.COMMAND_SCHEMA && cmd.finiteBindingDigest == binding.digest &&
                    cmd.sourceProfileHash == binding.profileHash && cmd.runId == binding.run && cmd.venueSessionId == binding.session &&
                    cmd.instrumentId == binding.instrument) { "certified member binding/provenance mismatch" }
                FiniteLifecycleContract.validateCommand(cmd,binding)
                FiniteLifecycleContract.hex(member.commandPayloadHash)
                require(!member.hasReplayOf()) { "unexpected first-capture replay link" }
                if(id.kind == LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND) {
                    require(id.source.outcomeOrdinal == commands++ && id.withinOutcomeTradeOrdinal == 0 && id.flattenedTradeOrdinal == 0) { "certified command order mismatch" }
                    parent = member
                    require(!member.hasTrade() && member.executionsCount == 0) { "command has trade/execution facts" }
                    FiniteLifecycleContract.validateResult(cmd,member.outcomeStatus,
                        member.accepted.takeIf { member.hasAccepted() },member.rejected.takeIf { member.hasRejected() })
                    val row = orders[cmd.orderId]
                    if(row != null) require(row.acceptance.fact.participantId == cmd.participantId && row.acceptance.fact.accountId == cmd.accountId) { "certified ownership mismatch" }
                    require(member.dependenciesList == if(row == null) emptyList() else listOf(dependency(row))) { "certified command dependency missing/changed" }
                    if(member.disposition == LifecycleDispositionV1.LIFECYCLE_REJECTED) {
                        require(member.outcomeStatus == "rejected" && member.hasRejected() && !member.hasAccepted() &&
                            !member.hasResultingRevision() && !member.hasImmutableAcceptance()) { "certified rejection mutation" }
                        continue
                    }
                    require(member.disposition == LifecycleDispositionV1.LIFECYCLE_APPLIED && member.outcomeStatus == "accepted" &&
                        member.hasAccepted() && !member.hasRejected() && member.hasImmutableAcceptance()) { "certified command disposition mismatch" }
                    val revision = revision(id,if(row == null) 0 else row.revision.revision+1)
                    require(member.resultingRevision == revision) { "certified revision missing/changed" }
                    val next = appliedOrder(cmd,id,member.accepted,member.immutableAcceptance,row,orders.size,binding)
                    orders[cmd.orderId] = next
                } else {
                    require(id.kind == LifecycleMemberKindV1.LIFECYCLE_MEMBER_TRADE && member.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION &&
                        id.flattenedTradeOrdinal == trades && id.withinOutcomeTradeOrdinal == trades++ && member.hasTrade()) { "certified trade order/disposition mismatch" }
                    require(parent != null && parent.disposition == LifecycleDispositionV1.LIFECYCLE_APPLIED &&
                        member.command == parent.command && member.id.source == parent.id.source &&
                        member.commandPayloadHash == parent.commandPayloadHash && member.outcomeStatus == parent.outcomeStatus &&
                        !member.hasAccepted() && !member.hasRejected() && !member.hasResultingRevision() && !member.hasImmutableAcceptance()) { "certified trade outcome relationship mismatch" }
                    val trade = member.trade; val buy = orders[trade.fact.buyOrderId]; val sell = orders[trade.fact.sellOrderId]
                    require(buy != null && sell != null && member.dependenciesList == listOf(dependency(buy),dependency(sell))) { "certified trade dependencies missing/changed" }
                    val quantity = validateCurrentTrade(cmd,id.source,trade,member.executionsList,buy,sell,executions,id.flattenedTradeOrdinal,binding)
                    fillTrade(orders,buy,sell,id,quantity)
                }
            }
            require(commands == record.outcomeCount && trades == record.tradeCount) { "certified member omitted" }
        }
        require(state.ordersList == orders.values.toList() && state.executionIdsList == executions.toList()) { "required order/effect/execution history missing/changed" }
        require(state.batchesList == batches.values.map { FiniteLifecycleBatchStateV1.newBuilder().setBatchId(it.source.batchId)
            .setBatchChecksum(it.source.batchChecksum).setFirstCapture(it).build() }) { "required batch history missing/changed" }
        require(state.sourceBytes == state.completedRecordsList.sumOf { it.sourceEncodedBytes } &&
            state.captureBytes == state.completedRecordsList.sumOf { it.serializedSize.toLong() }) { "checkpoint byte accounting missing/changed" }
    }
    private fun amount(value: String, cap: Long) = FiniteLifecycleContract.amount(value,cap)

    /** New physical identity is exact; nested original facts and dependency/effect identities stay original. */
    private fun replayMember(first: LifecycleMemberV1, source: SourceProvenanceV1): LifecycleMemberV1 {
        val replaySource = source.toBuilder().setOutcomeOrdinal(first.id.source.outcomeOrdinal)
            .setCommandId(first.id.source.commandId).build()
        return first.toBuilder().setId(first.id.toBuilder().setSource(replaySource))
            .setDisposition(LifecycleDispositionV1.LIFECYCLE_REPLAY).setReplayOf(first.id).build()
    }

    /** Fresh reduction and receipt reconstruction share accepted command transition semantics. */
    private fun appliedOrder(cmd: OrderLifecycleCommandV1, id: LifecycleMemberIdV1,
                             accepted: reef.contracts.orderexecution.v1.OrderAccepted,
                             acceptance: AcceptedOrderSourceV1?, row: FiniteLifecycleOrderStateV1?,
                             orderCount: Int, binding: FiniteLifecycleBinding): FiniteLifecycleOrderStateV1 {
        FiniteLifecycleContract.validateResult(cmd,"accepted",accepted,null)
        requireNotNull(acceptance) { "missing immutable acceptance" }
        val revision = revision(id,if(row == null) 0 else row.revision.revision+1)
        return when(cmd.kind) {
            LifecycleCommandKindV1.LIFECYCLE_COMMAND_SUBMIT -> {
                require(row == null && orderCount < binding.budget.orderIds) { "duplicate/over-budget acceptance" }
                FiniteLifecycleContract.validateAcceptance(cmd,id.source,acceptance,accepted)
                Math.multiplyExact(amount(cmd.submit.quantityUnits,binding.budget.quantity),amount(cmd.submit.limitPrice,binding.budget.price))
                FiniteLifecycleOrderStateV1.newBuilder().setAcceptance(acceptance).setRevision(revision).setPreviousEffect(id)
                    .setQuantityUnits(cmd.submit.quantityUnits).setLimitPrice(cmd.submit.limitPrice).setFilledUnits("0").build()
            }
            LifecycleCommandKindV1.LIFECYCLE_COMMAND_MODIFY -> {
                require(row != null && row.terminal == LifecycleOrderTerminalV1.LIFECYCLE_ORDER_OPEN && acceptance == row.acceptance) { "modify missing/terminal/changed order" }
                require(accepted.engineOrderId == row.acceptance.fact.engineOrderId) { "modify engine identity mismatch" }
                val quantity = amount(cmd.modify.quantityUnits,binding.budget.quantity)
                Math.multiplyExact(quantity,amount(cmd.modify.limitPrice,binding.budget.price))
                require(quantity > row.filledUnits.toLong()) { "modify quantity not above filled" }
                row.toBuilder().setRevision(revision).setPreviousEffect(id).setQuantityUnits(cmd.modify.quantityUnits).setLimitPrice(cmd.modify.limitPrice).build()
            }
            LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL -> {
                require(row != null && row.terminal == LifecycleOrderTerminalV1.LIFECYCLE_ORDER_OPEN && acceptance == row.acceptance) { "cancel missing/terminal/changed order" }
                require(accepted.engineOrderId == row.acceptance.fact.engineOrderId) { "cancel engine identity mismatch" }
                row.toBuilder().setRevision(revision).setPreviousEffect(id).setTerminal(LifecycleOrderTerminalV1.LIFECYCLE_ORDER_CANCELLED).build()
            }
            else -> throw IllegalArgumentException("unsupported accepted command")
        }
    }
    private fun validateCurrentTrade(cmd: OrderLifecycleCommandV1, source: SourceProvenanceV1, trade: TradeSourceV1,
                                     pair: List<reef.contracts.orderexecution.v1.ExecutionCreated>, buy: FiniteLifecycleOrderStateV1?,
                                     sell: FiniteLifecycleOrderStateV1?, executions: MutableSet<String>, flat: Int,
                                     binding: FiniteLifecycleBinding): Long {
        FiniteLifecycleContract.validateTrade(cmd,source,trade,pair,binding)
        require(buy != null && sell != null && buy.terminal == LifecycleOrderTerminalV1.LIFECYCLE_ORDER_OPEN &&
            sell.terminal == LifecycleOrderTerminalV1.LIFECYCLE_ORDER_OPEN) { "trade missing/terminal dependency" }
        MatchContextResolver.resolve(CommitmentVerificationPassed(CommitmentId(binding.generation,binding.partition,source.sourceOffset,flat),1),
            trade,buy.acceptance,sell.acceptance)
        require(buy.acceptance.fact.participantId != sell.acceptance.fact.participantId) { "unsupported self-trade" }
        val price = amount(trade.fact.price.nanos,binding.budget.price)
        require(price <= buy.limitPrice.toLong() && price >= sell.limitPrice.toLong()) { "trade outside current limits" }
        require(executions.add(trade.fact.executionId)) { "execution reused outside identical batch replay" }
        return amount(trade.fact.quantity.units,binding.budget.quantity)
    }
    private fun fillTrade(orders: MutableMap<String,FiniteLifecycleOrderStateV1>, buy: FiniteLifecycleOrderStateV1,
                          sell: FiniteLifecycleOrderStateV1, id: LifecycleMemberIdV1, quantity: Long) {
        for(order in listOf(buy,sell)) {
            val filled = Math.addExact(order.filledUnits.toLong(),quantity)
            require(filled <= order.quantityUnits.toLong()) { "trade quantity conservation failure" }
            orders[order.acceptance.fact.orderId] = order.toBuilder().setFilledUnits(filled.toString()).setPreviousEffect(id)
                .setTerminal(if(filled == order.quantityUnits.toLong()) LifecycleOrderTerminalV1.LIFECYCLE_ORDER_FILLED else LifecycleOrderTerminalV1.LIFECYCLE_ORDER_OPEN).build()
        }
    }
    private fun revision(id: LifecycleMemberIdV1, number: Int) = LifecycleRevisionIdV1.newBuilder().setCommand(id).setRevision(number).build()
    private fun memberId(source: SourceProvenanceV1, kind: LifecycleMemberKindV1, within: Int, flat: Int) = LifecycleMemberIdV1.newBuilder()
        .setSource(source).setKind(kind).setWithinOutcomeTradeOrdinal(within).setFlattenedTradeOrdinal(flat).build()
    private fun dependency(state: FiniteLifecycleOrderStateV1) = LifecycleDependencyV1.newBuilder()
        .setOrderId(state.acceptance.fact.orderId).setAcceptance(state.acceptance).setRevision(state.revision)
        .setPreviousEffect(state.previousEffect).setQuantityUnits(state.quantityUnits).setLimitPrice(state.limitPrice).setFilledUnits(state.filledUnits).build()
}

/** Model topology only; no production entrypoint until O2 registration/isolation/history verification. */
internal class FiniteLifecycleCaptureProcessor(
    private val binding: FiniteLifecycleBinding, private val cut: FiniteLifecycleStartCut,
    private val blocked: (Boolean)->Unit,
) : Processor<ByteArray,ByteArray,ByteArray,ByteArray> {
    private lateinit var context: ProcessorContext<ByteArray,ByteArray>
    private lateinit var store: KeyValueStore<String,ByteArray>
    override fun init(context: ProcessorContext<ByteArray,ByteArray>) {
        this.context = context; store = context.getStateStore(STORE)
        require(context.taskId().partition() == binding.partition) { "capture task/lane mismatch" }
        val existing = store.get("state")?.let(FiniteLifecycleStateV1::parseFrom)
        require(existing != null || store.all().use { !it.hasNext() }) { "managed state missing; refuse inferred genesis" }
        val state = FiniteLifecycleCaptureRuntime.initializeModel(binding,cut,existing)
        store.put("state",state.toByteArray()); blocked(state.fault.isNotEmpty())
    }
    override fun process(record: Record<ByteArray,ByteArray>) {
        // Always re-read managed state: rollback/restore cannot leave an ahead-of-store cache.
        val state = FiniteLifecycleStateV1.parseFrom(store.get("state"))
        val metadata = context.recordMetadata().orElseThrow()
        require(metadata.topic() == binding.sourceTopic && metadata.partition() == binding.partition) { "source input lane mismatch" }
        val payload = record.value() ?: byteArrayOf()
        val offset = metadata.offset(); val resume = Math.addExact(offset,1)
        if (state.fault.isNotEmpty()) { retain(state,payload,offset,resume,state.fault); return }
        val result = try { FiniteLifecycleReducer.reduce(state,payload,offset,resume,binding) }
        catch (ex: IllegalArgumentException) { retain(state,payload,offset,resume,ex.message ?: "source integrity fault"); return }
        // Forward/store failures propagate to Streams and abort transaction; never convert to committed lane fault.
        result.envelope?.let { context.forward(Record(null,it.toByteArray(),record.timestamp())) }
        store.put("state",result.state.toByteArray())
    }
    private fun retain(state: FiniteLifecycleStateV1, payload: ByteArray, offset: Long, resume: Long, reason: String) {
        check(payload.size <= binding.budget.sourceBytes) { "oversized fault input; abort and retain broker history" }
        val prior = state.retainedSuffixList.singleOrNull { it.offset == offset }
        check(prior == null || prior.payload == ByteString.copyFrom(payload)) { "changed retained fault input; abort" }
        val next = state.toBuilder().setFault(reason.take(2048)).apply {
            if (prior == null) addRetainedSuffix(FiniteLifecycleSuffixV1.newBuilder().setOffset(offset)
                .setPayload(ByteString.copyFrom(payload)).setResumeOffset(resume))
        }.build()
        // Budget overflow aborts rather than checkpointing input past unretained suffix.
        FiniteLifecycleReducer.validateState(next,binding)
        store.put("state",next.toByteArray()); blocked(true)
    }
    override fun close() = Unit
    companion object {
        const val STORE = "finite-lifecycle"
        fun modelTopology(binding: FiniteLifecycleBinding, cut: FiniteLifecycleStartCut, blocked: (Boolean)->Unit = {}): Topology = Topology().apply {
            addSource("finite-source",ByteArrayDeserializer(),ByteArrayDeserializer(),binding.sourceTopic)
            addProcessor("capture",{ FiniteLifecycleCaptureProcessor(binding,cut,blocked) },"finite-source")
            addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(STORE),Serdes.String(),Serdes.ByteArray()).withCachingDisabled(),"capture")
            addSink("finite-capture",binding.captureTopic,ByteArraySerializer(),ByteArraySerializer(),
                org.apache.kafka.streams.processor.StreamPartitioner<ByteArray,ByteArray> { _,_,_,_->java.util.Optional.of(setOf(binding.partition)) },"capture")
        }
    }
}
