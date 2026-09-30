package com.reef.platform.calcify

import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import kotlin.io.path.readLines
import kotlin.test.*
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.streams.TopologyTestDriver
import reef.contracts.calcify.v1.MatchContextResolvedV1

class CalcifyResolverProcessorTest {
    private val bodies=Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()
    private fun settings()=ResolverSettings(generation=1,sourceTopic="source",sourceTopicId="topic-uuid",verifiedTopic="verified",outputTopic="resolved")
    private fun driver(reader: VenueSourceReader, blocked: (Int, Boolean)->Unit = {_,_->}) = TopologyTestDriver(
        CalcifyResolverProcessor.topology(settings(), {reader}, blocked),
        Properties().apply {put("application.id","resolver-test");put("bootstrap.servers","dummy:1234")}
    )
    private fun reader()=object:VenueSourceReader {
        override fun next(cursor:Long,target:Long):VenueSourceEntry? = if(cursor+1<bodies.size) VenueSourceEntry(cursor+1,bodies[(cursor+1).toInt()].toByteArray()) else null
        override fun close() {}
    }
    @Test fun sequentialSourceAndExactOutputReplay() {
        driver(reader()).use { driver ->
            val input=driver.createInputTopic("verified",ByteArraySerializer(),ByteArraySerializer())
            val output=driver.createOutputTopic("resolved",ByteArrayDeserializer(),ByteArrayDeserializer())
            val passed=bodies.flatMapIndexed { offset,body -> CalcifySourceBatch.extract(body,"source",1,0,offset.toLong()).map {CommitmentVerificationPassed(it,1)} }
            passed.forEach { input.pipeInput(CalcifyWire.commitment(it.commitmentId),CalcifyWire.passed(it)) }
            val contexts=output.readKeyValuesToList().map {MatchContextResolvedV1.parseFrom(it.value)}
            assertEquals(130,contexts.size)
            assertEquals(passed.map {it.commitmentId.sourceOffset},contexts.map {it.commitment.sourceOffset})
            passed.forEach { input.pipeInput(CalcifyWire.commitment(it.commitmentId),CalcifyWire.passed(it)) }
            assertTrue(output.isEmpty)
            assertEquals(0L,driver.getKeyValueStore<String,ByteArray>("resolver").get("pendingCount").let {java.nio.ByteBuffer.wrap(it).long})
        }
    }
    @Test fun waitingSourceStagesPendingAndResumesThroughPunctuation() {
        var available=false;var blocked=false
        val actual=reader()
        val delayed=object:VenueSourceReader {override fun next(cursor:Long,target:Long)=if(available) actual.next(cursor,target) else null;override fun close() {}}
        driver(delayed) {_,value->blocked=value}.use { driver ->
            val input=driver.createInputTopic("verified",ByteArraySerializer(),ByteArraySerializer());val output=driver.createOutputTopic("resolved",ByteArrayDeserializer(),ByteArrayDeserializer())
            val passed=CommitmentVerificationPassed(CommitmentId(1,0,1,0),1)
            input.pipeInput(CalcifyWire.commitment(passed.commitmentId),CalcifyWire.passed(passed))
            assertTrue(blocked);assertTrue(output.isEmpty)
            assertNotNull(driver.getKeyValueStore<String,ByteArray>("resolver").get("stagedInputOffset"))
            available=true;driver.advanceWallClockTime(Duration.ofMillis(50))
            assertFalse(blocked);assertEquals(1,output.readKeyValuesToList().size)
        }
    }
    @Test fun poisonAndConflictingPendingAreDurableLaneFaults() {
        val absent=object:VenueSourceReader {override fun next(cursor:Long,target:Long):VenueSourceEntry?=null;override fun close() {}}
        driver(absent).use { driver ->
            val input=driver.createInputTopic("verified",ByteArraySerializer(),ByteArraySerializer())
            val id=CommitmentId(1,0,1,0)
            input.pipeInput(CalcifyWire.commitment(id),CalcifyWire.passed(CommitmentVerificationPassed(id,1)))
            input.pipeInput(CalcifyWire.commitment(id),CalcifyWire.passed(CommitmentVerificationPassed(id,2)))
            assertNotNull(driver.getKeyValueStore<String,ByteArray>("resolver").get("fault"))
        }
        driver(reader()).use { driver ->
            driver.createInputTopic("verified",ByteArraySerializer(),ByteArraySerializer()).pipeInput(byteArrayOf(1),byteArrayOf(1))
            assertNotNull(driver.getKeyValueStore<String,ByteArray>("resolver").get("fault"))
            assertTrue(driver.createOutputTopic("resolved",ByteArrayDeserializer(),ByteArrayDeserializer()).isEmpty)
        }
    }

    @Test fun bufferedSuffixAfterFaultIsRetained() {
        driver(reader()).use {driver->
            val input=driver.createInputTopic("verified",ByteArraySerializer(),ByteArraySerializer())
            input.pipeInput(byteArrayOf(1),byteArrayOf(1))
            val passed=CommitmentVerificationPassed(CommitmentId(1,0,1,0),1)
            input.pipeInput(CalcifyWire.commitment(passed.commitmentId),CalcifyWire.passed(passed))
            val state=driver.getKeyValueStore<String,ByteArray>("resolver")
            assertEquals(2L,java.nio.ByteBuffer.wrap(state.get("faultSuffixCount")).long)
            assertNotNull(state.get("Q:00000000000000000001"))
        }
    }
    @Test fun pendingOverflowFailsClosedWithoutOutput() {
        val absent=object:VenueSourceReader {override fun next(cursor:Long,target:Long):VenueSourceEntry?=null;override fun close() {}}
        val topology=CalcifyResolverProcessor.topology(settings().copy(maxPending=2),{absent})
        TopologyTestDriver(topology,Properties().apply {put("application.id","budget-test");put("bootstrap.servers","dummy:1234")}).use {driver->
            val input=driver.createInputTopic("verified",ByteArraySerializer(),ByteArraySerializer())
            for(i in 0..2) {val passed=CommitmentVerificationPassed(CommitmentId(1,0,1,i),1);input.pipeInput(CalcifyWire.commitment(passed.commitmentId),CalcifyWire.passed(passed))}
            assertNotNull(driver.getKeyValueStore<String,ByteArray>("resolver").get("fault"))
            assertTrue(driver.createOutputTopic("resolved",ByteArrayDeserializer(),ByteArrayDeserializer()).isEmpty)
        }
    }
}
