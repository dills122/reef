package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.JsonCodec
import com.reef.platform.api.JsonDocument
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.*
import org.apache.kafka.streams.*
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import reef.contracts.calcify.v1.MatchContextResolvedV1

/** Test-only external process harness; production classes own resolution/state/source reads. */
object CalcifyResolverBrokerProbe {
    private data class SourceWave(val wave: Int, val partition: Int, val checksum: String, val bytes: Int)
    private data class MeasurementMark(val records: Int, val receivedNanos: Long)
    private val mapper=JsonMapper.builder().build()
    private val excluded=setOf("createdAt","workFinishedAt","timingChecksum","payloadChecksum","payloadChecksumAlgorithm")
    private val idFields=setOf("batchId","commandId","eventId","orderId","engineOrderId","clientOrderId","buyOrderId","sellOrderId","tradeId","executionId","participantId","accountId")
    @JvmStatic fun main(args:Array<String>) {
        val mode=args[0];val broker=args[1];val prefix=args[2]
        val props=Properties().apply {put("bootstrap.servers",broker)}
        val source="$prefix-source";val verified="$prefix-verified";val output=if(mode=="actual-measure") prefix else "$prefix-resolved"
        when(mode) {
            "init"->AdminClient.create(props).use {admin->admin.createTopics(listOf(source,verified,output).map {NewTopic(it,2,3.toShort()).configs(mapOf("cleanup.policy" to "delete","write.caching" to "false","segment.bytes" to "33554432","max.message.bytes" to "16778240"))}).all().get(20,TimeUnit.SECONDS)}
            "seed"->{
                val wave=args[3].toInt();val partition=args[4].toInt()
                KafkaProducer(props,ByteArraySerializer(),ByteArraySerializer()).use {producer->
                    for(original in Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines()) {
                        val node=mapper.readTree(original) as ObjectNode
                        suffix(node,"-w$wave-p$partition")
                        node.put("partition",partition);node.put("eventStream",source)
                        val altered=mapper.writeValueAsString(node)
                        node.put("payloadChecksum",JsonCodec.parseObject(altered).semanticSha256(excluded))
                        val body=mapper.writeValueAsBytes(node)
                        val metadata=producer.send(ProducerRecord(source,partition,null,body)).get(20,TimeUnit.SECONDS)
                        val ids=CalcifySourceBatch.extract(body.toString(Charsets.UTF_8),source,1,partition,metadata.offset())
                        for(id in ids) producer.send(ProducerRecord(verified,partition,CalcifyWire.commitment(id),CalcifyWire.passed(CommitmentVerificationPassed(id,1))))
                    }
                    producer.flush()
                }
            }
            "paired-seed" -> {
                val waves=args[3].toInt();val shape=args[4];val firstWave=args.getOrNull(5)?.toInt() ?: 0;val pace=args.getOrNull(6)?.toInt() ?: 0
                val maxInFlight=args.getOrNull(7)?.toInt() ?: System.getenv("CALCIFY_PAIRED_SOURCE_IN_FLIGHT")?.toInt() ?: 16
                require(maxInFlight in 1..16) { "source in-flight bound must be between1 and16" }
                val originals=Path.of("../../docs/evidence/calcify-phase2-implementation/paired-source-fixture.jsonl").readLines()
                val config=Properties().apply {putAll(props);put("acks","all");put("compression.type","lz4");put("linger.ms",5);put("buffer.memory",64*1024*1024)}
                var bytes=0L;val epoch=System.currentTimeMillis();val started=System.nanoTime()
                val verificationFailure=java.util.concurrent.atomic.AtomicReference<Exception?>(null)
                var peakPending=0
                println(JsonCodec.writeObject("configuration" to mapOf("maxInFlightSourceSends" to maxInFlight,"sourceAcknowledgements" to "durable FIFO before verification")))
                KafkaProducer(config,ByteArraySerializer(),ByteArraySerializer()).use {producer->
                    val pending=BoundedSourceAcknowledgements<SourceWave,RecordMetadata>(maxInFlight) { wave,metadata ->
                        println(JsonCodec.writeObject("source" to mapOf("wave" to wave.wave,"partition" to wave.partition,"offset" to metadata.offset(),"checksum" to wave.checksum,"bytes" to wave.bytes)))
                        repeat(100) {ordinal->
                            val id=CommitmentId(1,wave.partition,metadata.offset(),ordinal)
                            producer.send(ProducerRecord(verified,wave.partition,CalcifyWire.commitment(id),CalcifyWire.passed(CommitmentVerificationPassed(id,1)))) {_,failure->
                                if(failure!=null) verificationFailure.compareAndSet(null,failure)
                            }
                        }
                        verificationFailure.get()?.let {throw IllegalStateException("verification publication failed",it)}
                    }
                    repeat(waves) {index ->
                        val wave=firstWave+index
                        if(pace>0) {val remaining=started+index*1_000_000_000L/pace-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining)}
                        val partition=when(shape) {"hot","aged"->0;"spread"->wave%2;"skew"->if(wave%10==0) 1 else 0;else->error("unknown shape $shape")}
                        for(original in originals) {
                            val node=mapper.readTree(original) as ObjectNode;suffix(node,"-w$wave-p$partition")
                            scopeInstrument(node,partition)
                            node.put("partition",partition);node.put("eventStream",source)
                            node.put("firstSequence",wave.toLong()*200+1);node.put("lastSequence",wave.toLong()*200+200)
                            node.get("outcomes").forEachIndexed {index,row->(row as ObjectNode).put("streamSequence",wave.toLong()*200+index+1)}
                            node.put("payloadChecksum",JsonDocument(node).semanticSha256(excluded))
                            val body=mapper.writeValueAsBytes(node);bytes+=body.size
                            pending.add(SourceWave(wave,partition,node.get("payloadChecksum").asText(),body.size),producer.send(ProducerRecord(source,partition,null,body)))
                        }
                    }
                    pending.drain()
                    peakPending=pending.peakPending
                    producer.flush()
                    verificationFailure.get()?.let {throw IllegalStateException("verification publication failed",it)}
                }
                println(JsonCodec.writeObject("startedEpochMs" to epoch,"paceWavesPerSecond" to pace,"waves" to waves,"trades" to waves*100,"bytes" to bytes,"maxInFlightSourceSends" to maxInFlight,"peakPendingSourceSends" to peakPending,"elapsedMs" to (System.nanoTime()-started)/1_000_000))
            }
            "paired-oracle" -> {
                val expected=args[3].toInt()
                val uuid=AdminClient.create(props).use {it.describeTopics(listOf(source)).allTopicNames().get().getValue(source).topicId().toString()}
                val settings=ResolverSettings(1,source,uuid,verified,output)
                val readers=mutableMapOf<Int,BrokerVenueSourceReader>()
                val batches=mutableMapOf<Int,MatchContextResolver.Batch>()
                val accepted=mutableMapOf<Int,Map<String,reef.contracts.calcify.v1.AcceptedOrderSourceV1>>()
                val cursors=mutableMapOf<Int,Long>();val frontiers=mutableMapOf<Int,CommitmentId>()
                val hashes=mutableMapOf<Int,java.security.MessageDigest>()
                val config=Properties().apply {putAll(props);put("enable.auto.commit",false);put("isolation.level","read_committed");put("auto.offset.reset","earliest")}
                var count=0
                try {
                    KafkaConsumer(config,ByteArrayDeserializer(),ByteArrayDeserializer()).use {consumer->
                        val partitions=consumer.partitionsFor(output).map {TopicPartition(output,it.partition())};consumer.assign(partitions);consumer.seekToBeginning(partitions)
                        val end=consumer.endOffsets(partitions);val deadline=System.nanoTime()+900_000_000_000L
                        while(System.nanoTime()<deadline && partitions.any {consumer.position(it)<end.getValue(it)}) {
                            for(record in consumer.poll(Duration.ofMillis(100))) {
                                val id=CalcifyWire.readCommitment(record.key());val prior=frontiers[record.partition()]
                                require(id.sourcePartition==record.partition() && id.tradeOrdinal==(if(prior==null || prior.tradeOrdinal==99) 0 else prior.tradeOrdinal+1)) {"paired output ordinal gap/duplicate"}
                                require(id.sourceOffset==(if(prior==null) 0 else prior.sourceOffset+if(prior.tradeOrdinal==99) 1 else 0)) {"paired output offset gap/duplicate"}
                                if(cursors[record.partition()]!=id.sourceOffset) {
                                    val reader=readers.getOrPut(record.partition()) {BrokerVenueSourceReader(broker,settings,record.partition())}
                                    var entry:VenueSourceEntry?=null
                                    val readDeadline=System.nanoTime()+10_000_000_000L
                                    while(entry==null && System.nanoTime()<readDeadline) entry=reader.next(cursors[record.partition()] ?: -1,id.sourceOffset)
                                    requireNotNull(entry) {"paired source timeout"};require(entry.offset==id.sourceOffset)
                                    batches[record.partition()]=MatchContextResolver.parseBatch(entry.payload.toString(Charsets.UTF_8),source,uuid,1,record.partition(),entry.offset)
                                    accepted[record.partition()]=batches.getValue(record.partition()).acceptedOrders.associateBy {it.fact.orderId}
                                    cursors[record.partition()]=entry.offset
                                }
                                val batch=batches.getValue(record.partition());require(batch.acceptedOrders.size==200 && batch.trades.size==100)
                                val trade=batch.trades[id.tradeOrdinal]
                                val orders=accepted.getValue(record.partition())
                                val actual=MatchContextResolvedV1.parseFrom(record.value())
                                val oracle=MatchContextResolver.resolve(CommitmentVerificationPassed(id,1),trade,orders[trade.fact.buyOrderId],orders[trade.fact.sellOrderId])
                                require(actual==oracle) {"paired full-fact parity mismatch"}
                                hashes.getOrPut(record.partition()) {java.security.MessageDigest.getInstance("SHA-256")}.apply {update(record.key());update(java.nio.ByteBuffer.allocate(4).putInt(record.value().size).array());update(record.value())}
                                frontiers[record.partition()]=id;count++
                            }
                        }
                    }
                } finally {readers.values.forEach {it.close()}}
                println(JsonCodec.writeObject("result" to mapOf("sourceTopicId" to uuid,"partitionSha256" to hashes.toSortedMap().mapValues {java.util.HexFormat.of().formatHex(it.value.digest())},"records" to count,"expected" to expected,"fullFactParity" to true,"orderedUnique" to true,"pass" to (count==expected))))
            }
            "measure", "actual-measure" -> {
                val expected=args[3].toInt();val timeout=args[4].toLong();val tailOnly=args.getOrNull(5)=="true";val windowMs=args.getOrNull(6)?.toLong() ?: 5000L
                val expectedGeneration=args.getOrNull(7)?.toInt() ?: 1
                val config=Properties().apply {putAll(props);put("enable.auto.commit",false);put("isolation.level","read_committed");put("auto.offset.reset","earliest");put("fetch.max.bytes",32*1024*1024)}
                KafkaConsumer(config,ByteArrayDeserializer(),ByteArrayDeserializer()).use {consumer->
                    val partitions=consumer.partitionsFor(output).map {TopicPartition(output,it.partition())};consumer.assign(partitions);if(tailOnly) consumer.seekToEnd(partitions) else consumer.seekToBeginning(partitions)
                    partitions.forEach {consumer.position(it)}
                    val epoch=System.currentTimeMillis();val started=System.nanoTime();var first=0L;var last=0L;var count=0;var bytes=0L
                    val marker=java.util.concurrent.atomic.AtomicReference<MeasurementMark?>(null)
                    val observedCount=java.util.concurrent.atomic.AtomicInteger(0)
                    val completion=if(mode=="actual-measure") ActualMeasureCompletion() else null
                    Thread {System.`in`.bufferedReader().forEachLine {line->
                        val observed=observedCount.get();val received=System.nanoTime()
                        if(line=="mark") marker.set(MeasurementMark(observed,received))
                        else if(line.startsWith("finish")) completion?.accept(line,received,observed)
                    }}.apply {isDaemon=true;start()}
                    println("OBSERVER_READY")
                    val keys=mutableSetOf<String>();var valid=true;val windows=mutableListOf<Map<String,Any>>();var previous=started;var previousCount=0
                    var printedFinish=false;var lastMark:MeasurementMark?=null
                    while((System.nanoTime()-started)/1_000_000<timeout && (completion?.awaiting(count) ?: (count<expected))) {
                        for(record in consumer.poll(Duration.ofMillis(100))) {
                            val now=System.nanoTime();if(first==0L)first=now;last=now;count++;bytes+=record.value().size;keys.add(key(record.key()))
                            val row=MatchContextResolvedV1.parseFrom(record.value());val id=CalcifyWire.readCommitment(record.key())
                            valid=valid && id.sourceGeneration==expectedGeneration && row.commitment.sourceGeneration==expectedGeneration && row.commitment.sourceOffset==id.sourceOffset && row.commitment.tradeOrdinal==id.tradeOrdinal && row.commitment.sourcePartition==record.partition() && row.buyAcceptedOrder.fact.orderId==row.trade.fact.buyOrderId && row.sellAcceptedOrder.fact.orderId==row.trade.fact.sellOrderId
                            observedCount.set(count)
                        }
                        val now=System.nanoTime()
                        marker.getAndSet(null)?.let {mark->lastMark=mark;println(JsonCodec.writeObject("mark" to mapOf("records" to mark.records,"elapsedMs" to (mark.receivedNanos-started)/1_000_000)))}
                        completion?.finish()?.let {finish->if(!printedFinish) {printedFinish=true;println(JsonCodec.writeObject("finish" to mapOf("records" to finish.observedRecords,"expected" to finish.expected,"elapsedMs" to (finish.receivedNanos-started)/1_000_000)))}}
                        if(now-previous>=windowMs*1_000_000L) {
                            val window=mapOf("elapsedMs" to (now-started)/1_000_000,"records" to count,"rate" to (count-previousCount)*1_000_000_000.0/(now-previous));windows.add(window);println(JsonCodec.writeObject("window" to window));previous=now;previousCount=count
                        }
                    }
                    marker.getAndSet(null)?.let {mark->lastMark=mark;println(JsonCodec.writeObject("mark" to mapOf("records" to mark.records,"elapsedMs" to (mark.receivedNanos-started)/1_000_000)))}
                    val finish=completion?.finish();val actualExpected=finish?.expected ?: expected
                    if(finish!=null && !printedFinish) println(JsonCodec.writeObject("finish" to mapOf("records" to finish.observedRecords,"expected" to finish.expected,"elapsedMs" to (finish.receivedNanos-started)/1_000_000)))
                    println(JsonCodec.writeObject("result" to mapOf("startedEpochMs" to epoch,"expected" to actualExpected,"offeredExpected" to expected,"records" to count,"unique" to keys.size,"duplicates" to count-keys.size,"identityChecks" to valid,"outputBytes" to bytes,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"activeMs" to (if(first==0L)0 else (last-first)/1_000_000),"durableRate" to (if(last>first)count*1_000_000_000.0/(last-first) else 0),"finishReceived" to (finish!=null),"finishError" to completion?.error(),"finishElapsedMs" to finish?.let {(it.receivedNanos-started)/1_000_000},"drainAfterFinishMs" to finish?.let {(last-it.receivedNanos).coerceAtLeast(0)/1_000_000},"drainAfterMarkMs" to lastMark?.let {(last-it.receivedNanos).coerceAtLeast(0)/1_000_000},"pass" to (count==actualExpected && keys.size==actualExpected && valid && (completion==null || (finish!=null && completion.error()==null))),"windows" to windows)))
                }
            }
            "poison"->KafkaProducer(props,ByteArraySerializer(),ByteArraySerializer()).use {producer->
                repeat(1000) {val id=CommitmentId(2,0,1000L+it,0);producer.send(ProducerRecord(verified,0,CalcifyWire.commitment(id),CalcifyWire.passed(CommitmentVerificationPassed(id,1))))};producer.flush()
            }
            "worker"->{
                val app=args[3];val dir=args[4];val crash=args.getOrNull(5) ?: ""
                val uuid=AdminClient.create(props).use {it.describeTopics(listOf(source)).allTopicNames().get().getValue(source).topicId().toString()}
                val settings=ResolverSettings(1,source,uuid,verified,output)
                val topology=CalcifyResolverProcessor.topology(settings,{partition->
                    val actual=BrokerVenueSourceReader(broker,settings,partition)
                    object:VenueSourceReader {
                        private var read=0
                        override fun next(cursor:Long,target:Long):VenueSourceEntry? {
                            if(crash=="state" && read==1) Runtime.getRuntime().halt(91)
                            return actual.next(cursor,target).also {if(it!=null) read++}
                        }
                        override fun validateIdentity()=actual.validateIdentity()
                        override fun close()=actual.close()
                    }
                },{partition,blocked->ResolverConsumerGate.blocked(verified,partition,blocked)},{partition,stats->
                    println(JsonCodec.writeObject("partition" to partition,"stats" to stats))
                    if(crash=="commit" && (stats["resolvedCount"] as? Long ?: 0)>0) Runtime.getRuntime().halt(91)
                })
                if(crash=="forward") topology.addProcessor("test-crash",{object:Processor<ByteArray,ByteArray,ByteArray,ByteArray> {
                    override fun init(context:ProcessorContext<ByteArray,ByteArray>) {}
                    override fun process(record:Record<ByteArray,ByteArray>) {Runtime.getRuntime().halt(91)}
                    override fun close() {}
                }},"resolve")
                val config=Properties().apply {
                    putAll(props);put("application.id",app);put("state.dir",dir);put("processing.guarantee","exactly_once_v2")
                    put("num.stream.threads",args.getOrNull(6)?.toInt() ?: 1);put("replication.factor",3);put("topic.write.caching","false");put("topic.segment.bytes",33554432);put("topic.max.message.bytes",settings.maxTargetBytes+1024);put("num.standby.replicas",1)
                    put("producer.batch.size",128*1024);put("producer.linger.ms",20);put("producer.compression.type","lz4");put("producer.max.request.size",16*1024*1024+1024)
                    put("commit.interval.ms",100);put("statestore.cache.max.bytes",8*1024*1024L)
                    put("rocksdb.config.setter",ResolverRocksConfig::class.java);put("consumer.max.poll.records",100)
                    put("consumer.session.timeout.ms",6000);put("consumer.heartbeat.interval.ms",1000);put("consumer.auto.offset.reset","earliest");put("consumer.allow.auto.create.topics",false)
                }
                val streams=KafkaStreams(topology,config,ResolverConsumerGate());val latch=CountDownLatch(1)
                streams.setStateListener {next,old->println("STATE $old -> $next");if(next==KafkaStreams.State.ERROR || next==KafkaStreams.State.NOT_RUNNING) latch.countDown()}
                streams.setUncaughtExceptionHandler {error->error.printStackTrace();org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT}
                Runtime.getRuntime().addShutdownHook(Thread {streams.close(Duration.ofSeconds(8));latch.countDown()})
                streams.start();latch.await()
                check(streams.state()!=KafkaStreams.State.ERROR)
            }
            "observe", "raw-observe"->{
                val expected=args[3].toInt()
                val sourceRows=if(mode=="observe") read(props,source) else emptyList();val outputs=read(props,output)
                val uuid=AdminClient.create(props).use {it.describeTopics(listOf(source)).allTopicNames().get().getValue(source).topicId().toString()}
                val oracle=mutableMapOf<String,MatchContextResolvedV1>()
                val orders=mutableMapOf<String,reef.contracts.calcify.v1.AcceptedOrderSourceV1>()
                for(record in (if(outputs.isNotEmpty()) sourceRows else emptyList()).sortedWith(compareBy({it.partition()},{it.offset()}))) {
                    val batch=MatchContextResolver.parseBatch(record.value().toString(Charsets.UTF_8),source,uuid,1,record.partition(),record.offset())
                    for(row in batch.acceptedOrders) {val key="${record.partition()}:${row.fact.orderId}";orders[key]=MatchContextResolver.mergeAcceptance(orders[key],row)}
                    for((ordinal,trade) in batch.trades.withIndex()) {
                        val id=CommitmentId(1,record.partition(),record.offset(),ordinal)
                        oracle[key(CalcifyWire.commitment(id))]=MatchContextResolver.resolve(CommitmentVerificationPassed(id,1),trade,orders["${record.partition()}:${trade.fact.buyOrderId}"],orders["${record.partition()}:${trade.fact.sellOrderId}"])
                    }
                }
                val unique=outputs.map {key(it.key())}.toSet().size
                val parity=mode=="raw-observe" || outputs.all {record->oracle[key(record.key())]==MatchContextResolvedV1.parseFrom(record.value())}
                val result=mapOf("outputHash" to java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(outputs.flatMap {it.value().asIterable()}.toByteArray())), "records" to outputs.size,"unique" to unique,"duplicates" to outputs.size-unique,"fullFactParity" to parity,"expected" to expected,"pass" to (outputs.size==expected && unique==expected && parity))
                println(JsonCodec.writeObject("result" to result))
            }
            "cleanup" -> {
                require(prefix.startsWith("p2-sus-")) {"cleanup restricted to owned sustained probe namespace"}
                AdminClient.create(props).use {admin->
                    val owned=admin.listTopics().names().get().filter {it==source || it==verified || it==output || it=="$prefix-app-resolver-changelog"}
                    admin.deleteTopics(owned).all().get(20,TimeUnit.SECONDS)
                    println(JsonCodec.writeObject("deletedTopics" to owned.sorted()))
                }
            }
            "retention"->AdminClient.create(props).use {admin->admin.deleteRecords(mapOf(TopicPartition(source,0) to RecordsToDelete.beforeOffset(1))).all().get()}
            "recreate"->AdminClient.create(props).use {admin->admin.deleteTopics(listOf(source)).all().get();Thread.sleep(1000);admin.createTopics(listOf(NewTopic(source,2,3.toShort()).configs(mapOf("write.caching" to "false")))).all().get()}
            else->error("unsupported mode $mode")
        }
    }
    private fun key(bytes:ByteArray)=java.util.HexFormat.of().formatHex(bytes)
    private fun suffix(node:JsonNode,suffix:String) {
        if(node.isObject) {
            val fields=node.fieldNames().asSequence().toList()
            for(field in fields) {val child=node.get(field);if(field in idFields && child.isTextual && child.asText().isNotEmpty()) (node as ObjectNode).put(field,child.asText()+suffix) else suffix(child,suffix)}
        } else if(node.isArray) node.forEach {suffix(it,suffix)}
    }
    private fun scopeInstrument(node:JsonNode,partition:Int) {
        if(node.isObject) node.fields().forEachRemaining { (field,child)->
            if(field=="instrumentId" && child.isTextual) (node as ObjectNode).put(field,child.asText()+"-p$partition") else scopeInstrument(child,partition)
        } else if(node.isArray) node.forEach {scopeInstrument(it,partition)}
    }
    private fun read(props:Properties,topic:String):List<org.apache.kafka.clients.consumer.ConsumerRecord<ByteArray,ByteArray>> {
        val config=Properties().apply {putAll(props);put("enable.auto.commit",false);put("isolation.level","read_committed");put("auto.offset.reset","earliest")}
        KafkaConsumer(config,ByteArrayDeserializer(),ByteArrayDeserializer()).use {consumer->
            val partitions=consumer.partitionsFor(topic).map {TopicPartition(topic,it.partition())};consumer.assign(partitions);consumer.seekToBeginning(partitions)
            val end=consumer.endOffsets(partitions)
            val records=mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<ByteArray,ByteArray>>()
            val deadline=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<deadline && partitions.any {consumer.position(it)<end.getValue(it)}) records.addAll(consumer.poll(Duration.ofMillis(100)).toList())
            return records
        }
    }
}
