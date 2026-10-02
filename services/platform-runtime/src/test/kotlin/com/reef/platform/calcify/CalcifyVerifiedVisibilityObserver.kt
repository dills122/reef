package com.reef.platform.calcify

import com.reef.platform.api.JsonCodec
import com.reef.platform.api.JsonDocument
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/** Test-only live read-committed observer. Producer create-time is not committed visibility. */
object CalcifyVerifiedVisibilityObserver {
    private data class Mark(val records:Int,val nano:Long)
    private data class Start(val epochMs:Long,val durationMs:Long)
    internal class MillisecondLatencyHistogram(private val limitMs:Int=600000) {
        private val buckets=LongArray(limitMs+1)
        var count=0L;private set
        var overflow=0L;private set
        var maxNanos=0L;private set
        private fun ceilMs(n:Long)=n/1000000+if(n%1000000==0L) 0 else 1
        fun add(nanos:Long) {
            require(nanos>=0) {"negative matching-work-finished to visibility interval"}
            count++;maxNanos=maxOf(maxNanos,nanos)
            val bucket=ceilMs(nanos);if(bucket<=limitMs)buckets[bucket.toInt()]++ else overflow++
        }
        fun upper(percentile:Int):Long? {
            require(percentile in 1..100);if(count==0L)return null
            val rank=(count*percentile+99)/100;var cumulative=0L
            buckets.forEachIndexed {index,value->cumulative+=value;if(cumulative>=rank)return index.toLong()}
            return ceilMs(maxNanos)
        }
        fun result()=mapOf("samples" to count,"p50MsUpper" to upper(50),"p95MsUpper" to upper(95),"p99MsUpper" to upper(99),"maxMs" to maxNanos/1000000.0,"histogramCeilingMs" to limitMs,"overflowSamples" to overflow,"resolution" to "1ms ceiling buckets; overflow percentile upper is observed maximum")
    }
    internal fun matchingWorkFinishedNanos(root:JsonDocument):Long {
        val finished=root.strictTextField("workFinishedAt")
        val timing="reef-venue-batch-timing-v1\n"+root.strictTextField("payloadChecksum")+"\n"+finished
        val checksum=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(timing.toByteArray(Charsets.UTF_8)))
        require(root.strictTextField("timingChecksum")==checksum) {"matching timing checksum mismatch"}
        return nanos(Instant.parse(finished))
    }
    internal fun producerWorkDeltaNanos(producerEpochMs:Long,finishedNanos:Long):Long {
        require(producerEpochMs>=0) {"missing producer CreateTime"}
        val processing=Math.multiplyExact(producerEpochMs,1000000L)-finishedNanos
        require(processing>=-999999L) {"producer CreateTime before matching work beyond millisecond truncation"}
        return processing
    }
    internal fun batchEntryNanos(root:JsonDocument,finished:Long):Long {
        val entry=nanos(Instant.parse(root.strictTextField("createdAt")))
        require(entry<=finished) {"batch createdAt after matching workFinishedAt"}
        return entry
    }
    internal fun validateBaseline(expectedTrades:Int,expectedOrders:Int,sourceTrades:Long,sourceCommands:Long,sourceAcceptedOrders:Long,verifiedRecords:Int,resolvedRecords:Int) {
        require(expectedOrders>=0 && sourceTrades==expectedTrades.toLong() && verifiedRecords==expectedTrades && resolvedRecords==expectedTrades) {"baseline trade/output count mismatch"}
        require(sourceCommands==expectedOrders.toLong() && sourceAcceptedOrders==expectedOrders.toLong()) {"baseline source accepted command count mismatch"}
    }
    internal fun validateZeroTradeEndpoints(sourceCommitted:Map<Int,Long>,sourceRaw:Map<Int,Long>,verifiedCommitted:Map<Int,Long>,verifiedRaw:Map<Int,Long>,resolvedCommitted:Map<Int,Long>,resolvedRaw:Map<Int,Long>) {
        require(sourceCommitted==sourceRaw) {"source baseline has hidden uncommitted records"}
        require((verifiedCommitted.values+verifiedRaw.values+resolvedCommitted.values+resolvedRaw.values).all {it==0L}) {"zero-trade baseline contains output or uncommitted/aborted stage records"}
    }
    private fun nanos(t:Instant)=Math.addExact(Math.multiplyExact(t.epochSecond,1000000000L),t.nano.toLong())
    private fun ends(values:Map<TopicPartition,Long>)=values.mapKeys {it.key.partition().toString()}
    private fun id(record:ConsumerRecord<ByteArray,ByteArray>,generation:Int):CommitmentId {
        val passed=CalcifyWire.readPassed(requireNotNull(record.value()))
        require(passed.commitmentId.sourceGeneration==generation && passed.commitmentId.sourcePartition==record.partition() && passed.policyVersion==CalcifyWire.STUB_POLICY_VERSION) {"verified generation/lane/policy mismatch"}
        return passed.commitmentId
    }
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 8..9) {"bootstrap sourceTopic verifiedTopic resolvedTopic generation preflightContexts timeoutMs windowMs [preflightAcceptedOrders]"}
        val source=args[1];val verified=args[2];val resolved=args[3];val generation=args[4].toInt();val preflight=args[5].toInt();val timeout=args[6].toLong();val windowMs=args[7].toLong()
        val preflightOrders=if(args.size==9)args[8].toInt() else Math.multiplyExact(preflight,2)
        require(generation>0 && preflight in 0..1000 && preflightOrders>=0 && timeout>0 && windowMs>0)
        val props=Properties().apply {put("bootstrap.servers",args[0]);put("enable.auto.commit",false);put("isolation.level","read_committed");put("auto.offset.reset","none");put("allow.auto.create.topics",false);put("max.poll.records",1000);put("fetch.max.bytes",32*1024*1024);put("max.partition.fetch.bytes",16*1024*1024+1024)}
        val started=System.nanoTime();val deadline=started+TimeUnit.MILLISECONDS.toNanos(timeout)
        val lanes=mutableMapOf<Int,SourceLane>();val histogram=MillisecondLatencyHistogram();val completion=ActualMeasureCompletion()
        val batchWork=MillisecondLatencyHistogram();val entryVisibility=MillisecondLatencyHistogram()
        val producerAfterWork=MillisecondLatencyHistogram();val visibilityAfterProducer=MillisecondLatencyHistogram();var producerRoundingSamples=0L;var timestampDiagnosticOmitted=0L
        val observed=AtomicInteger(0);val mark=AtomicReference<Mark?>(null);val start=AtomicReference<Start?>(null);val commandError=AtomicReference<String?>(null)
        var count=0;var atDeadline=0L;var unclassified=0L;var first=0L;var last=0L;var priorWindow=started;var priorCount=0;var lastMark:Mark?=null
        var wall=nanos(Instant.now());var mono=System.nanoTime();var maxWallStep=0L;val timestampTypes=mutableSetOf<String>()
        try {
            AdminClient.create(props).use {admin->
                val identities=admin.describeTopics(listOf(source,verified,resolved)).allTopicNames().get(20,TimeUnit.SECONDS)
                val partitions=identities.getValue(source).partitions().map {it.partition()}.sorted()
                require(listOf(verified,resolved).all {partitions==identities.getValue(it).partitions().map {p->p.partition()}.sorted()}) {"partition sets differ"}
                val sourceParts=partitions.map {TopicPartition(source,it)};val verifiedParts=partitions.map {TopicPartition(verified,it)};val resolvedParts=partitions.map {TopicPartition(resolved,it)}
                KafkaConsumer(props,ByteArrayDeserializer(),ByteArrayDeserializer()).use {consumer->
                    consumer.assign(verifiedParts);verifiedParts.forEach {consumer.seek(it,0)}
                    val sourceEnds=consumer.endOffsets(sourceParts);val verifiedEnds=consumer.endOffsets(verifiedParts);val resolvedEnds=consumer.endOffsets(resolvedParts)
                    require(consumer.beginningOffsets(sourceParts+verifiedParts+resolvedParts).values.all {it==0L}) {"full retained offset0 required"}
                    if(preflight==0) {
                        val uncommittedProps=Properties().apply {putAll(props);put("isolation.level","read_uncommitted")}
                        KafkaConsumer(uncommittedProps,ByteArrayDeserializer(),ByteArrayDeserializer()).use {raw->
                            val rawEnds=raw.endOffsets(sourceParts+verifiedParts+resolvedParts)
                            fun laneEnds(values:Map<TopicPartition,Long>)=values.mapKeys {it.key.partition()}
                            validateZeroTradeEndpoints(laneEnds(sourceEnds),laneEnds(sourceParts.associateWith {rawEnds.getValue(it)}),laneEnds(verifiedEnds),laneEnds(verifiedParts.associateWith {rawEnds.getValue(it)}),laneEnds(resolvedEnds),laneEnds(resolvedParts.associateWith {rawEnds.getValue(it)}))
                        }
                    }
                    partitions.forEach {lanes[it]=SourceLane(props,source,identities.getValue(source).topicId().toString(),generation,it,deadline)}
                    val baseline=mutableSetOf<CommitmentId>()
                    while(verifiedParts.any {consumer.position(it)<verifiedEnds.getValue(it)}) {
                        check(System.nanoTime()<deadline) {"baseline timeout"}
                        for(record in consumer.poll(Duration.ofMillis(100))) {
                            require(record.offset()<verifiedEnds.getValue(TopicPartition(verified,record.partition()))) {"verified advanced during baseline"}
                            val target=id(record,generation);lanes.getValue(record.partition()).accept(target);require(baseline.add(target));count++
                        }
                    }
                    require(count==preflight) {"baseline verified$count != preflight$preflight"}
                    lanes.forEach {(partition,lane)->lane.finish(sourceEnds.getValue(TopicPartition(source,partition)),makerBaseline=preflight==0 && preflightOrders>0)}
                    require(lanes.values.sumOf {it.trades}==preflight.toLong()) {"baseline source count mismatch"}
                    var resolvedCount=0;val resolvedIds=mutableSetOf<CommitmentId>()
                    KafkaConsumer(props,ByteArrayDeserializer(),ByteArrayDeserializer()).use {initial->
                        initial.assign(resolvedParts);resolvedParts.forEach {initial.seek(it,0)}
                        while(resolvedParts.any {initial.position(it)<resolvedEnds.getValue(it)}) {
                            check(System.nanoTime()<deadline) {"baseline resolved timeout"}
                            for(record in initial.poll(Duration.ofMillis(100))) {
                                require(record.offset()<resolvedEnds.getValue(TopicPartition(resolved,record.partition())))
                                val key=CalcifyWire.readCommitment(record.key());require(key.sourceGeneration==generation && key.sourcePartition==record.partition() && key in baseline && resolvedIds.add(key));resolvedCount++
                            }
                        }
                    }
                    validateBaseline(preflight,preflightOrders,lanes.values.sumOf {it.trades},lanes.values.sumOf {it.commands},lanes.values.sumOf {it.acceptedOrders},count,resolvedCount)
                    require(consumer.endOffsets(sourceParts)==sourceEnds && consumer.endOffsets(verifiedParts)==verifiedEnds && consumer.endOffsets(resolvedParts)==resolvedEnds) {"topics advanced during baseline proof"}
                    observed.set(count)
                    println(JsonCodec.writeObject("ready" to mapOf("verifiedRecords" to count,"resolvedRecords" to resolvedCount,"sourceTrades" to lanes.values.sumOf {it.trades},"sourceAcceptedOrders" to lanes.values.sumOf {it.acceptedOrders},"sourceCommands" to lanes.values.sumOf {it.commands},"preflightAcceptedOrders" to preflightOrders,"preflightContexts" to preflight,"sourceTopicId" to identities.getValue(source).topicId().toString(),"verifiedTopicId" to identities.getValue(verified).topicId().toString(),"resolvedTopicId" to identities.getValue(resolved).topicId().toString(),"sourceCommittedEnds" to ends(sourceEnds),"verifiedCommittedEnds" to ends(verifiedEnds),"resolvedCommittedEnds" to ends(resolvedEnds))))
                    Thread {System.`in`.bufferedReader().forEachLine {line->
                        val seen=observed.get();val received=System.nanoTime()
                        when {
                            line=="mark"->mark.set(Mark(seen,received))
                            line.startsWith("finish")->completion.accept(line,received,seen)
                            line.startsWith("start ")-> {val p=line.split(' ');val epoch=p.getOrNull(1)?.toLongOrNull();val duration=p.getOrNull(2)?.toLongOrNull();if(p.size!=3 || epoch==null || duration==null || epoch<=0 || duration<=0 || epoch>Long.MAX_VALUE-duration || !start.compareAndSet(null,Start(epoch,duration))) commandError.compareAndSet(null,"invalid/duplicate start")}
                            else->commandError.compareAndSet(null,"unsupported command")
                        }
                    }}.apply {isDaemon=true;start()}
                    println("OBSERVER_READY");priorCount=count
                    while(System.nanoTime()<deadline && completion.awaiting(count) && commandError.get()==null) {
                        val records=consumer.poll(Duration.ofMillis(100));val visibleWall=nanos(Instant.now());val visibleMono=System.nanoTime()
                        maxWallStep=maxOf(maxWallStep,kotlin.math.abs((visibleWall-wall)-(visibleMono-mono)));wall=visibleWall;mono=visibleMono
                        for(record in records) {
                            val target=id(record,generation);val lane=lanes.getValue(record.partition());val finished=lane.accept(target)
                            batchWork.add(finished-lane.entryNanos);entryVisibility.add(visibleWall-lane.entryNanos)
                            histogram.add(visibleWall-finished);timestampTypes.add(record.timestampType().toString())
                            if(record.timestampType()==org.apache.kafka.common.record.TimestampType.CREATE_TIME) {
                                require(record.timestamp()>=0) {"missing producer CreateTime"}
                                val producerNanos=Math.multiplyExact(record.timestamp(),1000000L)
                                val processing=producerWorkDeltaNanos(record.timestamp(),finished)
                                if(processing<0)producerRoundingSamples++
                                producerAfterWork.add(processing.coerceAtLeast(0))
                                visibilityAfterProducer.add(visibleWall-producerNanos)
                            } else timestampDiagnosticOmitted++
                            count++;if(first==0L)first=visibleMono;last=visibleMono
                            val fixed=start.get();if(fixed==null)unclassified++ else if(visibleWall/1000000>=fixed.epochMs && visibleWall/1000000<fixed.epochMs+fixed.durationMs)atDeadline++
                            observed.set(count)
                        }
                        mark.getAndSet(null)?.let {lastMark=it;println(JsonCodec.writeObject("mark" to mapOf("records" to it.records,"verifiedRecords" to it.records,"elapsedMs" to (it.nano-started)/1000000)))}
                        val now=System.nanoTime();if(now-priorWindow>=windowMs*1000000) {println(JsonCodec.writeObject("window" to mapOf("records" to count,"verifiedRecords" to count,"elapsedMs" to (now-started)/1000000,"rate" to (count-priorCount)*1000000000.0/(now-priorWindow))));priorWindow=now;priorCount=count}
                    }
                    val finish=completion.finish();require(finish!=null && completion.error()==null && commandError.get()==null) {"invalid/missing finish/start"};require(count==finish.expected) {"verified$count != expected${finish.expected}"}
                    mark.getAndSet(null)?.let {lastMark=it;println(JsonCodec.writeObject("mark" to mapOf("records" to it.records,"verifiedRecords" to it.records,"elapsedMs" to (it.nano-started)/1000000)))}
                    val finalSource=consumer.endOffsets(sourceParts);val finalVerified=consumer.endOffsets(verifiedParts)
                    // Commit/abort markers advance offsets without user records. Drain those, rejecting any additional data.
                    while(verifiedParts.any {consumer.position(it)<finalVerified.getValue(it)}) {check(System.nanoTime()<deadline);require(consumer.poll(Duration.ofMillis(100)).isEmpty) {"extra verified records"}}
                    lanes.forEach {(partition,lane)->lane.finish(finalSource.getValue(TopicPartition(source,partition)))}
                    require(lanes.values.sumOf {it.trades}==count.toLong()) {"source/verified exact count mismatch"}
                    val finalIds=admin.describeTopics(listOf(source,verified,resolved)).allTopicNames().get(20,TimeUnit.SECONDS)
                    require(identities.all {(name,value)->value.topicId()==finalIds.getValue(name).topicId()}) {"topic recreated"}
                    require(consumer.endOffsets(sourceParts)==finalSource && consumer.endOffsets(verifiedParts)==finalVerified) {"source/verified advanced during final proof"}
                    val fixed=start.get()
                    println(JsonCodec.writeObject("result" to mapOf("pass" to true,"records" to count,"verifiedRecords" to count,"expected" to finish.expected,"unique" to count,"duplicates" to 0,"orderedExactSourceLinks" to true,"initialVerifiedRecords" to preflight,"initialResolvedRecords" to resolvedCount,"sourceTrades" to lanes.values.sumOf {it.trades},"sourceBatches" to lanes.values.sumOf {it.batches},"sourceAcceptedOrders" to lanes.values.sumOf {it.acceptedOrders},"sourceCommands" to lanes.values.sumOf {it.commands},"initialSourceAcceptedOrders" to preflightOrders,"sourceCommittedEnds" to ends(finalSource),"verifiedCommittedEnds" to ends(finalVerified),"latency" to histogram.result(),"batchEntryTiming" to mapOf("matchingWork" to batchWork.result(),"entryToVerifiedVisibilityUpper" to entryVisibility.result(),"semantics" to "trade-weighted batch createdAt before matching execution to workFinishedAt, and createdAt to read_committed verified poll-return visibility upper bound; conservative whole-batch path bound, not individual command matching acceptance latency; createdAt is metadata excluded from payload and timing checksums; workFinishedAt timing integrity checked"),"timestampDiagnostics" to mapOf("producerCreateTimeMinusWorkFinished" to if(producerAfterWork.count>0)producerAfterWork.result() else null,"visibilityMinusProducerCreateTime" to if(visibilityAfterProducer.count>0)visibilityAfterProducer.result() else null,"applicableCreateTimeSamples" to producerAfterWork.count,"producerMillisecondRoundingAdjustedSamples" to producerRoundingSamples,"unknownOrLogAppendTimeOmittedSamples" to timestampDiagnosticOmitted,"semantics" to "CreateTime only: producer creation minus matching work is processing/publication diagnostic, not durable latency; visibility minus producer creation includes transaction commit and observer delivery/processing, not pure observer lag; producer timestamp truncation permits negative work delta up to999999ns, clamped0 and counted"),"observerCost" to "all verified records plus full source checksum validation once per source batch; four bounded source readers; live CPU/fetch overhead included", "latencySemantics" to "batch matching workFinishedAt to observer poll-return read_committed verified visibility upper bound; includes checksum/source publication/extractor/verifier transaction/fetch/scheduling; not semantic acceptedAt, individual matching acceptance or producer timestamp","recordTimestampTypes" to timestampTypes.sorted(),"recordTimestampsUsedForLatency" to false,"clockAssumption" to "matching and observer wall clocks share local host; negative intervals fail; wall steps reported","maxObservedWallStepMs" to maxWallStep/1000000.0,"windowStartEpochMs" to fixed?.epochMs,"windowDurationMs" to fixed?.durationMs,"verifiedObservedWithinFixedWindow" to if(fixed!=null && unclassified==0L)atDeadline else null,"observationsBeforeStartCommand" to unclassified,"elapsedMs" to (System.nanoTime()-started)/1000000,"activeMs" to if(first==0L)0 else (last-first)/1000000,"finishElapsedMs" to (finish.receivedNanos-started)/1000000,"drainAfterFinishMs" to (last-finish.receivedNanos).coerceAtLeast(0)/1000000,"drainAfterMarkMs" to lastMark?.let {(last-it.nano).coerceAtLeast(0)/1000000})))
                }
            }
        } finally {lanes.values.forEach {it.close()}}
    }
    private class SourceLane(props:Properties,private val topic:String,private val topicId:String,private val generation:Int,private val partition:Int,private val deadline:Long):AutoCloseable {
        private val config=Properties().apply {putAll(props);put("max.poll.records",1)}
        private val consumer=KafkaConsumer(config,ByteArrayDeserializer(),ByteArrayDeserializer());private val tp=TopicPartition(topic,partition)
        private val pending=ArrayDeque<ConsumerRecord<ByteArray,ByteArray>>();private var offset=-1L;private var ordinal=0;private var size=0;private var finished=0L
        var entryNanos=0L;private set
        var commands=0L;private set
        var acceptedOrders=0L;private set
        var trades=0L;private set
        var batches=0L;private set
        init {consumer.assign(listOf(tp));consumer.seek(tp,0)}
        fun accept(id:CommitmentId):Long {
            require(id.sourceGeneration==generation && id.sourcePartition==partition)
            if(offset!=id.sourceOffset) {
                require(ordinal==size) {"missing verified ordinal"}
                while(offset<id.sourceOffset) {val record=next(id.sourceOffset+1) ?: error("missing committed source ${id.sourceOffset}");require(record.offset()<=id.sourceOffset && ordinal==size) {"source trade batch skipped"};decode(record)}
            }
            require(offset==id.sourceOffset && id.tradeOrdinal==ordinal && ordinal<size) {"verified duplicate/ordinal gap/source regression"}
            ordinal++;return finished
        }
        fun finish(end:Long,makerBaseline:Boolean=false) {require(ordinal==size) {"missing verified links at source frontier"};while(true) {val record=next(end) ?: break;decode(record,makerBaseline);require(size==0) {"source trade batch missing verified links"}}}
        private fun decode(record:ConsumerRecord<ByteArray,ByteArray>,makerBaseline:Boolean=false) {
            require(record.offset()>offset)
            val root=CalcifySourceBatch.checked(record.value(),topic,partition);finished=matchingWorkFinishedNanos(root);entryNanos=batchEntryNanos(root,finished)
            commands+=root.strictIntField("commandCount")
            acceptedOrders+=root.strictObjectDocuments("outcomes").count {it.strictTextField("commandType")=="SubmitOrder" && it.strictTextField("status")=="accepted"}.toLong()
            size=root.strictObjectDocuments("outcomes").sumOf {outcome->outcome.strictObject("result").strictObjectDocuments("trades",required=false).onEach {it.strictTextField("tradeId");it.strictTextField("executionId")}.size}
            if(makerBaseline) {
                val facts=CalcifyActualFullFactObserver.decode(record.value(),topic,topicId,generation,partition,record.offset())
                require(facts.trades.isEmpty() && facts.orders.size==root.strictIntField("commandCount") && facts.orders.all {it.fact.side==reef.contracts.orderexecution.v1.OrderSide.ORDER_SIDE_BUY}) {"seed baseline must contain only fully validated accepted BUY makers and no trades"}
            }
            offset=record.offset();ordinal=0;trades+=size;batches++
        }
        private fun next(end:Long):ConsumerRecord<ByteArray,ByteArray>? {
            while(pending.isEmpty()) {if(consumer.position(tp)>=end)return null;check(System.nanoTime()<deadline) {"source observation timeout"};consumer.poll(Duration.ofMillis(100)).forEach {pending.addLast(it)}}
            require(pending.first().offset()<end) {"source advanced beyond frozen/target frontier"};return pending.removeFirst()
        }
        override fun close()=consumer.close()
    }
}
