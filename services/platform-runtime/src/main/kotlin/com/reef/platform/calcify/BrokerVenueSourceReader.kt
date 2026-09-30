package com.reef.platform.calcify

import java.time.Duration
import java.util.Properties
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/** One assigned source partition; only recovery seeks. Poll tail is bounded by records and bytes. */
internal class BrokerVenueSourceReader(
    bootstrap:String,
    private val settings:ResolverSettings,
    partition:Int,
    private val maxBufferBytes:Int=16*1024*1024,
):VenueSourceReader {
    private val tp=TopicPartition(settings.sourceTopic,partition)
    private val queue=ArrayDeque<VenueSourceEntry>()
    private var queuedBytes=0
    private var initialized=false
    private var identityCheckedAt=0L
    private val admin=AdminClient.create(Properties().apply {put("bootstrap.servers",bootstrap);put("request.timeout.ms",2000);put("default.api.timeout.ms",3000)})
    private val consumer=KafkaConsumer(Properties().apply {
        put("bootstrap.servers",bootstrap);put("enable.auto.commit",false);put("isolation.level","read_committed")
        put("auto.offset.reset","none");put("max.poll.records",16)
        put("max.partition.fetch.bytes",settings.maxSourceBytes);put("fetch.max.bytes",maxBufferBytes)
        put("default.api.timeout.ms",3000);put("request.timeout.ms",2000)
    },ByteArrayDeserializer(),ByteArrayDeserializer()).apply {assign(listOf(tp))}

    override fun validateIdentity() {
        // Startup plus live monitoring; no broker request per trade.
        if(identityCheckedAt!=0L && System.nanoTime()-identityCheckedAt<1_000_000_000L) return
        val topic=admin.describeTopics(listOf(tp.topic())).allTopicNames().get(3,TimeUnit.SECONDS).getValue(tp.topic())
        require(topic.topicId()!=Uuid.ZERO_UUID && topic.topicId().toString()==settings.sourceTopicId) {"source topic identity changed"}
        identityCheckedAt=System.nanoTime()
    }

    override fun next(cursor:Long,target:Long):VenueSourceEntry? {
        validateIdentity()
        val next=cursor+1
        if(!initialized) {
            require(consumer.beginningOffsets(listOf(tp)).getValue(tp)<=next) {"source retention lost"}
            consumer.seek(tp,next);initialized=true
        }
        if(queue.isEmpty()) {
            require(consumer.beginningOffsets(listOf(tp)).getValue(tp)<=next) {"source retention lost"}
            for(record in consumer.poll(Duration.ofMillis(10))) {
                require(record.value()!=null) {"source tombstone"}
                require(record.value().size<=settings.maxSourceBytes) {"source record budget exceeded"}
                require(queuedBytes+record.value().size<=maxBufferBytes) {"source buffer budget exceeded"}
                queue.add(VenueSourceEntry(record.offset(),record.value()));queuedBytes+=record.value().size
            }
        }
        val entry=queue.peek() ?: return null
        require(entry.offset<=target) {"target source offset absent"}
        queue.remove();queuedBytes-=entry.payload.size
        return entry
    }
    override fun close() {
        try {consumer.close(Duration.ofSeconds(2))} finally {admin.close(Duration.ofSeconds(2));queue.clear()}
    }
}
