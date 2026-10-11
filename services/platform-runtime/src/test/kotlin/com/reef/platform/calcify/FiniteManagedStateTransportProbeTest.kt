package com.reef.platform.calcify

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.TopologyTestDriver
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.OrderAccepted

class FiniteManagedStateTransportProbeTest {
    private fun fields(d:Descriptors.Descriptor,names:String){assertEquals(names.split(' '),d.fields.map{it.name})}
    private fun framed(d:Descriptors.Descriptor,n:Int,bytes:Long):Long {
        val field=d.findFieldByNumber(n);require(field.type in listOf(Descriptors.FieldDescriptor.Type.STRING,Descriptors.FieldDescriptor.Type.BYTES,Descriptors.FieldDescriptor.Type.MESSAGE))
        return CodedOutputStream.computeTagSize(n)+CodedOutputStream.computeUInt32SizeNoTag(Math.toIntExact(bytes))+bytes
    }
    /** All scalar wire values conservatively allow ten varint bytes, including int32/enums. */
    private fun component(d:Descriptors.Descriptor,batchIdBytes:Long=128):Long=d.fields.sumOf { f ->
        require(!f.isRepeated)
        when(f.javaType){
            Descriptors.FieldDescriptor.JavaType.STRING->framed(d,f.number,if(d==SourceProvenanceV1.getDescriptor() && f.name=="batch_id")batchIdBytes else 128)
            Descriptors.FieldDescriptor.JavaType.MESSAGE->framed(d,f.number,component(f.messageType,batchIdBytes))
            Descriptors.FieldDescriptor.JavaType.INT,Descriptors.FieldDescriptor.JavaType.LONG,Descriptors.FieldDescriptor.JavaType.ENUM->CodedOutputStream.computeTagSize(f.number)+10L
            else->error("unbounded/new component type ${f.fullName}")
        }
    }
    @Test fun checkedCanonicalReachableBoundAndFactoryIdentity(){
        val state=FiniteLifecycleStateV1.getDescriptor();val batch=FiniteLifecycleBatchStateV1.getDescriptor();val suffix=FiniteLifecycleSuffixV1.getDescriptor()
        fields(state,"state_version finite_binding_digest source_profile_hash orders batches execution_ids completed_records completed_frontier resume_offset source_bytes capture_bytes fault retained_suffix binding_identity")
        fields(batch,"batch_id batch_checksum first_capture");fields(suffix,"offset payload resume_offset")
        fields(SourceProvenanceV1.getDescriptor(),"source_generation source_partition source_offset outcome_ordinal source_topic source_topic_id batch_id batch_checksum command_id")
        fields(AcceptedOrderFactV1.getDescriptor(),"order_id engine_order_id client_order_id run_id venue_session_id instrument_id participant_id account_id side order_type quantity_units limit_price currency time_in_force accepted_at")
        fields(OrderAccepted.getDescriptor(),"event_id order_id engine_order_id occurred_at")
        fields(AcceptedOrderSourceV1.getDescriptor(),"fact acceptance source")
        fields(LifecycleMemberIdV1.getDescriptor(),"source kind within_outcome_trade_ordinal flattened_trade_ordinal")
        fields(LifecycleRevisionIdV1.getDescriptor(),"command revision")
        fields(FiniteLifecycleOrderStateV1.getDescriptor(),"acceptance revision previous_effect quantity_units limit_price filled_units terminal")
        val budget=FiniteLifecycleBudget()
        // Only effect-generating member IDs are copied into order rows. Each generating capture
        // contains current batch ID in envelope source+frontier+member ID+revision.command (APPLIED)
        // or trade.source (READY_EXECUTION). Rejected/replayed members do not alter order rows.
        val orderBatchIdBound=budget.captureBytes/4L
        val order=component(FiniteLifecycleOrderStateV1.getDescriptor(),orderBatchIdBound)
        // Zero-outcome batches need not have those four copies: use actual source byte ceiling.
        val batchBytes=framed(batch,1,budget.sourceBytes.toLong())+framed(batch,2,128)+framed(batch,3,budget.captureBytes.toLong())
        val completed=framed(state,7,budget.captureBytes.toLong());val batchRow=framed(state,5,batchBytes)
        val suffixRow=framed(state,13,22+framed(suffix,2,budget.sourceBytes.toLong()))
        // Certified-history reconstruction gives batches<=completed, and completed+suffix<=publications.
        // Each publication slot therefore costs at most max(completed+batch,suffix), including framing.
        val perPublication=maxOf(completed+batchRow,suffixRow)
        val identityBound="calcify-finite-capture-binding-v1\u0000".toByteArray().size+32+
            "calcify-finite-budget-v1\u0000".toByteArray().size+32+5*(4+128)+4+4*(4+128)+2+10*8+8*(4+128)+4+4+8+4+8+8
        assertTrue(identityBound<=4096)
        val base=(1..3).sumOf{framed(state,it,128)}+framed(state,8,component(SourceProvenanceV1.getDescriptor(),budget.sourceBytes.toLong()))+33+
            framed(state,12,4L*2048)+framed(state,14,4096)
        val bound=base+budget.orderIds*framed(state,4,order)+budget.publications*budget.trades*framed(state,6,128)+budget.publications*perPublication
        assertTrue(bound<budget.stateBytes)
        val builder=FiniteLifecycleCaptureProcessor.managedStoreBuilder();assertEquals(FiniteLifecycleCaptureProcessor.STORE,builder.name());assertTrue(builder.loggingEnabled())
        assertTrue(builder.build().persistent())
        val binding=FiniteLifecycleFixtures.binding()
        val production=FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Genesis(0,0,0))
        val raw=FiniteManagedStateTransportProbe.rawTopology(binding,true,"0".repeat(64),{})
        assertEquals(production.describe().toString(),raw.describe().toString())
        val receipt=FiniteBindingContract.strictObject("{}".toByteArray(),2).apply{
            put("schema","calcify-g9-checked-canonical-bound-v1");put("canonicalOnlyUnknownFieldsExcluded",true);put("exactNaturalMaximum",false)
            put("boundBytes",bound.toString());put("declaredStateCapBytes",budget.stateBytes.toString());put("identityUpperBytes",identityBound)
            put("orderComponentBytes",order.toString());put("perPublicationUpperBytes",perPublication.toString());put("suffixRowBytes",suffixRow.toString());put("baseBytes",base.toString())
            put("orderSourceBatchIdUpperBytes",orderBatchIdBound.toString());put("otherBatchIdUpperBytes",budget.sourceBytes)
            put("basis","Canonical typed strings<=128UTF8 bytes through validation/equality to command fields, except batchId: source/frame<=65536; order-effect batch IDs<=captureBytes/4 from source+frontier+memberId+APPLIED revision.command or READY_EXECUTION trade.source copies. Rejected/replayed members never change order rows. Fault.take(2048)<=8192UTF8 bytes. Complete capture guard covers all nested/imported fields; orders<=8; execution IDs<=128; batches<=completed; completed+suffix<=16. Restored corruption separately guarded by exact serializedSize before put.")
        }
        val dir=Path.of("build/finite-o2-receipts/g9-transport-prep");Files.createDirectories(dir);Files.write(dir.resolve("checked-bound.json"),FiniteBindingContract.encode(receipt))
    }
    @Test fun effectSourceBatchIdsHaveFourCanonicalSerializedCopies(){
        val binding=FiniteLifecycleFixtures.binding();val canonical=FiniteLifecycleFixtures.sequence()
        fun copies(message:com.google.protobuf.Message,batchId:String):Int =
            (if(message.descriptorForType==SourceProvenanceV1.getDescriptor() && message.getField(message.descriptorForType.findFieldByName("batch_id"))==batchId)1 else 0)+
                message.allFields.entries.sumOf{(field,value)->if(field.javaType!=Descriptors.FieldDescriptor.JavaType.MESSAGE)0 else if(field.isRepeated)(value as List<*>).sumOf{copies(it as com.google.protobuf.Message,batchId)}else copies(value as com.google.protobuf.Message,batchId)}
        val kinds=mutableSetOf<LifecycleCommandKindV1>();var trade=false;var rejected=false
        for(record in canonical.completedRecordsList)for(member in record.membersList){
            if(member.disposition==LifecycleDispositionV1.LIFECYCLE_APPLIED){
                assertEquals(member.id.source,member.resultingRevision.command.source);kinds+=member.command.kind
                if(member.command.hasSubmit())assertEquals(member.id.source,member.immutableAcceptance.source)
                assertTrue(copies(record,member.id.source.batchId)>=4)
            }else if(member.disposition==LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION){
                trade=true;assertEquals(member.id.source,member.trade.source);assertTrue(copies(record,member.id.source.batchId)>=4)
            }else if(member.disposition==LifecycleDispositionV1.LIFECYCLE_REJECTED){rejected=true;assertFalse(member.hasResultingRevision());assertFalse(member.hasImmutableAcceptance())}
        }
        assertEquals(setOf(LifecycleCommandKindV1.LIFECYCLE_COMMAND_SUBMIT,LifecycleCommandKindV1.LIFECYCLE_COMMAND_MODIFY,LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL),kinds)
        assertTrue(trade);assertTrue(rejected)
        // Regression witness: batch IDs actually exceed128 and must remain accounted for.
        val large=FiniteLifecycleFixtures.change(FiniteLifecycleFixtures.bodies[0]){it.put("batchId","b".repeat(20000))}
        val reduced=FiniteLifecycleReducer.reduce(FiniteLifecycleContract.genesis(binding),large,0,1,binding)
        assertEquals(20000,reduced.state.ordersList.single().acceptance.source.batchId.toByteArray().size)
        assertTrue(copies(reduced.envelope!!,"b".repeat(20000))>=4)
        FiniteLifecycleReducer.validateState(reduced.state,binding)
    }
    @Test fun realTypedHealthyThenFaultAndSyntheticPreParserStayDistinct(){
        val binding=FiniteLifecycleFixtures.binding().copy(run="qualification-run",session="qualification-session",sourceTopic="qualification-source",commandTopic="qualification-command")
        val inputs=FiniteManagedStateTransportProbe.healthyInputs(binding,FiniteLifecycleFixtures.bodies)
        assertEquals(8,inputs.size);assertTrue(inputs.all{it.size==8000})
        var healthy:FiniteLifecycleStateV1?=null;var fault:FiniteLifecycleStateV1?=null
        TopologyTestDriver(FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Genesis(0,0,0)),
            FiniteLifecycleCaptureRuntime.modelProperties(binding,"dummy:1234")).use {driver->
            val input=driver.createInputTopic(binding.sourceTopic,ByteArraySerializer(),ByteArraySerializer())
            inputs.forEach{input.pipeInput(null,it)}
            val store=driver.getKeyValueStore<String,ByteArray>(FiniteLifecycleCaptureProcessor.STORE)
            healthy=FiniteLifecycleStateV1.parseFrom(store.get("state"));assertEquals(8,healthy!!.ordersCount);assertEquals(8,healthy!!.completedRecordsCount);assertTrue(healthy!!.fault.isEmpty())
            repeat(7){input.pipeInput(null,ByteArray(8000){120})}
            fault=FiniteLifecycleStateV1.parseFrom(store.get("state"));FiniteLifecycleReducer.validateState(fault!!,binding)
            assertEquals(8,fault!!.completedRecordsCount);assertEquals(7,fault!!.retainedSuffixCount);assertTrue(fault!!.fault.isNotEmpty())
        }
        val bytes=fault!!.toByteArray();FiniteManagedStateTransportProbe.preParserCheck(binding,false,FiniteLifecycleContract.sha(bytes),bytes)
        val genesis=FiniteLifecycleContract.genesis(binding)
        assertEquals(genesis,FiniteManagedStateTransportProbe.healthyInitial(binding,genesis))
        assertFails{FiniteManagedStateTransportProbe.healthyInitial(binding,null)}
        assertFails{FiniteManagedStateTransportProbe.healthyInitial(binding,healthy)}
        assertFails{FiniteManagedStateTransportProbe.healthyInitial(binding,fault)}
        assertFails{FiniteManagedStateTransportProbe.healthyInitial(binding,genesis.toBuilder().setFiniteBindingDigest("0".repeat(64)).build())}
        val synthetic=FiniteManagedStateTransportProbe.payload();assertEquals(6307840,synthetic.size)
        assertFails{FiniteLifecycleStateV1.parseFrom(synthetic)}
        FiniteManagedStateTransportProbe.preParserCheck(binding,true,FiniteLifecycleContract.sha(synthetic),synthetic)
        assertFails{FiniteManagedStateTransportProbe.preParserCheck(binding,true,"0".repeat(64),synthetic)}
        val receipt=FiniteBindingContract.strictObject("{}".toByteArray(),2).apply{put("schema","calcify-g9-preparation-model-v1");put("healthyStateBytes",healthy!!.serializedSize);put("faultStateBytes",fault!!.serializedSize);put("healthyCompleted",8);put("faultSuffix",7);put("sourceRecordBytes",8000);put("syntheticBytes",synthetic.size);put("liveOrColdRestoreClaim",false)}
        val dir=Path.of("build/finite-o2-receipts/g9-transport-prep");Files.createDirectories(dir);Files.write(dir.resolve("model-seam.json"),FiniteBindingContract.encode(receipt))
    }
}
