package com.reef.platform.calcify

import java.nio.ByteBuffer
import java.time.Duration
import org.apache.kafka.common.serialization.*
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import reef.contracts.calcify.v1.AcceptedOrderSourceV1
import reef.contracts.calcify.v1.ResolverTargetBatchV1

/** One serial task per verified lane. Managed state and output share Streams EOS transaction. */
internal class CalcifyResolverProcessor(
    private val settings:ResolverSettings,
    private val readerFactory:(Int)->VenueSourceReader,
    private val blocked:(Int,Boolean)->Unit,
    private val report:(Int,Map<String,Any>)->Unit,
):Processor<ByteArray,ByteArray,ByteArray,ByteArray> {
    private lateinit var context:ProcessorContext<ByteArray,ByteArray>
    private lateinit var store:KeyValueStore<String,ByteArray>
    private lateinit var reader:VenueSourceReader
    private var partition=0
    private var cachedTarget:ResolverTargetBatchV1?=null
    private val acceptedCache=java.util.LinkedHashMap<String,AcceptedOrderSourceV1>(256,0.75f,true)
    private var acceptedCacheBytes=0L

    override fun init(context:ProcessorContext<ByteArray,ByteArray>) {
        this.context=context;store=context.getStateStore("resolver");partition=context.taskId().partition()
        cachedTarget=null;acceptedCache.clear();acceptedCacheBytes=0;reader=readerFactory(partition)
        // Version is transactionally checkpointed with index and target. Legacy keys
        // cannot be migrated by renaming: lost run collisions require source replay.
        val stateVersion = store.get("stateVersion")?.toString(Charsets.UTF_8)
        if (stateVersion == null) {
            val empty = store.all().use { !it.hasNext() }
            if (empty) store.put("stateVersion", STATE_VERSION.toByteArray())
            else fault("resolver state version missing; coordinated source/verification replay required")
        } else if (stateVersion != STATE_VERSION) fault("resolver state version incompatible; coordinated source/verification replay required")
        val identity="${settings.generation}:${settings.sourceTopic}:${settings.sourceTopicId}".toByteArray()
        val prior=store.get("identity")
        if(prior!=null && !prior.contentEquals(identity)) fault("source generation/topic identity changed") else store.put("identity",identity)
        if(number("pendingCount",0)>0 && store.get("pendingHead")==null) fault("pending queue checkpoint missing; explicit state repair required")
        blocked(partition,store.get("fault")!=null || number("pendingCount",0)>0)
        context.schedule(Duration.ofMillis(10),PunctuationType.WALL_CLOCK_TIME) { drain() }
        context.schedule(Duration.ofSeconds(5),PunctuationType.WALL_CLOCK_TIME) { report(partition,stats()) }
    }

    override fun process(record:Record<ByteArray,ByteArray>) {
        if(store.get("fault")!=null) {retainFaultInput(record);blocked(partition,true);return}
        try {
            require(record.value()!=null) {"null verification value"}
            val passed=CalcifyWire.readPassed(record.value());val id=passed.commitmentId
            require(id.sourceGeneration==settings.generation && id.sourcePartition==partition) {"verification generation/lane mismatch"}
            require(record.key()==null || record.key().contentEquals(CalcifyWire.commitment(id))) {"verification key mismatch"}
            val done=store.get(doneKey(id))
            if(done!=null) {require(done.contentEquals(record.value())) {"conflicting completed verification"};return}
            val key=pendingKey(id);val pending=store.get(key)
            require(pending==null || pending.contentEquals(record.value())) {"conflicting pending verification"}
            if(pending==null) {
                val frontier=store.get("stagedFrontier")?.toString(Charsets.UTF_8)
                require(frontier==null || key>frontier) {"verification lane order regressed"}
                if(number("pendingCount",0)==0L && number("cursor",-1)==id.sourceOffset) {
                    setNumber("stagedInputOffset",context.recordMetadata().orElseThrow().offset())
                    store.put("stagedFrontier",key.toByteArray())
                    publish(passed,record.value())
                    return
                }
                require(number("pendingCount",0)<settings.maxPending) {"pending budget exceeded"}
                val tail=store.get("pendingTail")?.toString(Charsets.UTF_8)
                if(tail==null) store.put("pendingHead",key.toByteArray()) else store.put("N:$tail",key.toByteArray())
                store.put("pendingTail",key.toByteArray());store.put("stagedFrontier",key.toByteArray())
                store.put(key,record.value());setNumber("pendingCount",number("pendingCount",0)+1)
            }
            setNumber("stagedInputOffset",context.recordMetadata().orElseThrow().offset())
        } catch(ex:IllegalArgumentException) {
            retainFaultInput(record)
            fault(ex.message ?: "invalid verification");return
        }
        drain()
    }

    private fun drain() {
        if(store.get("fault")!=null) {blocked(partition,true);return}
        try {reader.validateIdentity()} catch(ex:IllegalArgumentException) {fault(ex.message ?: "source identity failure");return}
        var work=0
        val deadline=System.nanoTime()+20_000_000
        while(work<settings.maxWorkPerDrain && System.nanoTime()<deadline) {
            val head=store.get("pendingHead")?.toString(Charsets.UTF_8)
            val pending=head?.let {org.apache.kafka.streams.KeyValue(it,checkNotNull(store.get(it)) {"pending queue row missing"})}
            if(pending==null) {blocked(partition,false);return}
            try {
                val passed=CalcifyWire.readPassed(pending.value);val id=passed.commitmentId
                while(number("cursor",-1)<id.sourceOffset) {
                    if(work>=settings.maxWorkPerDrain || System.nanoTime()>=deadline) {blocked(partition,true);return}
                    val entry=reader.next(number("cursor",-1),id.sourceOffset) ?: run {blocked(partition,true);return}
                    require(entry.offset>number("cursor",-1) && entry.offset<=id.sourceOffset) {"target source offset absent"}
                    ingest(entry);work++
                }
                publish(passed,pending.value);store.delete(pending.key)
                val next=store.get("N:${pending.key}");store.delete("N:${pending.key}")
                if(next==null) {store.delete("pendingHead");store.delete("pendingTail")} else store.put("pendingHead",next)
                setNumber("pendingCount",number("pendingCount",0)-1)
                // Streams commits this state and output together on configured interval.
                work++
            } catch(ex:IllegalArgumentException) {fault(ex.message ?: "source integrity failure");return}
        }
        blocked(partition,store.get("pendingHead")!=null)
    }

    private fun publish(passed:CommitmentVerificationPassed,passedBytes:ByteArray) {
        val id=passed.commitmentId
        val target=cachedTarget ?: store.get("target")?.let(ResolverTargetBatchV1::parseFrom)
        require(target!=null && target.sourceOffset==id.sourceOffset) {"verification source order regressed"}
        cachedTarget=target
        require(id.tradeOrdinal<target.tradesCount) {"trade ordinal absent"}
        val trade=target.getTrades(id.tradeOrdinal)
        val buy=accepted(orderKey(trade.runId,trade.fact.buyOrderId))
        val sell=accepted(orderKey(trade.runId,trade.fact.sellOrderId))
        val output=MatchContextResolver.resolve(passed,trade,buy,sell).toByteArray()
        require(output.size<=settings.maxTargetBytes) {"output budget exceeded"}
        reader.validateIdentity()
        context.forward(Record(CalcifyWire.commitment(id),output,0))
        store.put(doneKey(id),passedBytes)
        setNumber("resolvedCount",number("resolvedCount",0)+1)
        setNumber("outputBytes",number("outputBytes",0)+output.size)
        store.put("completedFrontier",CalcifyWire.commitment(id))
    }

    private fun ingest(entry:VenueSourceEntry) {
        require(entry.payload.size<=settings.maxSourceBytes) {"source record budget exceeded"}
        val batch=MatchContextResolver.parseBatch(entry.payload,settings.sourceTopic,settings.sourceTopicId,settings.generation,partition,entry.offset)
        for(row in batch.acceptedOrders) {
            // Read managed state for existence: cache cannot decide writes after transaction rollback.
            val key=orderKey(row.fact.runId,row.fact.orderId);val prior=store.get(key)?.let(AcceptedOrderSourceV1::parseFrom)
            val mergedRow=MatchContextResolver.mergeAcceptance(prior,row)
            val merged=mergedRow.toByteArray()
            require(merged.size<=settings.maxRowBytes) {"accepted row budget exceeded"}
            if(prior==null) {store.put(key,merged);setNumber("indexedCount",number("indexedCount",0)+1)}
            remember(key,mergedRow)
        }
        val target=ResolverTargetBatchV1.newBuilder().setSourceOffset(entry.offset).addAllTrades(batch.trades).build()
        val bytes=target.toByteArray();require(bytes.size<=settings.maxTargetBytes) {"target batch budget exceeded"}
        store.put("target",bytes);setNumber("cursor",entry.offset);cachedTarget=target
        setNumber("decodedCount",number("decodedCount",0)+1)
    }

    private fun accepted(key:String):AcceptedOrderSourceV1? = acceptedCache[key] ?: store.get(key)?.let {
        AcceptedOrderSourceV1.parseFrom(it).also {row->remember(key,row)}
    }

    private fun remember(key:String,row:AcceptedOrderSourceV1) {
        acceptedCache.put(key,row)?.let {acceptedCacheBytes-=it.serializedSize}
        acceptedCacheBytes+=row.serializedSize
        while(acceptedCache.size>settings.maxAcceptedCacheRows || acceptedCacheBytes>settings.maxAcceptedCacheBytes) {
            val iterator=acceptedCache.entries.iterator();val oldest=iterator.next()
            acceptedCacheBytes-=oldest.value.serializedSize;iterator.remove()
        }
    }

    // Streams can already hold a bounded poll suffix when pause is requested.
    // Preserve every suffix record instead of silently committing past it.
    private fun retainFaultInput(record:Record<ByteArray,ByteArray>) {
        val offset=context.recordMetadata().orElseThrow().offset()
        val key="Q:%020d".format(java.util.Locale.ROOT,offset)
        if(store.get(key)!=null) return
        check(number("faultSuffixCount",0)<settings.maxPending) { "fault suffix budget exceeded; stop and replay uncommitted poll" }
        val recordKey=record.key();val value=record.value()
        check((recordKey?.size ?: 0)+(value?.size ?: 0)<=settings.maxRowBytes) { "fault record byte budget exceeded; stop and replay uncommitted poll" }
        val envelope=ByteBuffer.allocate(8+(recordKey?.size ?: 0)+(value?.size ?: 0)).putInt(recordKey?.size ?: -1).putInt(value?.size ?: -1)
        if(recordKey!=null) envelope.put(recordKey)
        if(value!=null) envelope.put(value)
        store.put(key,envelope.array());setNumber("faultSuffixCount",number("faultSuffixCount",0)+1)
        if(store.get("faultInputOffset")==null) setNumber("faultInputOffset",offset)
    }

    private fun fault(reason:String) {
        if(store.get("fault")==null) store.put("fault",reason.take(2048).toByteArray())
        blocked(partition,true);context.commit();report(partition,stats())
    }
    private fun stats():Map<String,Any> = mapOf("generation" to settings.generation,"partition" to partition,"sourceCursor" to number("cursor",-1),"stagedInputOffset" to number("stagedInputOffset",-1),"resolvedCount" to number("resolvedCount",0),"pendingCount" to number("pendingCount",0),"indexedCount" to number("indexedCount",0),"decodedCount" to number("decodedCount",0),"outputBytes" to number("outputBytes",0),"acceptedCacheRows" to acceptedCache.size,"acceptedCacheBytes" to acceptedCacheBytes,"faultSuffixCount" to number("faultSuffixCount",0),"faultInputOffset" to number("faultInputOffset",-1),"fault" to (store.get("fault")?.toString(Charsets.UTF_8) ?: ""))
    private fun number(key:String,default:Long)=store.get(key)?.let {ByteBuffer.wrap(it).long} ?: default
    private fun setNumber(key:String,value:Long)=store.put(key,ByteBuffer.allocate(8).putLong(value).array())
    private fun orderKey(runId:String,id:String):String {
        require(runId.isNotBlank()) { "missing authoritative order run" }
        return "O:${settings.generation}:${runId.toByteArray(Charsets.UTF_8).size}:$runId:${id.toByteArray(Charsets.UTF_8).size}:$id"
    }
    override fun close() {if(this::reader.isInitialized) reader.close();cachedTarget=null;acceptedCache.clear();acceptedCacheBytes=0;report(partition,emptyMap())}

    companion object {
        internal const val STATE_VERSION = "2"
        private fun pendingKey(id:CommitmentId)="P:"+id.sourceOffset.toString().padStart(20,'0')+":"+id.tradeOrdinal.toString().padStart(10,'0')
        private fun doneKey(id:CommitmentId)="D:"+java.util.HexFormat.of().formatHex(CalcifyWire.commitment(id))
        fun topology(settings:ResolverSettings,readerFactory:(Int)->VenueSourceReader,blocked:(Int,Boolean)->Unit={_,_->},report:(Int,Map<String,Any>)->Unit={_,_->}):Topology = Topology().apply {
            addSource("verified",ByteArrayDeserializer(),ByteArrayDeserializer(),settings.verifiedTopic)
            addProcessor("resolve",{CalcifyResolverProcessor(settings,readerFactory,blocked,report)},"verified")
            addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("resolver"),Serdes.String(),Serdes.ByteArray()).withCachingEnabled(),"resolve")
            addSink("resolved",settings.outputTopic,ByteArraySerializer(),ByteArraySerializer(),org.apache.kafka.streams.processor.StreamPartitioner<ByteArray,ByteArray> { _,key,_,_-> java.util.Optional.of(setOf(CalcifyWire.readCommitment(key).sourcePartition)) },"resolve")
        }
    }
}
