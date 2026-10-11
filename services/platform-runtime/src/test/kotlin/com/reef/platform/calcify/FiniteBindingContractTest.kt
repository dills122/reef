package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal object FiniteBindingFixtures {
    private val root=Path.of("../../contracts/calcify")
    fun openingBytes()=Files.readAllBytes(root.resolve("finite-binding-v1.json"))
    fun opening()=FiniteBindingContract.parseOpening(openingBytes())
    fun witnessBytes()=Files.readAllBytes(root.resolve("finite-startup-v1.json"))
    fun change(bytes:ByteArray,edit:(ObjectNode)->Unit)=FiniteBindingContract.strictObject(bytes,32768).let {edit(it);FiniteBindingContract.encode(it)}
}

class FiniteBindingContractTest {
    @Test fun canonicalGoldenAndImmutableAdapter() {
        val o=FiniteBindingFixtures.opening()
        assertEquals("1fe1749dba2b35803eabb41fc1f9106477147a2ad9966ecf2087eb5f78dc2c8a",o.digest)
        assertEquals(786,o.canonicalBytes().size)
        val hex=Files.readString(Path.of("../../contracts/calcify/finite-binding-v1.hex")).trim()
        assertEquals(hex,java.util.HexFormat.of().formatHex(o.canonicalBytes()))
        o.json().put("runId","changed");assertEquals("p3-run",o.run)
        assertEquals(o.digest,o.toCaptureBinding().digest)
        assertEquals("6d49b6dd66e9cfa099e7b1c3cd02c9c275a9bf401d92069aace9634b5b4feee3",
            FiniteLifecycleContract.sha(o.budget.canonical(o.profileHash,"calcify-limit-day-v1",o.run,o.session,o.instrument,"USD",o.parties)))
    }
    @Test fun completeSixteenEntryWitness() {
        val witness=FiniteBindingContract.parseStartup(FiniteBindingFixtures.witnessBytes(),FiniteBindingFixtures.opening().digest,"fixture-nonce-01")
        assertEquals(16,witness.journal.size);assertEquals("0",witness.journal.first()["offset"].textValue())
        assertEquals("1",witness.journal.first()["streamSequence"].textValue())
    }
    @Test fun strictJsonAndUnicodeRefuseDuplicatesTrailingMalformedBytes() {
        val body=FiniteBindingFixtures.openingBytes().toString(Charsets.UTF_8)
        for(bytes in listOf((body+" {}").toByteArray(),body.replace("\"schema\":","\"schema\":\"bad\",\"schema\":").toByteArray(),
            body.replace("p3-run","p3-\\ud800").toByteArray(),byteArrayOf(0xC3.toByte(),0x28)))
            assertFailsWith<IllegalArgumentException>{FiniteBindingContract.parseOpening(bytes)}
    }
    @Test fun openingRejectsUnknownTypesChangesAndUuidSpelling() {
        val changes:List<(ObjectNode)->Unit> = listOf(
            {it.put("unknown",1)},{it.put("partition",1)},{it.put("partition",0.0)},{it.put("partitionCount",2)},
            {it.put("initialSourceNextOffset","1")},{it.put("initialSourceNextOffset","00")},
            {it.put("profileHash","0".repeat(64))},{it.put("budgetHash","0".repeat(64))},
            {(it["budget"] as ObjectNode).put("attempts",32)},
            {(it["topics"][0] as ObjectNode).put("topicId","AAAAAAAAAAAAAAAAAAAAAA")},
            {(it["topics"][0] as ObjectNode).put("topicId","AAAAAAAAAAAAAAAAAAAAAQ==")},
            {(it["topics"][0] as ObjectNode).put("topicId","AAAAAAAAAAAAAAAAAAAAAg")},
            {(it["parties"][1] as ObjectNode).put("accountId","buyer-account")},
            {it.put("commandSubjectPrefix",".reef.commands")},{it.put("captureApplicationId","other")})
        for(change in changes) assertFailsWith<IllegalArgumentException>{FiniteBindingContract.parseOpening(FiniteBindingFixtures.change(FiniteBindingFixtures.openingBytes(),change))}
    }
    @Test fun utf16ParticipantAndAccountComparatorStableAcrossInputOrder() {
        fun changed(participant:Boolean,reversed:Boolean):FiniteOpening {
            val root=FiniteBindingContract.strictObject(FiniteBindingFixtures.openingBytes(),16384)
            val parties=root["parties"]
            (parties[0] as ObjectNode).put(if(participant)"participantId" else "accountId","\uD800\uDC00")
            (parties[1] as ObjectNode).put(if(participant)"participantId" else "accountId","\uE000")
            val pp=parties.map { FiniteParty(it["participantId"].textValue(),it["accountId"].textValue(),
                if(it["side"].textValue()=="BUY")reef.contracts.orderexecution.v1.OrderSide.ORDER_SIDE_BUY else reef.contracts.orderexecution.v1.OrderSide.ORDER_SIDE_SELL) }
            root.put("budgetHash",FiniteLifecycleContract.sha(FiniteBindingFixtures.opening().budget.canonical(root["profileHash"].textValue(),
                "calcify-limit-day-v1","p3-run","p3-session","AAPL","USD",pp)))
            if(reversed){ val a=parties[0].deepCopy<JsonNode>();(parties as com.fasterxml.jackson.databind.node.ArrayNode).set(0,parties[1]);parties.set(1,a) }
            return FiniteBindingContract.parseOpening(FiniteBindingContract.encode(root))
        }
        assertContentEquals(changed(true,false).canonicalBytes(),changed(true,true).canonicalBytes())
        assertContentEquals(changed(false,false).canonicalBytes(),changed(false,true).canonicalBytes())
        assertTrue("\uD800\uDC00" < "\uE000")
    }
    @Test fun witnessRejectsAccountingEpochPositionAndChallengeContradictions() {
        val changes:List<(ObjectNode)->Unit> = listOf(
            {it.put("nonce","wrong")},{it.put("bindingDigest","0".repeat(64))},
            {(it["run"] as ObjectNode).put("recordedAttempts","33")},
            {(it["run"] as ObjectNode).put("commandPayloadBytes","129")},
            {(it["run"] as ObjectNode).put("unresolvedPublications","1")},
            {(it["journal"][0] as ObjectNode).put("ownerEpoch","2")},
            {(it["journal"][0] as ObjectNode).put("streamSequence","0")},
            {(it["journal"][0] as ObjectNode).put("offset","-1")},
            {(it["journal"][0] as ObjectNode).put("offset",0)},
            {(it["journal"][0] as ObjectNode).put("state","UNKNOWN")},
            {(it["journal"][1] as ObjectNode).put("commandId","p3-cmd-01")},
            {(it["cuts"]["ranges"][0] as ObjectNode).put("beginningOffset","17")})
        for(change in changes) assertFailsWith<IllegalArgumentException>{FiniteBindingContract.parseStartup(
            FiniteBindingFixtures.change(FiniteBindingFixtures.witnessBytes(),change),FiniteBindingFixtures.opening().digest,"fixture-nonce-01")}
    }
    @Test fun wireBoundaryMeasuredActualBytes() {
        val raw=FiniteBindingFixtures.witnessBytes().toString(Charsets.UTF_8).trimEnd().toByteArray()
        val exact=raw+ByteArray(32768-raw.size){32}
        FiniteBindingContract.parseStartup(exact,FiniteBindingFixtures.opening().digest,"fixture-nonce-01")
        assertFailsWith<IllegalArgumentException>{FiniteBindingContract.parseStartup(exact+byteArrayOf(32),FiniteBindingFixtures.opening().digest,"fixture-nonce-01")}
        assertFalse(FiniteBindingContract.validNonce("x?"));assertFalse(FiniteBindingContract.validNonce(""))
    }
}
