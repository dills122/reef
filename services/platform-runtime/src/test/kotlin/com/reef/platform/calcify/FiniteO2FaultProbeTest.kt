package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import reef.contracts.calcify.v1.*

class FiniteO2FaultProbeTest {
    private val binding=FiniteLifecycleFixtures.binding()
    private val commandId="p3-cmd-01"
    private fun state(offset:Long=0)=FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),
        FiniteLifecycleFixtures.bodies.first(),offset,offset+1,binding).state
    private fun observer(events:MutableList<Pair<String,ObjectNode>>,expected:FiniteLifecycleBinding=binding)=
        FiniteO2CaptureCommandGate(expected,commandId,{FiniteBindingContract.strictObject("{}".toByteArray(),2)}){name,event->events.add(name to event)}
    private fun recordChange(original:FiniteLifecycleStateV1,edit:(FiniteLifecycleCaptureV1.Builder)->Unit):FiniteLifecycleStateV1 {
        val changed=original.getCompletedRecords(0).toBuilder().also(edit).build()
        return original.toBuilder().setCompletedRecords(0,changed).build()
    }
    private fun assertRefused(candidate:FiniteLifecycleStateV1,expected:FiniteLifecycleBinding=binding) {
        val events=mutableListOf<Pair<String,ObjectNode>>()
        assertFailsWith<IllegalArgumentException>{observer(events,expected).observe(candidate)}
        assertTrue(events.isEmpty())
    }
    @Test fun ingressThreeArgumentsRemainAndMixedSelectorsArePrearmedExactIdentity() {
        for(boundary in FiniteIngressBoundary.entries) {
            val selected=FiniteO2FaultSelection.parse(arrayOf("/private/gates",boundary.name,"p3-cmd-02"))
            assertEquals(boundary,selected.boundary);assertNull(selected.captureCommandId);assertNull(selected.openingFile)
        }
        val valid=arrayOf("/private/gates","PUBLISHING","p3-cmd-02","captureCommandId=p3-cmd-01","openingFile=/fixture/state/fault-opening.json")
        assertEquals(commandId,FiniteO2FaultSelection.parse(valid).captureCommandId)
        val invalid=listOf(valid.take(4).toTypedArray(),valid+"extra",valid.copyOf().also{it[3]="0"},valid.copyOf().also{it[3]="1"},
            valid.copyOf().also{it[3]="captureCommandId="},valid.copyOf().also{it[3]="captureCommandId=p3-cmd-02"},
            valid.copyOf().also{it[3]="captureCommandId=other-cmd-01"},valid.copyOf().also{it[3]="captureCommandId=p3-cmd-01 captureCommandId=other"},
            valid.copyOf().also{it[1]="RESERVED"},valid.copyOf().also{it[2]="p3-cmd-03"},valid.copyOf().also{it[4]="openingFile="},
            valid.copyOf().also{it[4]="openingFile=/fixture/state/../state/fault-opening.json"},valid.copyOf().also{it[4]="/fixture/state/fault-opening.json"},
            valid.copyOf().also{it[2]="-cmd-02";it[3]="captureCommandId=-cmd-01"})
        for(args in invalid)assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.parse(args)}
    }
    @Test fun genuineCommandAtZeroOneAndPhysicalGapEmitsActualOffsetAndRawDigest() {
        for(offset in listOf(0L,1L,31L)) {
            val events=mutableListOf<Pair<String,ObjectNode>>();val candidate=state(offset);observer(events).observe(candidate)
            val (name,event)=events.single();assertEquals("capture-$commandId",name)
            assertEquals(commandId,event["captureCommandId"].textValue());assertEquals(listOf(commandId),event["commandIds"].map{it.textValue()})
            assertEquals(offset.toString(),event["sourceOffset"].textValue());assertEquals((offset+1).toString(),event["resumeOffset"].textValue())
            assertEquals(offset.toString(),event["completedFrontierSourceOffset"].textValue());assertEquals(1,event["completedRecordsCount"].intValue())
            assertTrue(event["sourceGeneration"].isIntegralNumber);assertTrue(event["sourcePartition"].isIntegralNumber)
            assertEquals(binding.generation,event["sourceGeneration"].intValue());assertEquals(binding.partition,event["sourcePartition"].intValue())
            assertEquals(binding.sourceTopic,event["sourceTopic"].textValue());assertEquals(binding.sourceTopicId,event["sourceTopicId"].textValue())
            assertEquals(FiniteLifecycleContract.sha(FiniteLifecycleFixtures.bodies.first()),event["sourceContentDigest"].textValue())
            assertEquals(FiniteLifecycleContract.sha(candidate.toByteArray()),event["stateDigest"].textValue())
            assertEquals("CAPTURE_PROCESSED_BEFORE_COMMIT",event["boundary"].textValue())
        }
    }
    @Test fun samePhysicalOffsetWrongCommandOrTradeOnlyNeverQualifies() {
        val original=state(1);val events=mutableListOf<Pair<String,ObjectNode>>()
        val wrong=FiniteO2CaptureCommandGate(binding,"other-cmd-01",{FiniteBindingContract.strictObject("{}".toByteArray(),2)}){name,event->events.add(name to event)}
        wrong.observe(original);assertTrue(events.isEmpty())
        val tradeOnly=recordChange(original){record->val member=record.getMembers(0).toBuilder();member.id=member.id.toBuilder().setKind(LifecycleMemberKindV1.LIFECYCLE_MEMBER_TRADE).build();record.setMembers(0,member)}
        observer(events).observe(tradeOnly);assertTrue(events.isEmpty())
        observer(events).observe(FiniteLifecycleContract.genesis(binding));assertTrue(events.isEmpty())
    }
    @Test fun configuredBindingAndSourceLaneMustMatchActualReceipt() {
        val original=state(1)
        for(expected in listOf(binding.copy(digest="0".repeat(64)),binding.copy(profileHash="0".repeat(64)),binding.copy(run="other"),
            binding.copy(session="other"),binding.copy(instrument="OTHER"),binding.copy(sourceTopic="OTHER"),binding.copy(sourceTopicId="other-uuid"),
            binding.copy(generation=2),binding.copy(partition=1)))assertRefused(original,expected)
        val sourceEdits:List<(SourceProvenanceV1.Builder)->Unit> = listOf({it.sourceTopic="OTHER"},{it.sourceTopicId="other-uuid"},
            {it.sourceGeneration=2},{it.sourcePartition=1},{it.sourceOffset=-1},{it.sourceOffset=FiniteBindingContract.MAX_OFFSET})
        for(edit in sourceEdits)assertRefused(recordChange(original){it.source=it.source.toBuilder().also(edit).build()})
        assertRefused(original.toBuilder().setFiniteBindingDigest("0".repeat(64)).build())
        assertRefused(original.toBuilder().setSourceProfileHash("0".repeat(64)).build())
        assertRefused(original.toBuilder().clearBindingIdentity().build())
    }
    @Test fun actualMemberContextAndFirstCompletedPrefixCannotBeForged() {
        val original=state(1)
        val commandEdits:List<(OrderLifecycleCommandV1.Builder)->Unit> = listOf({it.finiteBindingDigest="0".repeat(64)},
            {it.sourceProfileHash="0".repeat(64)},{it.runId="other"},{it.venueSessionId="other"},{it.instrumentId="OTHER"})
        for(edit in commandEdits)assertRefused(recordChange(original){record->val member=record.getMembers(0).toBuilder();
            member.command=member.command.toBuilder().also(edit).build();record.setMembers(0,member)})
        assertRefused(recordChange(original){record->val member=record.getMembers(0).toBuilder();
            member.id=member.id.toBuilder().setSource(member.id.source.toBuilder().setCommandId("forged")).build();record.setMembers(0,member)})
        assertRefused(recordChange(original){record->val member=record.getMembers(0).toBuilder();
            member.id=member.id.toBuilder().setSource(member.id.source.toBuilder().setSourceTopicId("forged")).build();record.setMembers(0,member)})
        assertRefused(original.toBuilder().addCompletedRecords(original.getCompletedRecords(0)).build())
        assertRefused(original.toBuilder().setFault("fault").build())
        assertRefused(original.toBuilder().addRetainedSuffix(FiniteLifecycleSuffixV1.newBuilder().setOffset(2).setResumeOffset(3)).build())
        assertRefused(original.toBuilder().setCompletedFrontier(original.completedFrontier.toBuilder().setSourceOffset(0)).build())
        assertRefused(original.toBuilder().setResumeOffset(9).build())
        assertRefused(recordChange(original){it.resumeOffset=9})
    }
    @Test fun synchronousGateHoldsObserverCallerAndEmitsOnceIncludingReentry() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=CountDownLatch(1)
        val emitted=AtomicInteger();val failure=AtomicReference<Throwable?>();val candidate=state(1)
        lateinit var observer:FiniteO2CaptureCommandGate
        observer=FiniteO2CaptureCommandGate(binding,commandId,{FiniteBindingContract.strictObject("{}".toByteArray(),2)}){_,_->
            emitted.incrementAndGet();observer.observe(candidate);entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val caller=Thread{try{observer.observe(candidate)}catch(ex:Throwable){failure.set(ex)}finally{returned.countDown()}}
        caller.start()
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(1L,returned.count);observer.observe(candidate);assertEquals(1,emitted.get())}
        finally{release.countDown();caller.join(5000)}
        assertFalse(caller.isAlive);assertNull(failure.get());assertEquals(0L,returned.count)
        observer.observe(candidate);assertEquals(1,emitted.get())
    }
    @Test fun openingFileRequiresPrivateCanonicalStableActualBindingBytes() {
        val dir=Files.createTempDirectory("finite-fault-opening-").toRealPath();val file=dir.resolve("opening.json")
        val opening=FiniteBindingFixtures.opening();val canonical=FiniteBindingContract.encode(opening.json())
        try {
            Files.write(file,canonical);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
            assertEquals(opening.digest,FiniteO2FaultSelection.opening(file,opening.digest).digest)
            assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.opening(file,"0".repeat(64))}
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"))
            assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.opening(file,opening.digest)}
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
            Files.write(file,canonical+byteArrayOf(10));assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.opening(file,opening.digest)}
            Files.write(file,ByteArray(FiniteBindingContract.WIRE_BYTES+1));assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.opening(file,opening.digest)}
            Files.write(file,canonical);val link=dir.resolve("link.json");Files.createSymbolicLink(link,file)
            assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.opening(link,opening.digest)};Files.delete(link)
            val parentLink=dir.resolve("parent-link");Files.createSymbolicLink(parentLink,dir)
            assertFailsWith<IllegalArgumentException>{FiniteO2FaultSelection.opening(parentLink.resolve("opening.json"),opening.digest)};Files.delete(parentLink)
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(dir)}
    }
}
