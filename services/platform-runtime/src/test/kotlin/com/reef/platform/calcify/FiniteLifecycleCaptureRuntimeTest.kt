package com.reef.platform.calcify

import kotlin.test.*
import org.apache.kafka.streams.StreamsConfig
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.*

class FiniteLifecycleCaptureRuntimeTest {
    private val binding = FiniteLifecycleFixtures.binding()
    private fun changedReceipt(original: FiniteLifecycleStateV1, index: Int,
                               edit: (FiniteLifecycleCaptureV1.Builder)->Unit): FiniteLifecycleStateV1 {
        val raw = original.getCompletedRecords(index).toBuilder().apply(edit).build()
        val changed = raw.toBuilder().setContentDigest(FiniteLifecycleContract.digest(raw)).build()
        val state = original.toBuilder().setCompletedRecords(index,changed)
        for(n in 0 until state.batchesCount) if(state.getBatches(n).batchId == changed.source.batchId)
            state.setBatches(n,state.getBatches(n).toBuilder().setFirstCapture(changed))
        return state.setCaptureBytes(state.completedRecordsList.sumOf { it.serializedSize.toLong() }).build()
    }
    private fun refusesChangedReceipt(original: FiniteLifecycleStateV1, index: Int, label: String,
                                      edit: (FiniteLifecycleCaptureV1.Builder)->Unit) {
        val before = original.toByteArray(); val changed = changedReceipt(original,index,edit)
        assertFailsWith<IllegalArgumentException>(label) {
            FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(changed,0,12))
        }
        assertFailsWith<IllegalArgumentException>(label) {
            FiniteLifecycleReducer.reduce(changed,FiniteLifecycleFixtures.bodies.first(),12,13,binding)
        }
        assertContentEquals(before,original.toByteArray())
    }
    private fun changeTrade(record: FiniteLifecycleCaptureV1.Builder, edit: (TradeSourceV1.Builder)->Unit) {
        val member = record.getMembers(1).toBuilder()
        val trade = member.trade.toBuilder().apply(edit).build()
        member.setTrade(trade)
        // Keep execution economics consistent so trade semantic gates, not pair mismatch, reject.
        for(n in 0 until member.executionsCount) member.setExecutions(n,member.getExecutions(n).toBuilder()
            .setInstrumentId(trade.fact.instrumentId).setQuantity(trade.fact.quantity).setExecutionPrice(trade.fact.price)
            .setOccurredAt(trade.fact.occurredAt))
        record.setMembers(1,member)
    }
    private fun replayCheckpoint() = FiniteLifecycleReducer.reduce(FiniteLifecycleFixtures.sequence(),
        FiniteLifecycleFixtures.bodies[2],12,13,binding).state
    private fun refusesChangedReplay(original: FiniteLifecycleStateV1, label: String,
                                     edit: (FiniteLifecycleCaptureV1.Builder)->Unit) {
        val before = original.toByteArray(); val index = original.completedRecordsCount-1
        val raw = original.getCompletedRecords(index).toBuilder().apply(edit).build()
        val changed = raw.toBuilder().setContentDigest(FiniteLifecycleContract.digest(raw)).build()
        // Replay origin certificate stays original; all changed envelope accounting stays consistent.
        val state = original.toBuilder().setCompletedRecords(index,changed).setCompletedFrontier(changed.source)
            .setResumeOffset(changed.resumeOffset).setCaptureBytes(original.captureBytes-original.getCompletedRecords(index).serializedSize+changed.serializedSize).build()
        assertFailsWith<IllegalArgumentException>(label) {
            FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(state,0,1001))
        }
        assertFailsWith<IllegalArgumentException>(label) {
            FiniteLifecycleReducer.reduce(state,FiniteLifecycleFixtures.bodies[5],13,14,binding)
        }
        assertContentEquals(before,original.toByteArray())
    }
    @Test fun explicitGenesisNeverInfersEarliestOrResetsManagedHistory() {
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Genesis(1,1,10)) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Genesis(0,1,10)) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Genesis(0,0,10),FiniteLifecycleFixtures.sequence()) }
        assertEquals(0L,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Genesis(0,0,0)).resumeOffset)
    }
    @Test fun exactRestorePreservesFrontierEconomicsAndRefusesBindingHistoryChanges() {
        val checkpoint = FiniteLifecycleFixtures.sequence()
        val cut = FiniteLifecycleStartCut.Restore(checkpoint,0,12)
        assertEquals(checkpoint,FiniteLifecycleCaptureRuntime.initializeModel(binding,cut))
        val changes = listOf(binding.copy(digest="0".repeat(64)),binding.copy(profileHash="0".repeat(64)),binding.copy(sourceTopicId="new"),
            binding.copy(captureTopicId="new"),binding.copy(commandTopicId="new"),binding.copy(applicationId="new"),
            binding.copy(generation=2),binding.copy(budget=binding.budget.copy(publications=15)))
        for(changed in changes) assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(changed,cut) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,cut.copy(availableBeginning=1)) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,cut.copy(availableEnd=11)) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,cut.copy(checkpoint=checkpoint.toBuilder().clearCompletedFrontier().build())) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,cut.copy(checkpoint=checkpoint.toBuilder().clearStateVersion().build())) }
    }
    @Test fun replayBatchVariationHasSameFrozenPrefixAndLiveActivationRefuses() {
        val cut = 6; var state = FiniteLifecycleContract.genesis(binding)
        FiniteLifecycleFixtures.bodies.take(cut).forEachIndexed { i, bytes -> state = FiniteLifecycleReducer.reduce(state,bytes,i.toLong(),i+1L,binding).state }
        state = FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(state,0,12))
        FiniteLifecycleFixtures.bodies.drop(cut).forEachIndexed { i, bytes -> val offset = (i+cut).toLong(); state = FiniteLifecycleReducer.reduce(state,bytes,offset,offset+1,binding).state }
        assertEquals(FiniteLifecycleFixtures.sequence(),state)
        assertFailsWith<IllegalStateException> { FiniteLifecycleCaptureRuntime.run() }
        val config = FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")
        assertEquals(StreamsConfig.EXACTLY_ONCE_V2,config[StreamsConfig.PROCESSING_GUARANTEE_CONFIG])
        assertEquals("none",config[StreamsConfig.consumerPrefix("auto.offset.reset")])
        assertEquals("none",config[StreamsConfig.producerPrefix("compression.type")])
    }
    @Test fun restoreRefusesMissingMemberAndRetainsExactFaultBarrierAndSuffix() {
        val state = FiniteLifecycleFixtures.sequence()
        val record = state.getCompletedRecords(2)
        val broken = record.toBuilder().removeMembers(2).build()
        val missing = broken.toBuilder().setContentDigest(FiniteLifecycleContract.digest(broken)).build()
        assertFailsWith<IllegalArgumentException> {
            FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(state.toBuilder().setCompletedRecords(2,missing).build(),0,12))
        }
        val fault = state.toBuilder().setFault("test barrier").addRetainedSuffix(reef.contracts.calcify.v1.FiniteLifecycleSuffixV1.newBuilder()
            .setOffset(12).setResumeOffset(13).setPayload(com.google.protobuf.ByteString.copyFrom(FiniteLifecycleFixtures.bodies.first()))).build()
        assertEquals(fault,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(fault,0,13)))
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(fault,0,12)) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(fault,FiniteLifecycleFixtures.bodies.first(),12,13,binding) }
    }
    @Test fun restoredAcceptedAndRejectedResultsMustMatchCommandAndImmutableEngine() {
        val original = FiniteLifecycleFixtures.sequence()
        for(index in listOf(0,3,4)) {
            val changes: List<Pair<String,(OrderAccepted.Builder)->Unit>> = listOf(
                "order" to { it.orderId = "wrong" }, "engine" to { it.engineOrderId = "wrong" },
                "time" to { it.occurredAt = "2026-10-10T04:00:00Z" }, "invalid time" to { it.occurredAt = "wrong" },
                "blank event" to { it.eventId = "" }, "blank engine" to { it.engineOrderId = "" })
            for((label,edit) in changes) refusesChangedReceipt(original,index,"accepted $index $label") { record ->
                record.setMembers(0,record.getMembers(0).toBuilder().setAccepted(record.getMembers(0).accepted.toBuilder().apply(edit)))
            }
        }
        for(index in listOf(8,9,10,11)) {
            val changes: List<Pair<String,(OrderRejected.Builder)->Unit>> = listOf(
                "order" to { it.orderId = "wrong" }, "time" to { it.occurredAt = "2026-10-10T04:00:00Z" },
                "invalid time" to { it.occurredAt = "wrong" }, "blank event" to { it.eventId = "" },
                "blank code" to { it.code = "" }, "blank reason" to { it.reason = "" })
            for((label,edit) in changes) refusesChangedReceipt(original,index,"rejected $index $label") { record ->
                record.setMembers(0,record.getMembers(0).toBuilder().setRejected(record.getMembers(0).rejected.toBuilder().apply(edit)))
            }
        }
    }
    @Test fun restoredTradeFactsRequireFullScopeProvenanceEconomicsAndCurrentLimits() {
        val original = FiniteLifecycleFixtures.sequence()
        val changes: List<Pair<String,(TradeSourceV1.Builder)->Unit>> = listOf(
            "price cap" to { it.fact = it.fact.toBuilder().setPrice(it.fact.price.toBuilder().setNanos("100000000001")).build() },
            "sell current limit" to { it.fact = it.fact.toBuilder().setPrice(it.fact.price.toBuilder().setNanos("99000000000")).build() },
            "zero price" to { it.fact = it.fact.toBuilder().setPrice(it.fact.price.toBuilder().setNanos("0")).build() },
            "negative price" to { it.fact = it.fact.toBuilder().setPrice(it.fact.price.toBuilder().setNanos("-1")).build() },
            "nonnumeric price" to { it.fact = it.fact.toBuilder().setPrice(it.fact.price.toBuilder().setNanos("x")).build() },
            "quantity cap" to { it.fact = it.fact.toBuilder().setQuantity(it.fact.quantity.toBuilder().setUnits("11")).build() },
            "zero quantity" to { it.fact = it.fact.toBuilder().setQuantity(it.fact.quantity.toBuilder().setUnits("0")).build() },
            "negative quantity" to { it.fact = it.fact.toBuilder().setQuantity(it.fact.quantity.toBuilder().setUnits("-1")).build() },
            "nonnumeric quantity" to { it.fact = it.fact.toBuilder().setQuantity(it.fact.quantity.toBuilder().setUnits("x")).build() },
            "run" to { it.runId = "foreign" }, "instrument" to { it.fact = it.fact.toBuilder().setInstrumentId("foreign").build() },
            "currency" to { it.fact = it.fact.toBuilder().setPrice(it.fact.price.toBuilder().setCurrency("EUR")).build() },
            "time" to { it.fact = it.fact.toBuilder().setOccurredAt("2026-10-10T04:00:00Z").build() },
            "invalid time" to { it.fact = it.fact.toBuilder().setOccurredAt("wrong").build() },
            "blank event" to { it.fact = it.fact.toBuilder().clearEventId().build() },
            "blank trade ID" to { it.fact = it.fact.toBuilder().clearTradeId().build() },
            "blank execution ID" to { it.fact = it.fact.toBuilder().clearExecutionId().build() },
            "unrelated order" to { it.fact = it.fact.toBuilder().setBuyOrderId("p0-8").build() },
            "self order" to { it.fact = it.fact.toBuilder().setBuyOrderId(it.fact.sellOrderId).build() },
        )
        for((label,edit) in changes) refusesChangedReceipt(original,2,label) { changeTrade(it,edit) }
        val sourceChanges: List<Pair<String,(SourceProvenanceV1.Builder)->Unit>> = listOf(
            "offset" to { it.sourceOffset = 999 }, "topic" to { it.sourceTopic = "foreign" },
            "topic UUID" to { it.sourceTopicId = "foreign" }, "generation" to { it.sourceGeneration = 2 },
            "partition" to { it.sourcePartition = 1 }, "batch" to { it.batchId = "foreign" },
            "checksum" to { it.batchChecksum = "0".repeat(64) }, "ordinal" to { it.outcomeOrdinal = 1 },
            "command" to { it.commandId = "foreign" })
        for((label,edit) in sourceChanges) refusesChangedReceipt(original,2,"source $label") { record ->
            changeTrade(record) { it.source = it.source.toBuilder().apply(edit).build() }
        }
    }
    @Test fun restoredExecutionsRequireCompleteDistinctTypedPairsAndExactOutcomeRelationship() {
        val original = FiniteLifecycleFixtures.sequence()
        val changes: List<Pair<String,(ExecutionCreated.Builder)->Unit>> = listOf(
            "event" to { it.eventId = "" }, "execution" to { it.executionId = "wrong" },
            "order" to { it.orderId = "wrong" }, "instrument" to { it.instrumentId = "wrong" },
            "quantity" to { it.quantity = it.quantity.toBuilder().setUnits("2").build() },
            "price" to { it.executionPrice = it.executionPrice.toBuilder().setNanos("1").build() },
            "currency" to { it.executionPrice = it.executionPrice.toBuilder().setCurrency("EUR").build() },
            "time" to { it.occurredAt = "2026-10-10T04:00:00Z" }, "invalid time" to { it.occurredAt = "wrong" },
            "missing role" to { it.liquidityRole = LiquidityRole.LIQUIDITY_ROLE_UNSPECIFIED })
        for((label,edit) in changes) refusesChangedReceipt(original,2,"execution $label") { record ->
            val member = record.getMembers(1).toBuilder()
            record.setMembers(1,member.setExecutions(0,member.getExecutions(0).toBuilder().apply(edit)))
        }
        val memberChanges: List<Pair<String,(LifecycleMemberV1.Builder)->Unit>> = listOf(
            "missing execution" to { it.removeExecutions(0) },
            "duplicate execution" to { it.setExecutions(1,it.getExecutions(0)) },
            "blank executions" to { it.clearExecutions().addExecutions(ExecutionCreated.getDefaultInstance()).addExecutions(ExecutionCreated.getDefaultInstance()) },
            "duplicate role" to { it.setExecutions(1,it.getExecutions(1).toBuilder().setLiquidityRole(it.getExecutions(0).liquidityRole)) },
            "command" to { it.command = it.command.toBuilder().setTraceId("changed").build() },
            "payload hash" to { it.commandPayloadHash = "0".repeat(64) }, "rejected status" to { it.outcomeStatus = "rejected" },
            "extra acceptance" to { it.accepted = original.getCompletedRecords(2).getMembers(0).accepted },
            "extra rejection" to { it.rejected = original.getCompletedRecords(8).getMembers(0).rejected },
            "extra revision" to { it.resultingRevision = original.getCompletedRecords(2).getMembers(0).resultingRevision })
        for((label,edit) in memberChanges) refusesChangedReceipt(original,2,label) { record ->
            record.setMembers(1,record.getMembers(1).toBuilder().apply(edit))
        }
        refusesChangedReceipt(original,2,"trade after rejected parent") { record ->
            val command = record.getMembers(0).toBuilder()
            command.setDisposition(LifecycleDispositionV1.LIFECYCLE_REJECTED).setOutcomeStatus("rejected")
                .clearAccepted().clearImmutableAcceptance().clearResultingRevision()
                .setRejected(OrderRejected.newBuilder().setEventId("test-rejection").setOrderId(command.command.orderId)
                    .setOccurredAt(command.command.occurredAt).setCode("REJECTED").setReason("test rejection"))
            record.setMembers(0,command)
        }
        refusesChangedReceipt(original,2,"trade with cancel command") { record ->
            val member = record.getMembers(1).toBuilder()
            record.setMembers(1,member.setCommand(member.command.toBuilder().setKind(LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL)
                .setCancel(LifecycleCancelV1.newBuilder().setReason("test cancel"))))
        }
        refusesChangedReceipt(original,0,"accepted command carries rejected result") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().setRejected(original.getCompletedRecords(8).getMembers(0).rejected))
        }
        refusesChangedReceipt(original,8,"rejected command carries trade") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().setTrade(original.getCompletedRecords(2).getMembers(1).trade))
        }
    }
    @Test fun immutableSubmitAcceptanceAndReplayCoverageCannotBeChangedUnderRecomputedDigests() {
        val original = FiniteLifecycleFixtures.sequence()
        val changes: List<Pair<String,(AcceptedOrderSourceV1.Builder)->Unit>> = listOf(
            "engine" to { it.fact = it.fact.toBuilder().setEngineOrderId("wrong").build() },
            "source" to { it.source = it.source.toBuilder().setCommandId("wrong").build() },
            "accepted event" to { it.acceptance = it.acceptance.toBuilder().setEventId("wrong").build() },
            "account" to { it.fact = it.fact.toBuilder().setAccountId("wrong").build() },
            "time" to { it.fact = it.fact.toBuilder().setAcceptedAt("wrong").build() },
            "quantity" to { it.fact = it.fact.toBuilder().setQuantityUnits("1").build() })
        for((label,edit) in changes) refusesChangedReceipt(original,0,"immutable $label") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().setImmutableAcceptance(record.getMembers(0).immutableAcceptance.toBuilder().apply(edit)))
        }
        val replayed = FiniteLifecycleReducer.reduce(original,FiniteLifecycleFixtures.bodies[2],12,13,binding).state
        val raw = replayed.getCompletedRecords(12).toBuilder().setOutcomeCount(0).setTradeCount(3).build()
        val changed = raw.toBuilder().setContentDigest(FiniteLifecycleContract.digest(raw)).build()
        val wrongCounts = replayed.toBuilder().setCompletedRecords(12,changed)
            .setCaptureBytes(replayed.captureBytes-replayed.getCompletedRecords(12).serializedSize+changed.serializedSize).build()
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(wrongCounts,0,13)) }
        assertEquals(replayed,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(replayed,0,13)))
    }
    @Test fun replayPhysicalIdsMustRetainExactKindsAllOrdinalsAndDistinctCommandTradeIdentity() {
        val original = replayCheckpoint()
        val changes: List<Pair<String,(LifecycleMemberIdV1.Builder)->Unit>> = listOf(
            "command kind" to { it.kind = LifecycleMemberKindV1.LIFECYCLE_MEMBER_COMMAND },
            "unspecified kind" to { it.kind = LifecycleMemberKindV1.LIFECYCLE_MEMBER_UNSPECIFIED },
            "unknown kind" to { it.kindValue = 99 },
            "within ordinal" to { it.withinOutcomeTradeOrdinal = 99 },
            "unsigned within ordinal" to { it.withinOutcomeTradeOrdinal = -1 },
            "flat ordinal" to { it.flattenedTradeOrdinal = 99 },
            "unsigned flat ordinal" to { it.flattenedTradeOrdinal = -1 },
            "outcome ordinal" to { it.source = it.source.toBuilder().setOutcomeOrdinal(99).build() },
            "unsigned outcome ordinal" to { it.source = it.source.toBuilder().setOutcomeOrdinal(-1).build() },
            "command identity" to { it.source = it.source.toBuilder().setCommandId("wrong").build() })
        for((label,edit) in changes) refusesChangedReplay(original,label) { record ->
            record.setMembers(1,record.getMembers(1).toBuilder().setId(record.getMembers(1).id.toBuilder().apply(edit)))
        }
        refusesChangedReplay(original,"duplicate command ID") { record ->
            record.setMembers(1,record.getMembers(1).toBuilder().setId(record.getMembers(0).id))
        }
        refusesChangedReplay(original,"duplicate trade ID") { record ->
            record.setMembers(2,record.getMembers(2).toBuilder().setId(record.getMembers(1).id))
        }
        refusesChangedReplay(original,"swapped trade IDs") { record ->
            val first = record.getMembers(1); val second = record.getMembers(2)
            record.setMembers(1,first.toBuilder().setId(second.id)).setMembers(2,second.toBuilder().setId(first.id))
        }
        refusesChangedReplay(original,"changed command member kind") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().setId(record.getMembers(0).id.toBuilder().setKind(LifecycleMemberKindV1.LIFECYCLE_MEMBER_TRADE)))
        }
    }
    @Test fun replayMemberSourceMustEqualNewRecordCoordinatesAndOriginalOutcomeCommand() {
        val original = replayCheckpoint()
        val changes: List<Pair<String,(SourceProvenanceV1.Builder)->Unit>> = listOf(
            "topic" to { it.sourceTopic = "wrong" }, "topic UUID" to { it.sourceTopicId = "wrong" },
            "generation" to { it.sourceGeneration = 2 }, "partition" to { it.sourcePartition = 1 },
            "offset" to { it.sourceOffset = 999 }, "batch" to { it.batchId = "wrong" },
            "checksum" to { it.batchChecksum = "0".repeat(64) },
            "command" to { it.commandId = "wrong" }, "outcome" to { it.outcomeOrdinal = 99 })
        for(index in 0..2) for((label,edit) in changes) refusesChangedReplay(original,"member $index source $label") { record ->
            val member = record.getMembers(index)
            record.setMembers(index,member.toBuilder().setId(member.id.toBuilder().setSource(member.id.source.toBuilder().apply(edit))))
        }
    }
    @Test fun replayFactsResultsEconomicsDependenciesAndOptionalPresenceStayExact() {
        val original = replayCheckpoint()
        val changes: List<Pair<String,(LifecycleMemberV1.Builder)->Unit>> = listOf(
            "missing replay link" to { it.clearReplayOf() },
            "wrong replay link" to { it.replayOf = it.replayOf.toBuilder().setFlattenedTradeOrdinal(99).build() },
            "disposition" to { it.disposition = LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION },
            "command run" to { it.command = it.command.toBuilder().setRunId("wrong").build() },
            "command party" to { it.command = it.command.toBuilder().setParticipantId("wrong").build() },
            "command account" to { it.command = it.command.toBuilder().setAccountId("wrong").build() },
            "command metadata" to { it.command = it.command.toBuilder().setTraceId("wrong").build() },
            "command hash" to { it.commandPayloadHash = "0".repeat(64) }, "status" to { it.outcomeStatus = "rejected" },
            "extra accepted presence" to { it.accepted = OrderAccepted.getDefaultInstance() },
            "extra rejected presence" to { it.rejected = OrderRejected.getDefaultInstance() },
            "extra revision presence" to { it.resultingRevision = LifecycleRevisionIdV1.getDefaultInstance() },
            "extra acceptance presence" to { it.immutableAcceptance = AcceptedOrderSourceV1.getDefaultInstance() },
            "trade source rebased" to { it.trade = it.trade.toBuilder().setSource(it.id.source).build() },
            "trade price" to { it.trade = it.trade.toBuilder().setFact(it.trade.fact.toBuilder().setPrice(it.trade.fact.price.toBuilder().setNanos("99000000000"))).build() },
            "trade currency" to { it.trade = it.trade.toBuilder().setFact(it.trade.fact.toBuilder().setPrice(it.trade.fact.price.toBuilder().setCurrency("EUR"))).build() },
            "trade time" to { it.trade = it.trade.toBuilder().setFact(it.trade.fact.toBuilder().setOccurredAt("2026-10-10T04:00:00Z")).build() },
            "trade identity" to { it.trade = it.trade.toBuilder().setFact(it.trade.fact.toBuilder().setTradeId("wrong")).build() },
            "trade run" to { it.trade = it.trade.toBuilder().setRunId("wrong").build() },
            "execution quantity" to { it.setExecutions(0,it.getExecutions(0).toBuilder().setQuantity(it.getExecutions(0).quantity.toBuilder().setUnits("1"))) },
            "execution price" to { it.setExecutions(0,it.getExecutions(0).toBuilder().setExecutionPrice(it.getExecutions(0).executionPrice.toBuilder().setNanos("1"))) },
            "execution role" to { it.setExecutions(0,it.getExecutions(0).toBuilder().setLiquidityRole(it.getExecutions(1).liquidityRole)) },
            "execution ID" to { it.setExecutions(0,it.getExecutions(0).toBuilder().setExecutionId("wrong")) },
            "execution count" to { it.removeExecutions(1) },
            "execution ordering" to { val first = it.getExecutions(0); it.setExecutions(0,it.getExecutions(1)).setExecutions(1,first) },
            "dependency count" to { it.removeDependencies(0) },
            "dependency economics" to { it.setDependencies(0,it.getDependencies(0).toBuilder().setLimitPrice("1")) },
            "dependency revision" to { it.setDependencies(0,it.getDependencies(0).toBuilder().setRevision(it.getDependencies(0).revision.toBuilder().setRevision(99))) },
            "dependency effect" to { it.setDependencies(0,it.getDependencies(0).toBuilder().setPreviousEffect(it.id)) })
        for((label,edit) in changes) refusesChangedReplay(original,label) { record ->
            record.setMembers(1,record.getMembers(1).toBuilder().apply(edit))
        }
        refusesChangedReplay(original,"accepted command result") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().setAccepted(record.getMembers(0).accepted.toBuilder().setEngineOrderId("wrong")))
        }
        refusesChangedReplay(original,"accepted command revision") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().setResultingRevision(record.getMembers(0).resultingRevision.toBuilder().setRevision(99)))
        }
        refusesChangedReplay(original,"immutable acceptance") { record ->
            record.setMembers(0,record.getMembers(0).toBuilder().clearImmutableAcceptance())
        }
        refusesChangedReplay(original,"member ordering") { record ->
            val command = record.getMembers(0); record.setMembers(0,record.getMembers(1)).setMembers(1,command)
        }
    }
    @Test fun replayRecordCutCountsOriginAndAccountingMustRemainCertified() {
        val original = replayCheckpoint()
        val sourceChanges: List<Pair<String,(SourceProvenanceV1.Builder)->Unit>> = listOf(
            "topic" to { it.sourceTopic = "wrong" }, "UUID" to { it.sourceTopicId = "wrong" },
            "generation" to { it.sourceGeneration = 2 }, "partition" to { it.sourcePartition = 1 },
            "offset" to { it.sourceOffset = 999 }, "regressed offset" to { it.sourceOffset = 1 },
            "batch" to { it.batchId = "wrong" }, "checksum" to { it.batchChecksum = "0".repeat(64) },
            "root command" to { it.commandId = "wrong" }, "root outcome" to { it.outcomeOrdinal = 1 })
        for((label,edit) in sourceChanges) refusesChangedReplay(original,"record $label") { record ->
            record.setSource(record.source.toBuilder().apply(edit)); record.completedFrontier = record.source
        }
        val recordChanges: List<Pair<String,(FiniteLifecycleCaptureV1.Builder)->Unit>> = listOf(
            "schema" to { it.schema = "wrong" }, "binding" to { it.finiteBindingDigest = "0".repeat(64) },
            "resume" to { it.resumeOffset = 12 }, "frontier" to { it.completedFrontier = it.completedFrontier.toBuilder().setSourceOffset(99).build() },
            "outcome/trade counts" to { it.outcomeCount = 0; it.tradeCount = 3 },
            "member count" to { it.memberCount = 2 }, "source byte accounting" to { it.sourceEncodedBytes++ },
            "source digest format" to { it.sourceContentDigest = "wrong" }, "closure" to { it.prefixClosed = false })
        for((label,edit) in recordChanges) refusesChangedReplay(original,label,edit)
        assertEquals(original,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(original,0,13)))
    }
}
