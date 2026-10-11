package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.streams.KafkaStreams
import reef.contracts.calcify.v1.FiniteLifecycleStateV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1

/** Managed state is observed, never treated as committed until broker history/output agree. */
internal class FiniteCaptureLive(private val opening:FiniteOpening,private val store:FiniteBindingStore,
    private val kafka:FiniteKafka,private val directory:Path,private val onFault:()->Unit,
    private val observer:(FiniteLifecycleStateV1)->Unit={}) : AutoCloseable {
    private val observed=AtomicReference<FiniteLifecycleStateV1?>()
    private val binding=opening.toCaptureBinding()
    private var streams:KafkaStreams?=null
    private fun initialize(existing:FiniteLifecycleStateV1?):FiniteLifecycleStateV1 {
        kafka.verifyIdentities();val snapshot=store.snapshot(opening.digest)
        require(snapshot["run"]["admissionState"].textValue()=="STOPPED"){"capture restore requires stopped admission"}
        val cuts=kafka.cuts()
        if(existing==null) {
            require(snapshot["run"]["activationEpoch"].textValue()=="0" && snapshot["run"]["possiblePublications"].textValue()=="0"){"missing previously activated managed state"}
            require(cuts["ranges"].all {it["beginningOffset"].textValue()=="0" && it["readCommittedEndOffset"].textValue()=="0" && it["endOffset"].textValue()=="0"}){"nonempty finite genesis history"}
            require(cuts["captureSourceGroup"]["nextOffset"].textValue()=="0"){"finite genesis group seed unavailable"}
            return FiniteLifecycleContract.genesis(binding)
        }
        verify(existing,cuts);return existing
    }
    fun start() {
        check(streams==null)
        kafka.verifyIdentities()
        val topology=FiniteLifecycleCaptureProcessor.modelTopology(binding,FiniteLifecycleStartCut.Genesis(0,0,0),
            blocked={if(it)onFault()},verifiedInitialize=::initialize,observe={observed.set(it);observer(it)})
        val next=KafkaStreams(topology,FiniteLifecycleCaptureRuntime.liveProperties(binding,kafka.properties(),directory.toString()))
        next.setUncaughtExceptionHandler {onFault();org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_APPLICATION}
        next.setStateListener {state,_->if(state==KafkaStreams.State.ERROR)onFault()}
        streams=next;next.start()
    }
    private fun verify(state:FiniteLifecycleStateV1,cuts:ObjectNode,drained:Boolean=false) {
        FiniteLifecycleReducer.validateState(state,binding);require(state.fault.isEmpty()){"finite capture fault"}
        val source=cuts["ranges"].single{it["role"].textValue()=="SOURCE"}
        val begin=source["beginningOffset"].textValue().toLong();val end=source["readCommittedEndOffset"].textValue().toLong()
        require(begin<=binding.genesisOffset){"finite source genesis lost"}
        val represented=maxOf(state.resumeOffset,state.retainedSuffixList.maxOfOrNull{it.resumeOffset}?:0)
        val group=cuts["captureSourceGroup"]["nextOffset"].textValue().toLong()
        require(group>=represented && group<=end){"finite source group/certified cut mismatch"}
        val sources=kafka.read("SOURCE",binding.genesisOffset,if(drained)end else group,binding.budget.publications,binding.budget.sourceTotalBytes)
        FiniteCaptureProof.sourceCut(group,end,represented,sources.map{it.offset()},state.completedRecordsList.map{it.source.sourceOffset},drained)
        sources.zip(state.completedRecordsList).forEach{(record,receipt)->
            require(record.offset()==receipt.source.sourceOffset && record.value()!=null &&
                FiniteLifecycleContract.sha(record.value())==receipt.sourceContentDigest){"finite retained source receipt mismatch"}
        }
        val capture=cuts["ranges"].single{it["role"].textValue()=="CAPTURE"}
        require(capture["beginningOffset"].textValue().toLong()==0L){"finite capture genesis lost"}
        val outputs=kafka.read("CAPTURE",0,capture["readCommittedEndOffset"].textValue().toLong(),binding.budget.publications,binding.budget.captureTotalBytes)
        require(outputs.size==state.completedRecordsCount){"finite output receipt missing/extra"}
        outputs.zip(state.completedRecordsList).forEach{(record,receipt)->require(record.value()!=null && FiniteLifecycleCaptureV1.parseFrom(record.value())==receipt){"finite capture output mismatch"}}
    }
    fun readiness(nonce:String):ObjectNode {
        require(FiniteBindingContract.validNonce(nonce));require(streams?.state()==KafkaStreams.State.RUNNING){"finite Streams not RUNNING"}
        val state=observed.get()?:throw IllegalStateException("finite task not initialized")
        val snapshot=store.snapshot(opening.digest);FiniteCaptureProof.activationReady(snapshot);require(snapshot["run"]["admissionState"].textValue()=="STOPPED"){"readiness requires stopped admission"}
        kafka.verifyIdentities();val cuts=kafka.cuts();verify(state,cuts,true)
        kafka.verifyManagedState(state.toByteArray(),cuts["ranges"].single{it["role"].textValue()=="CAPTURE_CHANGELOG"})
        require(store.snapshot(opening.digest)==snapshot && observed.get()==state && kafka.cuts()==cuts){"finite capture proof changed during readiness"}
        val result=FiniteBindingContract.strictObject("{}".toByteArray(),2)
        listOf("schema" to "calcify-finite-capture-ready-v1","nonce" to nonce,"bindingDigest" to opening.digest,
            "ownerEpoch" to snapshot["run"]["ownerEpoch"].textValue(),"activationEpoch" to snapshot["run"]["activationEpoch"].textValue(),
            "applicationId" to opening.applicationId,"sourceGroupNextOffset" to cuts["captureSourceGroup"]["nextOffset"].textValue(),
            "representedNextOffset" to state.resumeOffset.toString(),"captureReadCommittedEndOffset" to cuts["ranges"].single{it["role"].textValue()=="CAPTURE"}["readCommittedEndOffset"].textValue(),
            "stateDigest" to FiniteLifecycleContract.sha(state.toByteArray()),"completedRecords" to state.completedRecordsCount.toString(),
            "retainedSuffix" to state.retainedSuffixCount.toString(),"fault" to state.fault).forEach{result.put(it.first,it.second)}
        require(FiniteBindingContract.encode(result).size<=8192);return result
    }
    override fun close(){streams?.close(Duration.ofSeconds(10));streams=null}
}

/** Committed record membership certifies drain; numeric control/aborted gaps remain legal. */
internal object FiniteCaptureProof {
    fun activationReady(snapshot:ObjectNode) {
        require(snapshot["run"]["unresolvedPublications"].textValue()=="0"){"unresolved finite publication forbids activation readiness"}
    }
    fun sourceCut(group:Long,end:Long,represented:Long,records:List<Long>,completed:List<Long>,drained:Boolean) {
        require(group>=represented && group<=end){"finite committed group/certified cut mismatch"}
        require(records==completed && records.all{it<group}) {if(drained)"finite committed source suffix pending" else "finite source group skips unrepresented committed record"}
    }
}
