package com.reef.platform.calcify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.reef.platform.api.JsonCodec
import java.nio.file.Files
import java.time.Duration
import java.time.OffsetDateTime
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.rocksdb.Options
import org.rocksdb.RocksDB
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.*

/** Post-load actual-topic oracle. Fact mapping deliberately does not call MatchContextResolver. */
object CalcifyActualFullFactObserver {
    private val mapper = JsonMapper.builder().build()
    internal data class Facts(val orders: List<AcceptedOrderSourceV1>, val trades: List<TradeSourceV1>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 5..7) { "bootstrap sourceTopic resolvedTopic generation expectedContexts [timeoutSeconds] [policyVersion]" }
        val broker=args[0]; val source=args[1]; val output=args[2]; val generation=args[3].toInt()
        val expected=args[4].toLong(); val timeout=args.getOrNull(5)?.toLong() ?: 900L; val policy=args.getOrNull(6)?.toInt() ?: 1
        require(expected >= 0 && timeout > 0 && generation > 0 && policy in 1..65535)
        val props=Properties().apply {
            put("bootstrap.servers",broker);put("enable.auto.commit",false);put("isolation.level","read_committed")
            put("auto.offset.reset","earliest");put("allow.auto.create.topics",false);put("fetch.max.bytes",32*1024*1024)
            put("max.partition.fetch.bytes",16*1024*1024+1024);put("max.poll.records",1)
        }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout)
        val scratch=Files.createTempDirectory("reef-actual-fullfact-")
        var contexts=0L;var batches=0L;var orders=0L;var sourceBytes=0L;var maxSourceRecordBytes=0
        val hashes=mutableMapOf<Int,java.security.MessageDigest>()
        RocksDB.loadLibrary()
        try {
            AdminClient.create(props).use { admin ->
                val identities=admin.describeTopics(listOf(source,output)).allTopicNames().get(20,TimeUnit.SECONDS)
                val uuid=identities.getValue(source).topicId().toString()
                val partitions=identities.getValue(source).partitions().map {it.partition()}.sorted()
                require(partitions==identities.getValue(output).partitions().map {it.partition()}.sorted()) { "source/output partition sets differ" }
                Options().setCreateIfMissing(true).setWriteBufferSize(16L*1024*1024).setMaxWriteBufferNumber(2).use { options ->
                    RocksDB.open(options,scratch.toString()).use { store ->
                        KafkaConsumer(props,ByteArrayDeserializer(),ByteArrayDeserializer()).use { input ->
                            KafkaConsumer(props,ByteArrayDeserializer(),ByteArrayDeserializer()).use { resolved ->
                                val sourcePartitions=partitions.map {TopicPartition(source,it)}
                                val outputPartitions=partitions.map {TopicPartition(output,it)}
                                val sourceEnd=input.endOffsets(sourcePartitions);val outputEnd=resolved.endOffsets(outputPartitions)
                                require(input.beginningOffsets(sourcePartitions).values.all {it==0L} && resolved.beginningOffsets(outputPartitions).values.all {it==0L}) { "full-history oracle requires retained offset0" }
                                for(partition in partitions) {
                                    val sourcePartition=TopicPartition(source,partition);val outputPartition=TopicPartition(output,partition)
                                    input.assign(listOf(sourcePartition));input.seek(sourcePartition,0)
                                    resolved.assign(listOf(outputPartition));resolved.seek(outputPartition,0)
                                    val sourceReader=FrozenReader(input,sourcePartition,sourceEnd.getValue(sourcePartition),deadline)
                                    val outputReader=FrozenReader(resolved,outputPartition,outputEnd.getValue(outputPartition),deadline)
                                    while(true) {
                                        val record=sourceReader.next() ?: break
                                        sourceBytes+=record.value().size
                                        maxSourceRecordBytes=maxOf(maxSourceRecordBytes,record.value().size)
                                        val decoded=decode(record.value(),source,uuid,generation,partition,record.offset())
                                        batches++
                                        for(next in decoded.orders) {
                                            val key="$partition:${next.fact.orderId}".toByteArray(Charsets.UTF_8)
                                            val prior=store.get(key)?.let {AcceptedOrderSourceV1.parseFrom(it)}
                                            if(prior!=null) {
                                                require(prior.fact==next.fact && prior.acceptance==next.acceptance && prior.source.commandId==next.source.commandId) { "independent acceptance conflict ${next.fact.orderId}" }
                                            } else {store.put(key,next.toByteArray());orders++}
                                        }
                                        decoded.trades.forEachIndexed {ordinal,trade ->
                                            val id=CommitmentId(generation,partition,record.offset(),ordinal)
                                            val row=outputReader.next() ?: error("missing resolved context $id")
                                            require(row.partition()==partition && CalcifyWire.readCommitment(row.key())==id) { "ordered context mismatch: expected $id at output ${row.offset()}" }
                                            fun lookup(orderId:String)=store.get("$partition:$orderId".toByteArray(Charsets.UTF_8))?.let {AcceptedOrderSourceV1.parseFrom(it)} ?: error("missing source acceptance $orderId")
                                            val oracle=context(id,policy,trade,lookup(trade.fact.buyOrderId),lookup(trade.fact.sellOrderId))
                                            require(MatchContextResolvedV1.parseFrom(row.value())==oracle) { "independent full-fact mismatch $id" }
                                            hashes.getOrPut(partition) {java.security.MessageDigest.getInstance("SHA-256")}.apply {update(row.key());update(java.nio.ByteBuffer.allocate(4).putInt(row.value().size).array());update(row.value())}
                                            contexts++
                                        }
                                    }
                                    require(outputReader.next()==null) { "extra resolved records in partition $partition" }
                                }
                                val finalIdentities=admin.describeTopics(listOf(source,output)).allTopicNames().get(20,TimeUnit.SECONDS)
                                require(identities.all {(name,description)->description.topicId()==finalIdentities.getValue(name).topicId()}) { "topic recreated during oracle" }
                                require(input.endOffsets(sourcePartitions)==sourceEnd && resolved.endOffsets(outputPartitions)==outputEnd) { "topics advanced during post-load oracle" }
                                require(contexts==expected) { "context total $contexts != expected $expected" }
                                println(JsonCodec.writeObject("result" to mapOf("sourceTopic" to source,"resolvedTopic" to output,"sourceTopicId" to uuid,"generation" to generation,"policyVersion" to policy,"sourceBatches" to batches,"sourceBytes" to sourceBytes,"maxSourceRecordBytes" to maxSourceRecordBytes,"acceptedOrders" to orders,"records" to contexts,"expected" to expected,"fullFactParity" to true,"orderedUnique" to true,"independentFactDecoder" to true,"checksumValidator" to "shared CalcifySourceBatch.checked","sourceCommittedEnds" to sourceEnd.mapKeys {it.key.partition().toString()},"resolvedCommittedEnds" to outputEnd.mapKeys {it.key.partition().toString()},"partitionSha256" to hashes.toSortedMap().mapValues {java.util.HexFormat.of().formatHex(it.value.digest())},"pass" to true)))
                            }
                        }
                    }
                }
            }
        } finally {
            Files.walk(scratch).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}
        }
    }

    private class FrozenReader(private val consumer:KafkaConsumer<ByteArray,ByteArray>,private val partition:TopicPartition,private val end:Long,private val deadline:Long) {
        private val pending=ArrayDeque<ConsumerRecord<ByteArray,ByteArray>>()
        fun next():ConsumerRecord<ByteArray,ByteArray>? {
            while(pending.isEmpty()) {
                if(consumer.position(partition)>=end) return null
                check(System.nanoTime()<deadline) { "actual full-fact observer timeout at $partition" }
                consumer.poll(Duration.ofMillis(100)).forEach {if(it.offset()<end)pending.addLast(it)}
            }
            return pending.removeFirst()
        }
    }

    internal fun decode(payload:ByteArray,topic:String,topicId:String,generation:Int,partition:Int,offset:Long):Facts {
        // Envelope/checksum validation shared; all field extraction below is independent.
        CalcifySourceBatch.checked(payload,topic,partition)
        val root=mapper.readTree(payload);val orders=mutableListOf<AcceptedOrderSourceV1>();val trades=mutableListOf<TradeSourceV1>()
        root.get("outcomes").forEachIndexed {ordinal,outcome ->
            val provenance=SourceProvenanceV1.newBuilder().setSourceGeneration(generation).setSourcePartition(partition).setSourceOffset(offset)
                .setOutcomeOrdinal(ordinal).setSourceTopic(topic).setSourceTopicId(topicId).setBatchId(text(root,"batchId"))
                .setBatchChecksum(text(root,"payloadChecksum")).setCommandId(text(outcome,"commandId")).build()
            val result=outcome.get("result")
            if(text(outcome,"commandType")=="SubmitOrder" && text(outcome,"status")=="accepted") {
                require(!result.has("rejected"))
                val accepted=result.get("accepted");val fact=acceptedFact(result.get("acceptedOrder"))
                val event=OrderAccepted.newBuilder().setEventId(text(accepted,"eventId")).setOrderId(text(accepted,"orderId"))
                    .setEngineOrderId(text(accepted,"engineOrderId")).setOccurredAt(timestamp(accepted,"occurredAt")).build()
                require(event.orderId==fact.orderId && event.engineOrderId==fact.engineOrderId && event.occurredAt==fact.acceptedAt)
                require(text(outcome,"orderId")==fact.orderId && text(outcome,"instrumentId")==fact.instrumentId)
                orders+=AcceptedOrderSourceV1.newBuilder().setFact(fact).setAcceptance(event).setSource(provenance).build()
            }
            val rows=result.get("trades")
            if(rows!=null) {
                require(rows.isArray)
                rows.forEach {trade ->
                    require(text(outcome,"status")=="accepted" && result.has("accepted") && !result.has("rejected"))
                    val fact=TradeCreated.newBuilder().setEventId(text(trade,"eventId")).setTradeId(text(trade,"tradeId"))
                        .setExecutionId(text(trade,"executionId")).setBuyOrderId(text(trade,"buyOrderId")).setSellOrderId(text(trade,"sellOrderId"))
                        .setInstrumentId(text(trade,"instrumentId")).setQuantity(OrderQuantity.newBuilder().setUnits(positive(trade,"quantityUnits")))
                        .setPrice(Price.newBuilder().setNanos(positive(trade,"price")).setCurrency(text(trade,"currency")))
                        .setOccurredAt(timestamp(trade,"occurredAt")).build()
                    require(text(outcome,"instrumentId")==fact.instrumentId)
                    trades+=TradeSourceV1.newBuilder().setFact(fact).setSource(provenance).build()
                }
            }
        }
        return Facts(orders,trades)
    }

    private fun acceptedFact(row:JsonNode):AcceptedOrderFactV1 {
        val client=row.get("clientOrderId");require(client==null || client.isTextual)
        return AcceptedOrderFactV1.newBuilder().setOrderId(text(row,"orderId")).setEngineOrderId(text(row,"engineOrderId"))
            .setClientOrderId(client?.textValue() ?: "").setRunId(text(row,"runId")).setVenueSessionId(text(row,"venueSessionId"))
            .setInstrumentId(text(row,"instrumentId")).setParticipantId(text(row,"participantId")).setAccountId(text(row,"accountId"))
            .setSide(when(text(row,"side")){"BUY"->OrderSide.ORDER_SIDE_BUY;"SELL"->OrderSide.ORDER_SIDE_SELL;else->error("bad side")})
            .setOrderType(when(text(row,"orderType")){"LIMIT"->OrderType.ORDER_TYPE_LIMIT;else->error("bad order type")})
            .setQuantityUnits(positive(row,"quantityUnits")).setLimitPrice(positive(row,"limitPrice")).setCurrency(text(row,"currency"))
            .setTimeInForce(when(text(row,"timeInForce")){"DAY"->TimeInForce.TIME_IN_FORCE_DAY;"IOC"->TimeInForce.TIME_IN_FORCE_IOC;else->error("bad TIF")})
            .setAcceptedAt(timestamp(row,"acceptedAt")).build()
    }

    internal fun context(id:CommitmentId,policy:Int,trade:TradeSourceV1,buy:AcceptedOrderSourceV1,sell:AcceptedOrderSourceV1):MatchContextResolvedV1 {
        require(trade.source.sourceGeneration==id.sourceGeneration && trade.source.sourcePartition==id.sourcePartition && trade.source.sourceOffset==id.sourceOffset)
        require(buy.fact.side==OrderSide.ORDER_SIDE_BUY && sell.fact.side==OrderSide.ORDER_SIDE_SELL)
        require(buy.fact.orderId==trade.fact.buyOrderId && sell.fact.orderId==trade.fact.sellOrderId && buy.fact.orderId!=sell.fact.orderId)
        require(buy.fact.runId==sell.fact.runId && buy.fact.venueSessionId==sell.fact.venueSessionId && buy.fact.instrumentId==sell.fact.instrumentId && trade.fact.instrumentId==buy.fact.instrumentId)
        require(buy.fact.currency==trade.fact.price.currency && sell.fact.currency==trade.fact.price.currency)
        for(order in listOf(buy,sell)) {
            val a=order.source;val b=trade.source
            require(a.sourceGeneration==b.sourceGeneration && a.sourcePartition==b.sourcePartition && a.sourceTopic==b.sourceTopic && a.sourceTopicId==b.sourceTopicId)
            require(a.sourceOffset<b.sourceOffset || (a.sourceOffset==b.sourceOffset && a.outcomeOrdinal<=b.outcomeOrdinal))
        }
        return MatchContextResolvedV1.newBuilder().setCommitment(CommitmentSourceV1.newBuilder().setSourceGeneration(id.sourceGeneration)
            .setSourcePartition(id.sourcePartition).setSourceOffset(id.sourceOffset).setTradeOrdinal(id.tradeOrdinal))
            .setPolicyVersion(policy).setRunId(buy.fact.runId).setTrade(trade).setBuyAcceptedOrder(buy).setSellAcceptedOrder(sell).build()
    }
    private fun text(row:JsonNode,field:String):String {val value=row.get(field);require(value!=null && value.isTextual && value.textValue().isNotBlank()) {"missing/nontext $field"};return value.textValue()}
    private fun positive(row:JsonNode,field:String)=text(row,field).also {require((it.toLongOrNull() ?: 0)>0)}
    private fun timestamp(row:JsonNode,field:String)=text(row,field).also {OffsetDateTime.parse(it)}
}
