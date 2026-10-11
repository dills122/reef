package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.StreamCommandEnvelope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class FiniteIngressResult(val status:Int,val disposition:String,val commandId:String,val sequence:Long?=null,val acceptedAt:String?=null)
internal data class FiniteBrokerAck(val topic:String,val partition:Int,val offset:Long)
internal enum class FiniteIngressBoundary { RESERVED, PUBLISHING, BROKER_ACK, PUBLISHED }
internal fun interface FiniteIngressObserver { fun observe(boundary:FiniteIngressBoundary,commandId:String,ack:FiniteBrokerAck?) }
internal fun interface FiniteCommandPublisher {fun send(envelope:StreamCommandEnvelope):CompletableFuture<FiniteBrokerAck>}

/** No publication retry. Positive ACK must be durably recorded before a successful response. */
internal class FiniteIngress(private val opening:FiniteOpening,private val epoch:Long,
    private val store:FiniteBindingStore,private val publisher:FiniteCommandPublisher,
    private val scheduler:ScheduledExecutorService,private val timeoutMillis:Long,
    private val observer:FiniteIngressObserver=FiniteIngressObserver{_,_,_->}) {
    private val fenced=AtomicBoolean(false)
    private val inFlight=java.util.concurrent.ConcurrentHashMap<String,CompletableFuture<FiniteIngressResult>>()
    init {require(epoch>0 && timeoutMillis>0)}
    @Synchronized fun submit(envelope:StreamCommandEnvelope,refusal:String?=null):CompletableFuture<FiniteIngressResult> {
        val request=try { request(envelope,refusal) } catch(ex:IllegalArgumentException){return done(400,"INVALID_FINITE_COMMAND",envelope.commandId)}
        if(fenced.get()) return stoppedReplay(envelope,request)
        val decision=try {store.beginAttempt(opening.digest,epoch,request)} catch(ex:Exception){fenced.set(true);runCatching{store.stop(opening.digest,epoch)};return done(503,"DURABLE_INTAKE_FAILED",envelope.commandId)}
        if(decision.disposition=="PUBLISHED") {
            return try {
                val receipt=store.receipt(opening.digest,decision.commandId)
                check(receipt.commandId==decision.commandId && receipt.sequence==decision.streamSequence){"finite receipt identity mismatch"}
                done(202,"PUBLISHED",receipt.commandId,receipt.sequence,receipt.acceptedAt)
            }catch(ex:Exception){fenced.set(true);runCatching{store.stop(opening.digest,epoch)};done(503,"RECEIPT_UNAVAILABLE",decision.commandId)}
        }
        if(decision.disposition!="RESERVED")return done(when(decision.disposition){"CONFLICT"->409;"REFUSED"->422;else->503},decision.disposition,decision.commandId)
        val result=CompletableFuture<FiniteIngressResult>()
        if(inFlight.putIfAbsent(decision.commandId,result)!=null) {fenced.set(true);return done(503,"PUBLICATION_ALREADY_IN_FLIGHT",decision.commandId)}
        try {observer.observe(FiniteIngressBoundary.RESERVED,decision.commandId,null)}catch(ex:Exception){
            fenced.set(true);val durable=runCatching{store.stop(opening.digest,epoch)}.getOrDefault(false);inFlight.remove(decision.commandId,result);return done(503,if(durable)"RESERVED_STOPPED" else "STOP_NOT_DURABLE",decision.commandId)
        }
        val authorized=try {store.authorizeSend(opening.digest,epoch,decision.commandId)}catch(ex:Exception){false}
        if(!authorized){fenced.set(true);runCatching{store.stop(opening.digest,epoch)};inFlight.remove(decision.commandId);return done(503,"SEND_NOT_AUTHORIZED",decision.commandId)}
        // Authorization already charged a possible append. Every subsequent error is uncertain.
        val timeout=try {scheduler.schedule({ failUnknown(decision.commandId,result,"UNKNOWN") },timeoutMillis,TimeUnit.MILLISECONDS)}
            catch(ex:Exception){failUnknown(decision.commandId,result,"UNKNOWN");inFlight.remove(decision.commandId,result);return result}
        try {
            observer.observe(FiniteIngressBoundary.PUBLISHING,decision.commandId,null)
            publisher.send(envelope).whenComplete { ack,failure ->
                try {
                    if(failure!=null || ack==null) failUnknown(decision.commandId,result,"UNKNOWN")
                    else acknowledge(decision.commandId,ack,result)
                }catch(ex:Exception){failUnknown(decision.commandId,result,"UNKNOWN")}
                timeout.cancel(false)
                inFlight.remove(decision.commandId,result)
            }
        }catch(ex:Exception){timeout.cancel(false);failUnknown(decision.commandId,result,"UNKNOWN");inFlight.remove(decision.commandId,result)}
        return result
    }
    private fun stoppedReplay(envelope:StreamCommandEnvelope,request:ObjectNode):CompletableFuture<FiniteIngressResult> = try {
        val snapshot=store.snapshot(opening.digest)
        val entry=snapshot["journal"].singleOrNull { it["commandId"].textValue()==envelope.commandId &&
            it["state"].textValue()=="PUBLISHED" && listOf("payloadHash","keyHash","subjectHash","routeHash").all{key->it[key]==request[key]} }
        if(entry==null) done(503,"STOPPED",envelope.commandId) else {
            val receipt=store.receipt(opening.digest,envelope.commandId)
            check(receipt.sequence.toString()==entry["streamSequence"].textValue())
            done(202,"PUBLISHED",receipt.commandId,receipt.sequence,receipt.acceptedAt)
        }
    }catch(ex:Exception){done(503,"STOPPED",envelope.commandId)}
    private fun acknowledge(commandId:String,ack:FiniteBrokerAck,result:CompletableFuture<FiniteIngressResult>) {
        val valid=ack.topic==opening.topic("COMMAND").name && ack.partition==0 && ack.offset in 0 until FiniteBindingContract.MAX_OFFSET
        if(!valid){failUnknown(commandId,result,"ACK_NOT_DURABLE");return}
        observer.observe(FiniteIngressBoundary.BROKER_ACK,commandId,ack)
        val receipt=synchronized(result) {
            val recorded=try {store.acknowledge(opening.digest,commandId,epoch,ack.offset,ack.offset+1)}catch(ex:Exception){false}
            if(!recorded){failUnknown(commandId,result,"ACK_NOT_DURABLE");null}
            else try {store.receipt(opening.digest,commandId)}catch(ex:Exception){failUnknown(commandId,result,"RECEIPT_UNAVAILABLE");null}
        } ?: return
        if(receipt.commandId!=commandId || receipt.sequence!=ack.offset+1){failUnknown(commandId,result,"RECEIPT_UNAVAILABLE");return}
        observer.observe(FiniteIngressBoundary.PUBLISHED,commandId,ack)
        synchronized(result){result.complete(FiniteIngressResult(202,"PUBLISHED",commandId,receipt.sequence,receipt.acceptedAt))}
    }
    private fun failUnknown(commandId:String,result:CompletableFuture<FiniteIngressResult>,reason:String) = synchronized(result) {
        if(result.getNow(null)?.status==202)return@synchronized
        fenced.set(true)
        val durable=try {store.unknown(opening.digest,commandId,epoch)}catch(ex:Exception){false}
        result.complete(FiniteIngressResult(503,if(durable)reason else "STOP_NOT_DURABLE",commandId))
    }
    /** Stop new admission; callers must still drain callbacks and close actual producer before exit certification. */
    fun stop():Boolean {fenced.set(true);return store.stop(opening.digest,epoch)}
    fun pending():Int=inFlight.size
    private fun done(status:Int,disposition:String,id:String,sequence:Long?=null,acceptedAt:String?=null)=CompletableFuture.completedFuture(FiniteIngressResult(status,disposition,id,sequence,acceptedAt))
    private fun containsNul(node:JsonNode):Boolean = when {
        node.isTextual -> '\u0000' in node.textValue()
        node.isObject -> node.properties().any { '\u0000' in it.key || containsNul(it.value) }
        node.isArray -> node.any(::containsNul)
        else -> false
    }
    private fun request(e:StreamCommandEnvelope,refusal:String?):ObjectNode {
        require(e.runId==opening.run && e.venueSessionId==opening.session && e.instrumentId==opening.instrument && e.partition==0){"finite lane"}
        val bytes=e.payloadJson.toByteArray(Charsets.UTF_8)
        val body=FiniteBindingContract.strictObject(bytes,opening.budget.commandBytes)
        // Postgres JSONB cannot hold U+0000. Such a request cannot be admitted or charged as a refusal, and must not
        // reach SQL where the failed cast would fence the whole run. Same uncharged class as other malformed input.
        require(!containsNul(body) && listOf(e.scope,e.clientId,e.idempotencyKey,e.participantId,e.commandId,e.commandType,
            e.route,e.subject,e.natsMessageId).none{'\u0000' in it}){"finite NUL character"}
        for((key,value) in listOf("commandId" to e.commandId,"runId" to e.runId,"venueSessionId" to e.venueSessionId,"instrumentId" to e.instrumentId,
            "participantId" to e.participantId,"orderId" to e.orderId)) require(FiniteBindingContract.text(body,key,128)==value){"finite command envelope mismatch"}
        require(e.commandId.toByteArray().size<=128 && e.clientId.toByteArray().size in 1..128 && e.idempotencyKey.toByteArray().size in 1..128)
        val account=FiniteBindingContract.text(body,"accountId",128)
        val party=opening.parties.singleOrNull{it.participant==e.participantId && it.account==account}
            ?: throw IllegalArgumentException("finite authenticated party scope")
        fun boundedAmount(name:String,maximum:Long):Boolean {
            val n=body[name];val s=if(n?.isTextual==true)n.textValue() else return false
            return s.matches(Regex("[1-9][0-9]*")) && s.toLongOrNull()?.let { it<=maximum }==true
        }
        val occurred=body["occurredAt"]
        val invalidTime=occurred==null || !occurred.isTextual || occurred.textValue().toByteArray().size>128 ||
            !occurred.textValue().matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")) ||
            runCatching{java.time.Instant.parse(occurred.textValue())}.isFailure
        val invalidMetadata=listOf("actorId","traceId","causationId","correlationId","clientOrderId").any {key->
            body[key]?.let{!it.isTextual || it.textValue().toByteArray(Charsets.UTF_8).size>128}==true
        }
        val orderNumber=e.orderId.removePrefix("p0-").toIntOrNull()
        val policyRefusal=when {
            !e.orderId.matches(Regex("p0-[1-9][0-9]*")) || orderNumber==null || orderNumber>opening.budget.orderIds->"SOURCE_POLICY"
            listOf(e.actorId,e.traceId,e.causationId,e.correlationId,e.clientOrderId).any{it.toByteArray(Charsets.UTF_8).size>128} || invalidTime || invalidMetadata->"SOURCE_POLICY"
            e.commandType=="CancelOrder" && body["reason"]?.let{!it.isTextual || it.textValue().toByteArray().size>128}==true->"SOURCE_POLICY"
            e.commandType=="SubmitOrder" && (body["side"]?.textValue()!=(if(party.side==reef.contracts.orderexecution.v1.OrderSide.ORDER_SIDE_BUY)"BUY" else "SELL") ||
                body["orderType"]?.textValue()!="LIMIT" || body["timeInForce"]?.textValue()!="DAY" || body["currency"]?.textValue()!=opening.json()["quoteCurrency"].textValue())->"SOURCE_POLICY"
            e.commandType in listOf("SubmitOrder","ModifyOrder") && (!boundedAmount("quantityUnits",opening.budget.quantity) || !boundedAmount("limitPrice",opening.budget.price))->"SOURCE_POLICY"
            e.commandType !in listOf("SubmitOrder","ModifyOrder","CancelOrder")->"COMMAND_TYPE"
            else->null
        }
        val root=FiniteBindingContract.strictObject("{}".toByteArray(),2)
        listOf("scope" to e.scope,"clientId" to e.clientId,"idempotencyKey" to e.idempotencyKey,"participantId" to e.participantId,
            "commandId" to e.commandId,"commandType" to e.commandType,"route" to e.route,"subject" to e.subject,"key" to e.natsMessageId,
            "payload" to e.payloadJson,"payloadHash" to FiniteLifecycleContract.sha(bytes),"keyHash" to FiniteLifecycleContract.sha(e.natsMessageId.toByteArray()),
            "subjectHash" to FiniteLifecycleContract.sha(e.subject.toByteArray()),"routeHash" to FiniteLifecycleContract.sha(e.route.toByteArray()),
            "refusal" to (refusal ?: policyRefusal ?: "")).forEach { root.put(it.first,it.second) }
        root.put("partition",0);root.put("payloadBytes",bytes.size)
        return root
    }
}
