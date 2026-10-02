package com.reef.platform.tools

import com.reef.platform.api.JsonCodec
import com.reef.platform.calcify.*
import java.time.Duration
import java.util.Properties
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import reef.contracts.calcify.v1.AcceptedOrderSourceV1
import reef.contracts.calcify.v1.MatchContextResolvedV1

/** Bounded functional smoke observer. Source-prefix reconciliation is test tooling only. */
object CalcifyResolvedSmoke {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==7) {"expected bootstrap source output generation partition offset tradeId"}
        val broker=args[0];val source=args[1];val output=args[2]
        val id=CommitmentId(args[3].toInt(),args[4].toInt(),args[5].toLong(),0)
        val props=Properties().apply {put("bootstrap.servers",broker);put("enable.auto.commit",false);put("isolation.level","read_committed");put("auto.offset.reset","none")}
        var observed:MatchContextResolvedV1?=null
        KafkaConsumer(props,ByteArrayDeserializer(),ByteArrayDeserializer()).use {consumer->
            val tp=TopicPartition(output,id.sourcePartition);consumer.assign(listOf(tp));consumer.seek(tp,0)
            val deadline=System.nanoTime()+60_000_000_000L
            while(observed==null && System.nanoTime()<deadline) for(record in consumer.poll(Duration.ofMillis(100))) {
                val context=MatchContextResolvedV1.parseFrom(record.value())
                if(record.key().contentEquals(CalcifyWire.commitment(id))) {check(observed==null) {"duplicate resolved smoke context"};observed=context}
            }
        }
        val actual=checkNotNull(observed) {"resolved context timeout"}
        val uuid=AdminClient.create(Properties().apply {put("bootstrap.servers",broker)}).use {it.describeTopics(listOf(source)).allTopicNames().get().getValue(source).topicId().toString()}
        val orders=mutableMapOf<String,AcceptedOrderSourceV1>()
        var expected:MatchContextResolvedV1?=null
        KafkaConsumer(props,ByteArrayDeserializer(),ByteArrayDeserializer()).use {consumer->
            val tp=TopicPartition(source,id.sourcePartition);consumer.assign(listOf(tp));consumer.seek(tp,0)
            val deadline=System.nanoTime()+60_000_000_000L;var records=0
            while(expected==null && System.nanoTime()<deadline) for(record in consumer.poll(Duration.ofMillis(100))) {
                check(++records<=10000) {"smoke source-prefix bound exceeded"}
                val batch=MatchContextResolver.parseBatch(record.value().toString(Charsets.UTF_8),source,uuid,id.sourceGeneration,id.sourcePartition,record.offset())
                for(row in batch.acceptedOrders) orders[row.fact.orderId]=MatchContextResolver.mergeAcceptance(orders[row.fact.orderId],row)
                if(record.offset()==id.sourceOffset) {
                    val trade=batch.trades[id.tradeOrdinal]
                    expected=MatchContextResolver.resolve(CommitmentVerificationPassed(id,actual.policyVersion),trade,orders[trade.fact.buyOrderId],orders[trade.fact.sellOrderId])
                }
            }
        }
        check(actual==expected) {"resolved full-fact source parity mismatch"}
        check(actual.trade.fact.tradeId==args[6]) {"wrong resolved trade"}
        println(JsonCodec.writeObject("fullFactParity" to true,"generation" to id.sourceGeneration,"partition" to id.sourcePartition,"offset" to id.sourceOffset,"tradeId" to actual.trade.fact.tradeId,"buyOrderId" to actual.buyAcceptedOrder.fact.orderId,"sellOrderId" to actual.sellAcceptedOrder.fact.orderId,"bytes" to actual.serializedSize))
    }
}
