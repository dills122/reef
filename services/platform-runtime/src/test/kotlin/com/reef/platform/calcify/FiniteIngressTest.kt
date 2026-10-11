package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.EitherBoundaryError
import com.reef.platform.api.StreamCommandConfig
import com.reef.platform.api.StreamCommandEnvelope
import com.reef.platform.api.StreamCommandEnvelopeBuilder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Controller doubles only; SQL atomicity/permissions require separate real Postgres proof. */
class FiniteIngressTest {
    private class Store:FiniteBindingStore {
        var disposition="RESERVED";var authorized=true;var marker=true;var stopMarker=true
        var attempts=0;var ackCalls=0;var unknowns=0;val events=mutableListOf<String>()
        override fun snapshot(digest:String):ObjectNode=error("not used")
        override fun receipt(digest:String,commandId:String)=FinitePublishedReceipt(commandId,1,"2026-10-10T03:00:00Z")
        override fun beginAttempt(digest:String,epoch:Long,request:ObjectNode):FiniteIngressDecision {
            attempts++;events+="reserve";return FiniteIngressDecision(if(request["refusal"].textValue().isNotEmpty())"REFUSED" else disposition,request["commandId"].textValue(),if(disposition=="PUBLISHED")1 else null)
        }
        override fun authorizeSend(digest:String,epoch:Long,commandId:String):Boolean {events+="authorize";return authorized}
        override fun acknowledge(digest:String,commandId:String,originalEpoch:Long,offset:Long,sequence:Long):Boolean {
            assertEquals(0,offset);assertEquals(1,sequence);ackCalls++;events+="mark";return marker
        }
        override fun unknown(digest:String,commandId:String,originalEpoch:Long):Boolean {unknowns++;events+="unknown-stop";return stopMarker}
        override fun stop(digest:String,epoch:Long)=true
        override fun claimOwner(digest:String,expectedEpoch:Long,instance:String)=expectedEpoch+1
    }
    private fun envelope(command:String="command-1",participant:String="buyer",account:String="buyer-account"):StreamCommandEnvelope {
        val body="""{"runId":"p3-run","venueSessionId":"p3-session","instrumentId":"AAPL","commandId":"$command","orderId":"p0-1","actorId":"buyer","occurredAt":"2026-10-10T03:00:00Z","participantId":"$participant","accountId":"$account","side":"BUY","orderType":"LIMIT","quantityUnits":"1","limitPrice":"10","timeInForce":"DAY","currency":"USD"}"""
        return (StreamCommandEnvelopeBuilder.fromRequest("buyer",route="/api/v1/orders/submit",idempotencyKey=command,body=body,
            config=StreamCommandConfig(partitionCount=1)) as EitherBoundaryError.Envelope).envelope
    }
    @Test fun noSuccessBeforeAckAndDurableMarker() {
        val store=Store();val ack=CompletableFuture<FiniteBrokerAck>();val scheduler=Executors.newSingleThreadScheduledExecutor()
        try {
            val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{store.events+="send";ack},scheduler,10000)
            val response=ingress.submit(envelope());assertFalse(response.isDone)
            assertEquals(listOf("reserve","authorize","send"),store.events)
            ack.complete(FiniteBrokerAck(FiniteBindingFixtures.opening().topic("COMMAND").name,0,0))
            assertEquals(202,response.get(1,TimeUnit.SECONDS).status);assertEquals("mark",store.events.last());assertEquals(1,store.ackCalls)
        }finally{scheduler.shutdownNow()}
    }
    @Test fun timeoutStopsDurablyAndLateAckRepairsWithoutChanging503OrResending() {
        val store=Store();val ack=CompletableFuture<FiniteBrokerAck>();val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
        try {
            val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{sends++;ack},scheduler,20)
            val response=ingress.submit(envelope());assertEquals(503,response.get(2,TimeUnit.SECONDS).status)
            assertEquals("unknown-stop",store.events.last());assertEquals(1,store.unknowns)
            ack.complete(FiniteBrokerAck(FiniteBindingFixtures.opening().topic("COMMAND").name,0,0))
            assertEquals(1,store.ackCalls);assertEquals(503,response.get().status)
            assertEquals(503,ingress.submit(envelope()).get().status);assertEquals(1,sends);assertEquals(1,store.attempts)
        }finally{scheduler.shutdownNow()}
    }
    @Test fun failureToMarkAckOrStopCannotReturn202() {
        for(durableStop in listOf(true,false)) {
            val store=Store().apply{marker=false;stopMarker=durableStop};val scheduler=Executors.newSingleThreadScheduledExecutor()
            try {
                val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{CompletableFuture.completedFuture(FiniteBrokerAck(FiniteBindingFixtures.opening().topic("COMMAND").name,0,0))},scheduler,1000)
                val r=ingress.submit(envelope()).get();assertEquals(503,r.status)
                assertEquals(if(durableStop)"ACK_NOT_DURABLE" else "STOP_NOT_DURABLE",r.disposition);assertEquals(1,store.unknowns)
            }finally{scheduler.shutdownNow()}
        }
    }
    @Test fun wrongBrokerTopicPartitionOrOffsetFaults() {
        for(ack in listOf(FiniteBrokerAck("wrong",0,0),FiniteBrokerAck("CALCIFY_P3_COMMANDS",1,0),FiniteBrokerAck("CALCIFY_P3_COMMANDS",0,-1))) {
            val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor()
            try {
                val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{CompletableFuture.completedFuture(ack)},scheduler,1000)
                assertEquals(503,ingress.submit(envelope()).get().status);assertEquals(0,store.ackCalls);assertEquals(1,store.unknowns)
            }finally{scheduler.shutdownNow()}
        }
    }
    @Test fun uncertaintyAndDuplicateNeverResendPublishedReceiptReplays() {
        for(disposition in listOf("UNRESOLVED","CONFLICT","REFUSED","STOPPED","PUBLISHED")) {
            val store=Store().apply{this.disposition=disposition};val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
            try {
                val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{sends++;error("must not send")},scheduler,1000)
                val response=ingress.submit(envelope()).get();assertEquals(if(disposition=="PUBLISHED")202 else if(disposition=="CONFLICT")409 else if(disposition=="REFUSED")422 else 503,response.status)
                assertEquals(0,sends);assertEquals(1,store.attempts)
            }finally{scheduler.shutdownNow()}
        }
    }
    @Test fun schedulingOrSynchronousProducerFailureAfterAuthorizationIsUnknown() {
        for(schedulerFailure in listOf(true,false)) {
            val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor();if(schedulerFailure)scheduler.shutdownNow()
            try {
                val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{throw IllegalStateException("ambiguous producer failure")},scheduler,1000)
                assertEquals(503,ingress.submit(envelope()).get().status);assertEquals(1,store.unknowns);assertEquals(0,ingress.pending())
            }finally{scheduler.shutdownNow()}
        }
    }
    @Test fun wrongPartyOrRevokedEpochNeverSends() {
        val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
        try {
            val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{sends++;error("must not send")},scheduler,1000)
            assertEquals(400,ingress.submit(envelope(participant="other")).get().status);assertEquals(0,store.attempts)
            store.authorized=false;assertEquals(503,ingress.submit(envelope()).get().status);assertEquals(0,sends)
        }finally{scheduler.shutdownNow()}
    }

    @Test fun laneSerializationPreventsSecondReservationOvertakingFirstEnqueue() {
        val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor();val callers=Executors.newFixedThreadPool(2)
        val reached=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val sent=java.util.Collections.synchronizedList(mutableListOf<String>())
        val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{sent.add(it.commandId);CompletableFuture()},scheduler,10000,
            FiniteIngressObserver{boundary,id,_->if(boundary==FiniteIngressBoundary.RESERVED && id=="first"){reached.countDown();assertTrue(release.await(2,TimeUnit.SECONDS))}})
        try {
            val first=callers.submit<CompletableFuture<FiniteIngressResult>>{ingress.submit(envelope("first"))};assertTrue(reached.await(1,TimeUnit.SECONDS))
            val second=callers.submit<CompletableFuture<FiniteIngressResult>>{ingress.submit(envelope("second"))}
            Thread.sleep(30);assertTrue(sent.isEmpty());assertEquals(1,store.attempts)
            release.countDown();first.get(1,TimeUnit.SECONDS);second.get(1,TimeUnit.SECONDS);assertEquals(listOf("first","second"),sent)
        }finally{release.countDown();callers.shutdownNow();scheduler.shutdownNow()}
    }
    @Test fun realCallbackPauseAllowsTimeoutBeforeDurableLateRepair() {
        val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor();val callback=Executors.newSingleThreadExecutor()
        val ack=CompletableFuture<FiniteBrokerAck>();val reached=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{ack},scheduler,30,
            FiniteIngressObserver{boundary,_,_->if(boundary==FiniteIngressBoundary.BROKER_ACK){reached.countDown();assertTrue(release.await(2,TimeUnit.SECONDS))}})
        try {
            val response=ingress.submit(envelope());val completion=callback.submit{ack.complete(FiniteBrokerAck(FiniteBindingFixtures.opening().topic("COMMAND").name,0,0))}
            assertTrue(reached.await(1,TimeUnit.SECONDS));assertEquals(503,response.get(1,TimeUnit.SECONDS).status);assertEquals(1,store.unknowns)
            release.countDown();completion.get(1,TimeUnit.SECONDS);assertEquals(1,store.ackCalls);assertEquals(503,response.get().status)
        }finally{release.countDown();callback.shutdownNow();scheduler.shutdownNow()}
    }

    private fun mutation(route:String,body:String):StreamCommandEnvelope = (StreamCommandEnvelopeBuilder.fromRequest("buyer",route=route,
        idempotencyKey="canonical-mutation",body=body,config=StreamCommandConfig(partitionCount=1)) as EitherBoundaryError.Envelope).envelope
    @Test fun canonicalModifyAndCancelUseSameRawDomainFields() {
        for((route,fields) in listOf("/api/v1/orders/modify" to "\"quantityUnits\":\"2\",\"limitPrice\":\"20\"",
            "/api/v1/orders/cancel" to "\"reason\":\"canonical cancel\"")) {
            val body="""{"runId":"p3-run","venueSessionId":"p3-session","instrumentId":"AAPL","commandId":"canonical-mutation","orderId":"p0-1","actorId":"buyer","occurredAt":"2026-10-10T03:00:00.123456789Z","participantId":"buyer","accountId":"buyer-account",$fields}"""
            val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
            try {
                val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{sends++;CompletableFuture.completedFuture(FiniteBrokerAck(FiniteBindingFixtures.opening().topic("COMMAND").name,0,0))},scheduler,1000)
                assertEquals(202,ingress.submit(mutation(route,body)).get().status);assertEquals(1,sends);assertEquals(1,store.attempts)
            }finally{scheduler.shutdownNow()}
        }
    }
    @Test fun sourcePolicyRefusalsChargeAttemptButNeverPublishUnrepresentableFacts() {
        val original=envelope().payloadJson
        val cases=listOf(original.replace("\"LIMIT\"","\"LIMIT_HIDDEN\""),original.replace("\"p0-1\"","\"p0-9\""),
            original.replace("\"quantityUnits\":\"1\"","\"quantityUnits\":\"0\""),
            original.replace("2026-10-10T03:00:00Z","invalid-time"),
            original.replace("\"occurredAt\":\"2026-10-10T03:00:00Z\",",""),
            original.dropLast(1)+",\"correlationId\":null}",original.dropLast(1)+",\"traceId\":\""+"t".repeat(129)+"\"}")
        for(body in cases) {
            val store=Store();val scheduler=Executors.newSingleThreadScheduledExecutor();var sends=0
            try {
                val ingress=FiniteIngress(FiniteBindingFixtures.opening(),1,store,FiniteCommandPublisher{sends++;error("must refuse before send")},scheduler,1000)
                assertEquals(422,ingress.submit(mutation("/api/v1/orders/submit",body)).get().status);assertEquals(1,store.attempts);assertEquals(0,sends)
            }finally{scheduler.shutdownNow()}
        }
    }
}
