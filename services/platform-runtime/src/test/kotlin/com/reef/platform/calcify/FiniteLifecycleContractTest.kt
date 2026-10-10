package com.reef.platform.calcify

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.JsonCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.OrderSide

internal object FiniteLifecycleFixtures {
    val root: Path = Path.of("../../contracts/calcify")
    val bodies: List<ByteArray> get() = Files.readAllLines(root.resolve("finite-lifecycle-source-v1.jsonl")).map { it.toByteArray() }
    fun binding(budget: FiniteLifecycleBudget = FiniteLifecycleBudget()): FiniteLifecycleBinding {
        val manifest = JsonCodec.parseObject(Files.readString(root.resolve("finite-lifecycle-source-v1-manifest.json")))
        return FiniteLifecycleBinding(manifest.strictTextField("finiteBindingDigest"),manifest.strictTextField("sourceProfileHash"),
            "p3-run","p3-session","AAPL","USD",listOf(FiniteParty("buyer","buyer-account",OrderSide.ORDER_SIDE_BUY),
                FiniteParty("seller","seller-account",OrderSide.ORDER_SIDE_SELL)),manifest.strictTextField("sourceTopic"),"fixture-source-uuid",1,0,
            manifest.strictTextField("commandTopic"),"fixture-command-uuid","CALCIFY_P3_CAPTURE","fixture-capture-uuid","finite-capture-model",0,budget)
    }
    fun change(bytes: ByteArray, mutate: (ObjectNode)->Unit): ByteArray {
        val mapper = JsonMapper.builder().build(); val root = mapper.readTree(bytes) as ObjectNode
        mutate(root)
        root.put("payloadChecksum",JsonCodec.parseObject(mapper.writeValueAsString(root)).semanticSha256(
            setOf("createdAt","workFinishedAt","timingChecksum","payloadChecksum","payloadChecksumAlgorithm")))
        return mapper.writeValueAsBytes(root)
    }
    fun outcome(root: ObjectNode) = root.get("outcomes").get(0) as ObjectNode
    fun sequence(offsets: List<Long> = (0L..11L).toList()): FiniteLifecycleStateV1 {
        val binding = binding(); var state = FiniteLifecycleContract.genesis(binding)
        bodies.forEachIndexed { index, payload -> state = FiniteLifecycleReducer.reduce(state,payload,offsets[index],offsets[index]+1,binding).state }
        return state
    }
}

class FiniteLifecycleContractTest {
    @Test fun realGoFixtureChecksumsBytesAndTypedFacts() {
        val binding = FiniteLifecycleFixtures.binding()
        val manifest = JsonCodec.parseObject(Files.readString(FiniteLifecycleFixtures.root.resolve("finite-lifecycle-source-v1-manifest.json")))
        val file = Files.readAllBytes(FiniteLifecycleFixtures.root.resolve("finite-lifecycle-source-v1.jsonl"))
        assertEquals(manifest.strictTextField("sha256"),FiniteLifecycleContract.sha(file))
        assertEquals(manifest.strictIntField("bytes"),file.size)
        FiniteLifecycleFixtures.bodies.forEachIndexed { i, bytes ->
            val batch = FiniteLifecycleContract.parse(bytes,binding,i.toLong())
            val line = manifest.strictObjectDocuments("lines")[i]
            assertEquals(line.strictTextField("sha256"),batch.digest)
            assertEquals(line.strictTextField("payloadChecksum"),batch.source.batchChecksum)
            assertEquals(line.strictIntField("bytes"),batch.bytes)
            assertEquals(1,batch.outcomes.size)
            assertEquals("p3-cmd-%02d".format(i+1),batch.outcomes.single().command.commandId)
            val command = batch.outcomes.single().command
            assertEquals(command,OrderLifecycleCommandV1.parseFrom(command.toByteArray()))
            if(!command.hasSubmit()) assertFalse(command.toString().contains("currency"))
        }
    }
    @Test fun pairedLegacyBytesRemainExactAndFiniteCaptureRefusesMissingTypedFact() {
        val file = Files.readAllBytes(FiniteLifecycleFixtures.root.resolve("finite-lifecycle-legacy-source-v1.jsonl"))
        assertEquals("bdd4df11f562ba2f4dd217168a3e81e60f96fa4c3fa4a25c01e75830dda26506",FiniteLifecycleContract.sha(file))
        assertEquals(16647,file.size)
        val first = file.toString(Charsets.UTF_8).lineSequence().first().toByteArray()
        CalcifySourceBatch.checked(first,"CALCIFY_P3_SOURCE",0)
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(first,FiniteLifecycleFixtures.binding(),0) }
        assertEquals(21,CalcifyWire.commitment(CommitmentId(1,0,0,0)).size)
        assertEquals(23,CalcifyWire.passed(CommitmentVerificationPassed(CommitmentId(1,0,0,0),1)).size)
    }
    @Test fun typedScopeVersionKindsOwnershipAndTypesFailClosed() {
        val original = FiniteLifecycleFixtures.bodies.first(); val binding = FiniteLifecycleFixtures.binding()
        val changes: List<(ObjectNode)->Unit> = listOf(
            { it.put("schema","bad") }, { it.put("finiteBindingDigest","0".repeat(64)) },
            { it.put("sourceProfileHash","0".repeat(64)) }, { it.put("runId","other") },
            { it.put("venueSessionId","other") }, { it.put("instrumentId","other") },
            { it.put("accountId","buyer-account") }, { it.put("commandId","other") },
            { it.put("unknown","poison") }, { it.remove("traceId") }, { it.put("traceId",42) },
            { it.set<ObjectNode>("cancel",JsonMapper.builder().build().createObjectNode().put("reason","x")) },
            { (it.get("submit") as ObjectNode).put("quantityUnits",3) },
            { (it.get("submit") as ObjectNode).put("quantityUnits","4") },
            { (it.get("submit") as ObjectNode).put("timeInForce","IOC") },
            { (it.get("submit") as ObjectNode).put("side","BUY") },
        )
        for(change in changes) {
            val changed = FiniteLifecycleFixtures.change(original) { root -> change(FiniteLifecycleFixtures.outcome(root).get("lifecycleCommand") as ObjectNode) }
            assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(changed,binding,0) }
        }
        val duplicate = original.toString(Charsets.UTF_8).replace("\"schema\":","\"schema\":\"ignored\",\"schema\":").toByteArray()
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(duplicate,binding,0) }
        val unchecked = original.toString(Charsets.UTF_8).replace("\"quantityUnits\":\"3\"","\"quantityUnits\":\"4\"").toByteArray()
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleContract.parse(unchecked,binding,0) }
    }
    @Test fun finiteBudgetCanonicalBytesAndDigestPinned() {
        val binding = FiniteLifecycleFixtures.binding()
        val bytes = binding.budget.canonical(binding.profileHash,binding.policy,binding.run,binding.session,binding.instrument,binding.currency,binding.parties)
        assertContentEquals(bytes,binding.budget.canonical(binding.profileHash,binding.policy,binding.run,binding.session,binding.instrument,binding.currency,binding.parties.reversed()))
        assertEquals(260,bytes.size)
        val fixture = FiniteLifecycleFixtures.root.resolve("finite-lifecycle-budget-v1.hex")
        assertEquals(Files.readString(fixture).trim(),java.util.HexFormat.of().formatHex(bytes))
        assertEquals("6d49b6dd66e9cfa099e7b1c3cd02c9c275a9bf401d92069aace9634b5b4feee3",FiniteLifecycleContract.sha(bytes))
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleBudget(publications=17) }
        assertFailsWith<IllegalArgumentException> { FiniteLifecycleBudget(openWindows=2) }
        assertFailsWith<IllegalArgumentException> { binding.copy(parties=binding.parties.take(1)) }
    }
    @Test fun actualCaptureGoldenWireBytesRemainStable() {
        val state = FiniteLifecycleFixtures.sequence()
        for((index,name) in listOf(0 to "command",2 to "multi-fill")) {
            val expected = java.util.HexFormat.of().parseHex(Files.readString(FiniteLifecycleFixtures.root.resolve("finite-lifecycle-capture-$name-v1.hex")).trim())
            assertContentEquals(expected,state.getCompletedRecords(index).toByteArray())
            assertEquals(state.getCompletedRecords(index),FiniteLifecycleCaptureV1.parseFrom(expected))
        }
    }
}
