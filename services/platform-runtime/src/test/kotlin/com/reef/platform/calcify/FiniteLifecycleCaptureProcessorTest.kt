package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.apache.kafka.common.serialization.*
import org.apache.kafka.streams.TopologyTestDriver
import reef.contracts.calcify.v1.*

class FiniteLifecycleCaptureProcessorTest {
    private val binding = FiniteLifecycleFixtures.binding()
    private val bodies get() = FiniteLifecycleFixtures.bodies
    private fun driver() = TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,
        FiniteLifecycleStartCut.Genesis(0,0,16)),FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234"))
    @Test fun actualFullPrefixClosesZeroTradeAmendCancelAndRejections() {
        val state = FiniteLifecycleFixtures.sequence()
        val members = state.completedRecordsList.flatMap { it.membersList }
        assertEquals(12,state.completedRecordsCount); assertEquals(15,members.size)
        assertEquals(8,members.count { it.disposition == LifecycleDispositionV1.LIFECYCLE_APPLIED })
        assertEquals(4,members.count { it.disposition == LifecycleDispositionV1.LIFECYCLE_REJECTED })
        val trades = members.filter { it.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION }
        assertEquals(3,trades.size); assertEquals(6L,trades.sumOf { it.trade.fact.quantity.units.toLong() })
        assertEquals(listOf(0,1,0),trades.map { it.id.flattenedTradeOrdinal })
        assertEquals(listOf(0,1,0),trades.map { it.id.withinOutcomeTradeOrdinal })
        assertEquals(5,state.ordersCount)
        val cancelled = state.ordersList.single { it.acceptance.fact.orderId == "p0-2" }
        assertEquals("2",cancelled.acceptance.fact.quantityUnits); assertEquals("4",cancelled.quantityUnits)
        assertEquals("99000000000",cancelled.limitPrice); assertEquals("1",cancelled.filledUnits)
        assertEquals(2,cancelled.revision.revision); assertEquals(LifecycleOrderTerminalV1.LIFECYCLE_ORDER_CANCELLED,cancelled.terminal)
        assertEquals(4,state.ordersList.count { it.terminal == LifecycleOrderTerminalV1.LIFECYCLE_ORDER_FILLED })
        assertEquals(0,members.last().dependenciesCount)
        assertEquals(1,members.single { it.command.commandId == "p3-cmd-09" }.dependenciesCount)
        assertTrue(trades.all { it.dependenciesCount == 2 })
        assertEquals(11L,state.completedFrontier.sourceOffset); assertEquals(12L,state.resumeOffset)
        assertTrue(state.completedRecordsList.all { it.prefixClosed && it.contentDigest == FiniteLifecycleContract.digest(it) })
        assertTrue(trades.all { it.executionsCount == 2 })
        val out = System.getenv("FINITE_CAPTURE_EVIDENCE")
        if(out != null) {
            val path = Path.of(out); Files.createDirectories(path)
            state.completedRecordsList.forEachIndexed { index, record -> Files.write(path.resolve("capture-%02d.pb".format(index+1)),record.toByteArray()) }
            Files.write(path.resolve("state.pb"),state.toByteArray())
        }
    }
    @Test fun actualNumericOffsetGapsAndPhysicalReplay() {
        val offsets = listOf(0L,2L,5L,6L,9L,12L,14L,17L,20L,21L,25L,28L)
        val state = FiniteLifecycleFixtures.sequence(offsets)
        assertEquals(offsets,state.completedRecordsList.map { it.source.sourceOffset })
        assertEquals(29L,state.resumeOffset)
        val same = FiniteLifecycleReducer.reduce(state,bodies.last(),28,29,binding)
        assertEquals(state,same.state); assertNull(same.envelope)
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(state,bodies.first(),28,29,binding) }
    }
    @Test fun certifiedBatchReplayAddsPhysicalCoverageWithoutSecondMutation() {
        val state = FiniteLifecycleFixtures.sequence()
        val replay = FiniteLifecycleReducer.reduce(state,bodies[2],12,13,binding)
        assertEquals(state.ordersList,replay.state.ordersList); assertEquals(state.executionIdsList,replay.state.executionIdsList)
        assertEquals(3,replay.envelope!!.membersCount)
        assertTrue(replay.envelope.membersList.all { it.disposition == LifecycleDispositionV1.LIFECYCLE_REPLAY && it.hasReplayOf() && it.id.source.sourceOffset == 12L })
        val changed = FiniteLifecycleFixtures.change(bodies[2]) { it.put("shardId","changed") }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(state,changed,12,13,binding) }
        val newBatch = FiniteLifecycleFixtures.change(bodies[2]) { it.put("batchId","new-batch") }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(state,newBatch,12,13,binding) }
    }
    @Test fun entireMultiFillRecordFaultsAtomicallyAndMissingDependencyRefuses() {
        var state = FiniteLifecycleContract.genesis(binding)
        bodies.take(2).forEachIndexed { index, bytes -> state = FiniteLifecycleReducer.reduce(state,bytes,index.toLong(),index+1L,binding).state }
        val before = state.toByteArray()
        val malformed = FiniteLifecycleFixtures.change(bodies[2]) { root ->
            val result = FiniteLifecycleFixtures.outcome(root).get("result") as ObjectNode
            (result.get("trades").get(1) as ObjectNode).put("quantityUnits","9")
        }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(state,malformed,2,3,binding) }
        assertContentEquals(before,state.toByteArray())
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),bodies[2],2,3,binding) }
    }
    @Test fun sourceEnvelopeAndPublicationCapsExactAndOneOver() {
        val first = bodies.first()
        val sourceCap = binding.copy(budget=binding.budget.copy(sourceBytes=first.size))
        val result = FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(sourceCap),first,0,1,sourceCap)
        val captureCap = binding.copy(budget=binding.budget.copy(captureBytes=result.envelope!!.serializedSize))
        FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(captureCap),first,0,1,captureCap)
        val tooSmall = binding.copy(budget=binding.budget.copy(captureBytes=result.envelope.serializedSize-1))
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(tooSmall),first,0,1,tooSmall) }
        val sourceSmall = binding.copy(budget=binding.budget.copy(sourceBytes=first.size-1))
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(sourceSmall),first,0,1,sourceSmall) }
        var state = FiniteLifecycleFixtures.sequence()
        for(i in 12L..15L) state = FiniteLifecycleReducer.reduce(state,first,i,i+1,binding).state
        assertEquals(16,state.completedRecordsCount)
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(state,first,16,17,binding) }
    }
    @Test fun managedTopologyEmitsSameEnvelopesAndRetainsFaultSuffix() {
        driver().use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            val output = driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer())
            bodies.forEach { input.pipeInput(null,it) }
            val envelopes = output.readKeyValuesToList().map { FiniteLifecycleCaptureV1.parseFrom(it.value) }
            assertEquals(FiniteLifecycleFixtures.sequence().completedRecordsList,envelopes)
            assertEquals(12,envelopes.size)
        }
        driver().use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            input.pipeInput(null,bodies[2]); bodies.drop(3).forEach { input.pipeInput(null,it) }
            val state = FiniteLifecycleStateV1.parseFrom(driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE).get("state"))
            assertTrue(state.fault.isNotEmpty()); assertEquals(10,state.retainedSuffixCount)
            assertEquals(0,state.completedRecordsCount); assertEquals(0L,state.resumeOffset)
            assertTrue(driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer()).isEmpty)
        }
    }
    @Test fun managedRollbackCannotReuseAheadOfStoreAcceptanceOrExecutionCache() {
        driver().use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            val output = driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer())
            input.pipeInput(null,bodies[0]); input.pipeInput(null,bodies[1])
            val store = driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE)
            val before = store.get("state").copyOf()
            input.pipeInput(null,bodies[2]); assertEquals(3,output.readKeyValuesToList().size)
            // Model transaction abort restores managed bytes; next physical append is identical batch replay.
            store.put("state",before)
            input.pipeInput(null,bodies[2])
            val restored = FiniteLifecycleStateV1.parseFrom(store.get("state"))
            assertEquals(2,restored.executionIdsCount); assertEquals(3,restored.ordersCount)
            assertTrue(output.readValue().let(FiniteLifecycleCaptureV1::parseFrom).membersList.all { it.disposition != LifecycleDispositionV1.LIFECYCLE_REPLAY })
        }
    }
    @Test fun missingDuplicateAndContradictoryMembershipIsRejectedBeforeReduction() {
        val edits: List<(ObjectNode)->Unit> = listOf(
            { it.put("commandCount",2) },
            { FiniteLifecycleFixtures.outcome(it).put("status","rejected") },
            { (FiniteLifecycleFixtures.outcome(it).get("result") as ObjectNode).remove("trades") },
            { (FiniteLifecycleFixtures.outcome(it).get("result").get("executions").get(0) as ObjectNode).put("liquidityRole","MAKER") },
            { (FiniteLifecycleFixtures.outcome(it).get("result").get("executions").get(0) as ObjectNode).put("executionPrice","1") },
            { (FiniteLifecycleFixtures.outcome(it).get("result").get("trades").get(1) as ObjectNode).put("executionId",
                FiniteLifecycleFixtures.outcome(it).get("result").get("trades").get(0).get("executionId").asText()) },
            { (FiniteLifecycleFixtures.outcome(it).get("result") as ObjectNode).set<ObjectNode>("cancelled",ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)) },
        )
        for(edit in edits) assertFailsWith<IllegalArgumentException> {
            FiniteLifecycleContract.parse(FiniteLifecycleFixtures.change(bodies[2],edit),binding,2)
        }
    }
    @Test fun emptySourceRecordStillEmitsClosedCoverage() {
        val empty = FiniteLifecycleFixtures.change(bodies[0]) { root ->
            root.putArray("outcomes"); root.put("commandCount",0); root.put("firstSequence",0); root.put("lastSequence",0)
        }
        val capture = FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),empty,0,1,binding)
        assertEquals(0,capture.envelope!!.membersCount); assertTrue(capture.envelope.prefixClosed)
        assertEquals(1L,capture.state.resumeOffset); assertEquals(0L,capture.state.completedFrontier.sourceOffset)
    }
    @Test fun faultSuffixAtHighWatermarkPreservesEveryFiniteRecordAndOverflowAborts() {
        driver().use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            for(i in 0..15) input.pipeInput(null,bodies[2])
            val store = driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE)
            val bytes = store.get("state").copyOf()
            assertEquals(16,FiniteLifecycleStateV1.parseFrom(bytes).retainedSuffixCount)
            assertFails { input.pipeInput(null,bodies[2]) }
            assertContentEquals(bytes,store.get("state"))
        }
    }
    @Test fun managedStateEncodedCapAndByteCountersRefuseRestore() {
        val state = FiniteLifecycleFixtures.sequence()
        assertTrue(state.serializedSize <= binding.budget.stateBytes)
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.validateState(state.toBuilder().setSourceBytes(binding.budget.sourceTotalBytes+1).build(),binding) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.validateState(state.toBuilder().setCaptureBytes(binding.budget.captureTotalBytes+1).build(),binding) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.validateState(state.toBuilder().clearOrders().build(),binding) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.validateState(state.toBuilder().clearExecutionIds().build(),binding) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.validateState(state.toBuilder().clearBatches().build(),binding) }
        val changed = state.getOrders(0).toBuilder().clearPreviousEffect().build()
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.validateState(state.toBuilder().setOrders(0,changed).build(),binding) }
    }
    @Test fun reopenedManagedTopologyRestoresPrefixAndSuppressesCompletedPhysicalReplay() {
        val checkpoint = FiniteLifecycleFixtures.sequence()
        TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Restore(checkpoint,0,13)),
            FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            val output = driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer())
            bodies.forEach { input.pipeInput(null,it) }
            assertTrue(output.isEmpty)
            input.pipeInput(null,bodies[2])
            assertTrue(output.readValue().let(FiniteLifecycleCaptureV1::parseFrom).membersList.all { it.disposition == LifecycleDispositionV1.LIFECYCLE_REPLAY })
        }
    }
    @Test fun amendmentConservesFilledAndEngineIdentityWhileMissingAcceptanceTextFaults() {
        // Use real prefix reduction rather than hand-authored state.
        var prefix = FiniteLifecycleContract.genesis(binding)
        bodies.take(3).forEachIndexed { i, bytes -> prefix = FiniteLifecycleReducer.reduce(prefix,bytes,i.toLong(),i+1L,binding).state }
        val badEngine = FiniteLifecycleFixtures.change(bodies[3]) { root ->
            (FiniteLifecycleFixtures.outcome(root).get("result").get("accepted") as ObjectNode).put("engineOrderId","other")
        }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(prefix,badEngine,3,4,binding) }
        val badQuantity = FiniteLifecycleFixtures.change(bodies[3]) { root ->
            (FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand").get("modify") as ObjectNode).put("quantityUnits","1")
        }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(prefix,badQuantity,3,4,binding) }
        val missingClient = FiniteLifecycleFixtures.change(bodies[0]) { root ->
            (FiniteLifecycleFixtures.outcome(root).get("result").get("acceptedOrder") as ObjectNode).remove("clientOrderId")
            (FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand").get("submit") as ObjectNode).put("clientOrderId","")
        }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(missingClient,binding,0) }
    }
    @Test fun changedCompletedPhysicalReplayRetainsBarrierWithoutChangingSuccessfulCut() {
        val checkpoint = FiniteLifecycleFixtures.sequence()
        TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Restore(checkpoint,0,12)),
            FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use { driver ->
            val changed = FiniteLifecycleFixtures.change(bodies.first()) { it.put("shardId","contradiction") }
            driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer()).pipeInput(null,changed)
            val fault = FiniteLifecycleStateV1.parseFrom(driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE).get("state"))
            assertTrue(fault.fault.isNotEmpty()); assertEquals(1,fault.retainedSuffixCount)
            assertEquals(checkpoint.ordersList,fault.ordersList); assertEquals(checkpoint.completedFrontier,fault.completedFrontier)
            assertEquals(checkpoint.resumeOffset,fault.resumeOffset)
            assertTrue(driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer()).isEmpty)
        }
    }
    @Test fun trailingRootsScalarsAndGarbageRetainWholeValueWithoutSuccessfulCoverage() {
        for(suffix in listOf(" {} "," 42 "," trailing-garbage")) {
            val malformed = bodies.first()+suffix.toByteArray()
            val original = FiniteLifecycleContract.genesis(binding)
            assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(malformed,binding,0) }
            assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(original,malformed,0,1,binding) }
            driver().use { driver ->
                val store = driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE)
                val before = FiniteLifecycleStateV1.parseFrom(store.get("state"))
                driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer()).pipeInput(null,malformed)
                val fault = FiniteLifecycleStateV1.parseFrom(store.get("state"))
                assertTrue(fault.fault.isNotEmpty()); assertEquals(1,fault.retainedSuffixCount)
                assertContentEquals(malformed,fault.getRetainedSuffix(0).payload.toByteArray())
                assertEquals(before.completedRecordsList,fault.completedRecordsList); assertEquals(before.resumeOffset,fault.resumeOffset)
                assertEquals(before.completedFrontier,fault.completedFrontier); assertEquals(before.ordersList,fault.ordersList)
                assertEquals(before.executionIdsList,fault.executionIdsList); assertEquals(before.sourceBytes,fault.sourceBytes)
                assertEquals(before.captureBytes,fault.captureBytes)
                assertTrue(driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer()).isEmpty)
            }
        }
        val whitespace = bodies.first()+" \r\n\t ".toByteArray()
        val legal = FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),whitespace,0,1,binding)
        assertTrue(legal.envelope!!.prefixClosed); assertEquals(1L,legal.state.resumeOffset)
        assertEquals(whitespace.size.toLong(),legal.envelope.sourceEncodedBytes)
    }
    @Test fun laterExecutionFailureAfterReopenRetainsPrefixWithoutPartialFillOrOutput() {
        var prefix = FiniteLifecycleContract.genesis(binding)
        bodies.take(2).forEachIndexed { i,bytes -> prefix = FiniteLifecycleReducer.reduce(prefix,bytes,i.toLong(),i+1L,binding).state }
        val malformed = FiniteLifecycleFixtures.change(bodies[2]) { root ->
            (FiniteLifecycleFixtures.outcome(root).get("result").get("executions").get(3) as ObjectNode).put("eventId","")
        }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(prefix,malformed,2,3,binding) }
        TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Restore(prefix,0,12)),
            FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            input.pipeInput(null,bodies[0]); input.pipeInput(null,bodies[1]); input.pipeInput(null,malformed)
            val fault = FiniteLifecycleStateV1.parseFrom(driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE).get("state"))
            assertEquals(prefix.ordersList,fault.ordersList); assertEquals(prefix.executionIdsList,fault.executionIdsList)
            assertEquals(prefix.completedRecordsList,fault.completedRecordsList); assertEquals(prefix.completedFrontier,fault.completedFrontier)
            assertEquals(prefix.resumeOffset,fault.resumeOffset); assertTrue(fault.fault.isNotEmpty())
            assertContentEquals(malformed,fault.getRetainedSuffix(0).payload.toByteArray())
            assertTrue(driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer()).isEmpty)
        }
    }
    @Test fun tradeAboveCurrentBuyLimitFaultsEvenWithinBoundProfile() {
        var prefix = FiniteLifecycleContract.genesis(binding)
        bodies.take(2).forEachIndexed { i,bytes -> prefix = FiniteLifecycleReducer.reduce(prefix,bytes,i.toLong(),i+1L,binding).state }
        val malformed = FiniteLifecycleFixtures.change(bodies[2]) { root ->
            (FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand").get("submit") as ObjectNode).put("limitPrice","99000000000")
            (FiniteLifecycleFixtures.outcome(root).get("result").get("acceptedOrder") as ObjectNode).put("limitPrice","99000000000")
        }
        val before = prefix.toByteArray()
        val failure = assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(prefix,malformed,2,3,binding) }
        assertEquals("trade outside current limits",failure.message); assertContentEquals(before,prefix.toByteArray())
    }
    @Test fun rejectedAttemptedStringEconomicsRemainFactsWithoutAcceptedCapsOrNormalization() {
        // Mutations preserve producer's existing decoded-string shape, including Go's +03 spelling.
        for(index in listOf(9,10)) for(value in listOf("0","-1","11","100000000001","not-a-number","+03")) {
            val rejected = FiniteLifecycleFixtures.change(bodies[index]) { root ->
                val command = FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand") as ObjectNode
                val economics = command.get(if(index == 9) "submit" else "modify") as ObjectNode
                economics.put("quantityUnits",value); economics.put("limitPrice",value)
            }
            val capture = FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),rejected,0,1,binding)
            assertEquals(0,capture.state.ordersCount); assertEquals(0,capture.state.executionIdsCount)
            assertEquals(LifecycleDispositionV1.LIFECYCLE_REJECTED,capture.envelope!!.getMembers(0).disposition)
            val command = capture.envelope.getMembers(0).command
            assertEquals(value,if(index == 9) command.submit.quantityUnits else command.modify.quantityUnits)
            assertEquals(capture.state,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(capture.state,0,1)))
        }
        val wrongType = FiniteLifecycleFixtures.change(bodies[10]) { root ->
            (FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand").get("modify") as ObjectNode).put("quantityUnits",0)
        }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(wrongType,binding,0) }
        for(value in listOf("0","-1","11","100000000001","not-a-number")) {
            val accepted = FiniteLifecycleFixtures.change(bodies[0]) { root ->
                (FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand").get("submit") as ObjectNode).put("quantityUnits",value)
                (FiniteLifecycleFixtures.outcome(root).get("result").get("acceptedOrder") as ObjectNode).put("quantityUnits",value)
            }
            assertFailsWith<IllegalArgumentException> { FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),accepted,0,1,binding) }
        }
    }
    @Test fun exactReplayIdentitySupportsGapsRepeatedBatchesZeroTradeRejectionsAndReopen() {
        val original = FiniteLifecycleFixtures.sequence()
        var replayed = original
        for(offset in listOf(20L,30L)) {
            val reduction = FiniteLifecycleReducer.reduce(replayed,bodies[2],offset,offset+1,binding)
            val envelope = reduction.envelope!!
            assertEquals(3,envelope.membersList.map { it.id }.distinct().size)
            envelope.membersList.forEachIndexed { index,member ->
                val first = original.getCompletedRecords(2).getMembers(index)
                assertEquals(offset,member.id.source.sourceOffset); assertEquals(first.id.kind,member.id.kind)
                assertEquals(first.id.withinOutcomeTradeOrdinal,member.id.withinOutcomeTradeOrdinal)
                assertEquals(first.id.flattenedTradeOrdinal,member.id.flattenedTradeOrdinal)
                assertEquals(first.id.source.commandId,member.id.source.commandId); assertEquals(first.id,member.replayOf)
                if(member.hasTrade()) assertEquals(first.trade.source,member.trade.source)
            }
            replayed = reduction.state
            assertEquals(original.ordersList,replayed.ordersList); assertEquals(original.executionIdsList,replayed.executionIdsList)
            assertEquals(original.batchesList,replayed.batchesList)
            assertEquals(replayed,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(replayed,0,offset+1)))
        }
        for(index in listOf(0,3,4,8,9,10,11)) {
            val state = FiniteLifecycleReducer.reduce(original,bodies[index],20,21,binding).state
            assertEquals(0,state.completedRecordsList.last().tradeCount)
            assertEquals(original.ordersList,state.ordersList); assertEquals(original.executionIdsList,state.executionIdsList)
            assertEquals(state,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(state,0,21)))
        }
        val empty = FiniteLifecycleFixtures.change(bodies[0]) { root ->
            root.putArray("outcomes"); root.put("commandCount",0); root.put("firstSequence",0); root.put("lastSequence",0)
        }
        val firstEmpty = FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),empty,0,1,binding).state
        val replayEmpty = FiniteLifecycleReducer.reduce(firstEmpty,empty,5,6,binding).state
        assertEquals(0,replayEmpty.completedRecordsList.last().membersCount)
        assertEquals(replayEmpty,FiniteLifecycleCaptureRuntime.initializeModel(binding,FiniteLifecycleStartCut.Restore(replayEmpty,0,6)))

        val checkpoint = FiniteLifecycleReducer.reduce(original,bodies[2],12,13,binding).state
        TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Restore(checkpoint,0,14)),
            FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            val output = driver.createOutputTopic(binding.captureTopic,ByteArrayDeserializer(),ByteArrayDeserializer())
            bodies.forEach { input.pipeInput(null,it) }; input.pipeInput(null,bodies[2])
            assertTrue(output.isEmpty)
            input.pipeInput(null,bodies[2])
            val expected = FiniteLifecycleReducer.reduce(checkpoint,bodies[2],13,14,binding)
            assertEquals(expected.envelope,FiniteLifecycleCaptureV1.parseFrom(output.readValue()))
            val restored = FiniteLifecycleStateV1.parseFrom(driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE).get("state"))
            assertEquals(expected.state,restored); assertEquals(original.ordersList,restored.ordersList)
            assertEquals(original.executionIdsList,restored.executionIdsList); assertTrue(output.isEmpty)
        }
    }
}
