package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.apache.kafka.streams.StreamsConfig
import reef.contracts.calcify.v1.FiniteLifecycleStateV1

class FiniteCaptureProofTest {
    @Test fun pendingCommittedSuffixRefusesDrainButControlGapsRemainLegal() {
        FiniteCaptureProof.sourceCut(1,4,1,listOf(0),listOf(0),false)
        assertFails{FiniteCaptureProof.sourceCut(1,4,1,listOf(0,2),listOf(0),true)}
        FiniteCaptureProof.sourceCut(1,4,1,listOf(0),listOf(0),true)
        FiniteCaptureProof.sourceCut(4,4,3,listOf(0,2),listOf(0,2),true)
        assertFails{FiniteCaptureProof.sourceCut(1,4,3,listOf(0,2),listOf(0,2),true)}
    }
    @Test fun stoppedUnresolvedActivationRefusesWhileRestoredKnownSourceCanDrain() {
        val snapshot=FiniteBindingContract.strictObject("{\"run\":{\"admissionState\":\"STOPPED\",\"unresolvedPublications\":\"1\"}}".toByteArray(),256)
        assertFails{FiniteCaptureProof.activationReady(snapshot)}
        val binding=FiniteLifecycleFixtures.binding();var observed:FiniteLifecycleStateV1?=null
        org.apache.kafka.streams.TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,
            FiniteLifecycleStartCut.Genesis(0,0,1),verifiedInitialize={FiniteLifecycleContract.genesis(binding)},observe={observed=it}),
            FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use {driver->
            val input=driver.createInputTopic(binding.sourceTopic,org.apache.kafka.common.serialization.ByteArraySerializer(),org.apache.kafka.common.serialization.ByteArraySerializer())
            input.pipeInput(null,FiniteLifecycleFixtures.bodies[0]);assertEquals(1,observed!!.completedRecordsCount)
            assertEquals(1L,observed!!.resumeOffset);FiniteLifecycleReducer.validateState(observed!!,binding)
            assertFails{FiniteCaptureProof.activationReady(snapshot)}
        }
    }
    @Test fun highWaterModelStateSerializesWithinFullLiveTransport() {
        val binding=FiniteLifecycleFixtures.binding();var state=FiniteLifecycleContract.genesis(binding)
        for(i in 0..15) {
            val n=i%8;val order="p0-${n+1}";val engine="engine-$n-"+"e".repeat(119)
            val client="client-$n-"+"c".repeat(119);val participant=if(n%2==0)"seller" else "buyer"
            val body=FiniteLifecycleFixtures.change(FiniteLifecycleFixtures.bodies[if(i<8)0 else 4]) {root->
                root.put("batchId","high-water-batch-$i");root.put("firstSequence",i+1);root.put("lastSequence",i+1)
                val outcome=FiniteLifecycleFixtures.outcome(root);outcome.put("commandId","high-water-command-$i");outcome.put("orderId",order);outcome.put("streamSequence",i+1)
                val cmd=outcome["lifecycleCommand"] as ObjectNode
                cmd.put("commandId","high-water-command-$i");cmd.put("orderId",order);cmd.put("participantId",participant);cmd.put("accountId","$participant-account")
                for(k in listOf("traceId","causationId","correlationId","actorId"))cmd.put(k,"x".repeat(128))
                val accepted=outcome["result"]["accepted"] as ObjectNode;accepted.put("orderId",order);accepted.put("engineOrderId",engine);accepted.put("eventId","event-$i")
                if(i<8) {
                    val submit=cmd["submit"] as ObjectNode;submit.put("clientOrderId",client);submit.put("side",if(participant=="seller")"SELL" else "BUY");submit.put("quantityUnits","10")
                    submit.put("limitPrice",if(participant=="seller")"100000000000" else "99999999999")
                    val fact=outcome["result"]["acceptedOrder"] as ObjectNode
                    fact.put("orderId",order);fact.put("engineOrderId",engine);fact.put("clientOrderId",client);fact.put("participantId",participant);fact.put("accountId","$participant-account")
                    fact.put("side",submit["side"].textValue());fact.put("quantityUnits","10");fact.put("limitPrice",submit["limitPrice"].textValue())
                }
            }
            // Legal wire whitespace saturates actual raw source-byte budget, without fake facts.
            require(body.size<=binding.budget.sourceBytes)
            val padded=body+ByteArray(binding.budget.sourceBytes-body.size){32}
            state=FiniteLifecycleReducer.reduce(state,padded,i.toLong(),i+1L,binding).state
        }
        FiniteLifecycleReducer.validateState(state,binding)
        assertEquals(8,state.ordersCount);assertEquals(16,state.batchesCount);assertEquals(16,state.completedRecordsCount)
        assertEquals(binding.budget.sourceTotalBytes,state.sourceBytes);assertEquals(1,state.completedRecordsList.maxOf{it.outcomeCount})
        val fault=org.apache.kafka.streams.TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,
            FiniteLifecycleStartCut.Genesis(0,0,16)),FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use {driver->
            val input=driver.createInputTopic(binding.sourceTopic,org.apache.kafka.common.serialization.ByteArraySerializer(),org.apache.kafka.common.serialization.ByteArraySerializer())
            repeat(16){input.pipeInput(null,ByteArray(binding.budget.sourceBytes){120})}
            FiniteLifecycleStateV1.parseFrom(driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE).get("state"))
        }
        FiniteLifecycleReducer.validateState(fault,binding);assertEquals(16,fault.retainedSuffixCount)
        val tradeState=FiniteLifecycleFixtures.sequence();FiniteLifecycleReducer.validateState(tradeState,binding)
        val opening=FiniteBindingFixtures.opening();assertEquals(6307840L,opening.budget.stateBytes)
        val properties=FiniteLifecycleCaptureRuntime.liveProperties(opening.toCaptureBinding(),java.util.Properties().apply{put("bootstrap.servers","unused");put("security.protocol","SASL_PLAINTEXT")},"private-state")
        val transport=6307840+1024
        for(key in listOf(StreamsConfig.producerPrefix("max.request.size"),StreamsConfig.consumerPrefix("max.partition.fetch.bytes"),StreamsConfig.consumerPrefix("fetch.max.bytes"),StreamsConfig.topicPrefix("max.message.bytes")))
            assertEquals(transport.toLong(),(properties[key] as Number).toLong())
        val receipt=FiniteBindingContract.strictObject("{}".toByteArray(),2)
        receipt.put("schema","calcify-finite-managed-model-serialization-v1");receipt.put("declaredStateCeilingBytes",opening.budget.stateBytes.toString())
        receipt.put("highWaterOrders",state.ordersCount);receipt.put("highWaterCompletedRecords",state.completedRecordsCount);receipt.put("highWaterBatches",state.batchesCount)
        receipt.put("highWaterSourceBytes",state.sourceBytes.toString());receipt.put("highWaterStateSerializedBytes",state.serializedSize)
        receipt.put("fullFaultSuffixSerializedBytes",fault.serializedSize);receipt.put("nonzeroTradeFixtureStateSerializedBytes",tradeState.serializedSize)
        receipt.put("nonzeroTradeFixtureMaximumTradesPerRecord",tradeState.completedRecordsList.maxOf{it.tradeCount});receipt.put("fullTransportWithFramingBytes",transport)
        receipt.put("exactReachableWorstStateMeasured",false)
        val path=Path.of("../../.planning/calcify-p3-o2-2026-10-10/kotlin/managed-state-serialization.json");Files.createDirectories(path.parent);Files.write(path,FiniteBindingContract.encode(receipt))
        assertTrue(listOf(state,fault,tradeState).all{it.serializedSize<=opening.budget.stateBytes})
    }
}
