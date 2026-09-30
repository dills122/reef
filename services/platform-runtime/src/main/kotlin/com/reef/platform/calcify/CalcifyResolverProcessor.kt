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

    override fun init(context:ProcessorContext<ByteArray,ByteArray>) {
        this.context=context;store=context.getStateStore("resolver");partition=context.taskId().partition()
        cachedTarget=null;reader=readerFactory(partition)
        val identity="${settings.generation}:${settings.sourceTopic}:${settings.sourceTopicId}".toByteArray()
        val prior=store.get("identity")
        if(prior!=null && !prior.contentEquals(identity)) fault("source generation/topic identity changed") else store.put("identity",identity)
        blocked(partition,store.get("fault")!=null || number("pendingCount",0)>0)
        context.schedule(Duration.ofMillis(10),PunctuationType.WALL_CLOCK_TIME) { drain() }
        context.schedule(Duration.ofSeconds(5),PunctuationType.WALL_CLOCK_TIME) { report(partition,stats()) }
    }

    override fun process(record:Record<ByteArray,ByteArray>) {
        if(store.get("fault")!=null) {retainFaultInput(record);blocked(partition,true);return}
        try {
            require(record.value()!=null && record.key()!=null) {"null verification key/value"}
            val passed=CalcifyWire.readPassed(record.value());val id=passed.commitmentId
            require(id.sourceGeneration==settings.generation && id.sourcePartition==partition) {"verification generation/lane mismatch"}
            require(record.key().contentEquals(CalcifyWire.commitment(id))) {"verification key mismatch"}
            val done=store.get(doneKey(id))
            if(done!=null) {require(done.contentEquals(record.value())) {"conflicting completed verification"};return}
            val key=pendingKey(id);val pending=store.get(key)
            require(pending==null || pending.contentEquals(record.value())) {"conflicting pending verification"}
            if(pending==null) {
                require(number("pendingCount",0)<settings.maxPending) {"pending budget exceeded"}
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
        var work=0
        val deadline=System.nanoTime()+20_000_000
        while(work<settings.maxWorkPerDrain && System.nanoTime()<deadline) {
            val pending=store.range("P:","P;").use {if(it.hasNext()) it.next() else null}
            if(pending==null) {blocked(partition,false);return}
            blocked(partition,true)
            try {
                val passed=CalcifyWire.readPassed(pending.value);val id=passed.commitmentId
                while(number("cursor",-1)<id.sourceOffset) {
                    if(work>=settings.maxWorkPerDrain || System.nanoTime()>=deadline) return
                    val entry=reader.next(number("cursor",-1),id.sourceOffset) ?: return
                    require(entry.offset>number("cursor",-1) && entry.offset<=id.sourceOffset) {"target source offset absent"}
                    ingest(entry);work++
                }
                val target=cachedTarget ?: store.get("target")?.let(ResolverTargetBatchV1::parseFrom)
                require(target!=null && target.sourceOffset==id.sourceOffset) {"verification source order regressed"}
                cachedTarget=target
                require(id.tradeOrdinal<target.tradesCount) {"trade ordinal absent"}
                val trade=target.getTrades(id.tradeOrdinal)
                val buy=store.get(orderKey(trade.fact.buyOrderId))?.let(AcceptedOrderSourceV1::parseFrom)
                val sell=store.get(orderKey(trade.fact.sellOrderId))?.let(AcceptedOrderSourceV1::parseFrom)
                val output=MatchContextResolver.resolve(passed,trade,buy,sell).toByteArray()
                require(output.size<=settings.maxTargetBytes) {"output budget exceeded"}
                reader.validateIdentity()
                context.forward(Record(CalcifyWire.commitment(id),output,0))
                store.put(doneKey(id),pending.value);store.delete(pending.key)
                setNumber("pendingCount",number("pendingCount",0)-1)
                setNumber("resolvedCount",number("resolvedCount",0)+1)
                setNumber("outputBytes",number("outputBytes",0)+output.size)
                store.put("completedFrontier",CalcifyWire.commitment(id))
                context.commit();work++
            } catch(ex:IllegalArgumentException) {fault(ex.message ?: "source integrity failure");return}
        }
    }

    private fun ingest(entry:VenueSourceEntry) {
        require(entry.payload.size<=settings.maxSourceBytes) {"source record budget exceeded"}
        val payload = try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.payload)).toString() } catch(ex:java.nio.charset.CharacterCodingException) {throw IllegalArgumentException("invalid source UTF-8",ex)}
        val batch=MatchContextResolver.parseBatch(payload,settings.sourceTopic,settings.sourceTopicId,settings.generation,partition,entry.offset)
        for(row in batch.acceptedOrders) {
            val key=orderKey(row.fact.orderId);val prior=store.get(key)?.let(AcceptedOrderSourceV1::parseFrom)
            val merged=MatchContextResolver.mergeAcceptance(prior,row).toByteArray()
            require(merged.size<=settings.maxRowBytes) {"accepted row budget exceeded"}
            if(prior==null) {store.put(key,merged);setNumber("indexedCount",number("indexedCount",0)+1)}
        }
        val target=ResolverTargetBatchV1.newBuilder().setSourceOffset(entry.offset).addAllTrades(batch.trades).build()
        val bytes=target.toByteArray();require(bytes.size<=settings.maxTargetBytes) {"target batch budget exceeded"}
        store.put("target",bytes);setNumber("cursor",entry.offset);cachedTarget=target
        setNumber("decodedCount",number("decodedCount",0)+1)
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
        store.put("fault",reason.take(2048).toByteArray())
        blocked(partition,true);context.commit();report(partition,stats())
    }
    private fun stats():Map<String,Any> = mapOf("generation" to settings.generation,"partition" to partition,"sourceCursor" to number("cursor",-1),"stagedInputOffset" to number("stagedInputOffset",-1),"resolvedCount" to number("resolvedCount",0),"pendingCount" to number("pendingCount",0),"indexedCount" to number("indexedCount",0),"decodedCount" to number("decodedCount",0),"outputBytes" to number("outputBytes",0),"fault" to (store.get("fault")?.toString(Charsets.UTF_8) ?: ""))
    private fun number(key:String,default:Long)=store.get(key)?.let {ByteBuffer.wrap(it).long} ?: default
    private fun setNumber(key:String,value:Long)=store.put(key,ByteBuffer.allocate(8).putLong(value).array())
    private fun orderKey(id:String)="O:${settings.generation}:${id.toByteArray().size}:$id"
    override fun close() {if(this::reader.isInitialized) reader.close();cachedTarget=null;report(partition,emptyMap())}

    companion object {
        private fun pendingKey(id:CommitmentId)="P:%020d:%010d".format(java.util.Locale.ROOT,id.sourceOffset,id.tradeOrdinal)
        private fun doneKey(id:CommitmentId)="D:"+java.util.HexFormat.of().formatHex(CalcifyWire.commitment(id))
        fun topology(settings:ResolverSettings,readerFactory:(Int)->VenueSourceReader,blocked:(Int,Boolean)->Unit={_,_->},report:(Int,Map<String,Any>)->Unit={_,_->}):Topology = Topology().apply {
            addSource("verified",ByteArrayDeserializer(),ByteArrayDeserializer(),settings.verifiedTopic)
            addProcessor("resolve",{CalcifyResolverProcessor(settings,readerFactory,blocked,report)},"verified")
            addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("resolver"),Serdes.String(),Serdes.ByteArray()).withCachingDisabled(),"resolve")
            addSink("resolved",settings.outputTopic,ByteArraySerializer(),ByteArraySerializer(),org.apache.kafka.streams.processor.StreamPartitioner<ByteArray,ByteArray> { _,key,_,_-> java.util.Optional.of(setOf(CalcifyWire.readCommitment(key).sourcePartition)) },"resolve")
        }
    }
}
