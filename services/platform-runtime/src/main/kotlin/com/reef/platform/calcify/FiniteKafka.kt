package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.StreamCommandEnvelope
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer

/** All clients authenticate. Runtime verifies existing topics; never creates or alters them. */
internal class FiniteKafka(private val opening:FiniteOpening,private val bootstrap:String,
    private val username:String,private val password:String,private val protocol:String="SASL_PLAINTEXT") : AutoCloseable {
    init {
        require(bootstrap.isNotBlank() && username.isNotBlank() && password.isNotBlank())
        require(protocol in listOf("SASL_PLAINTEXT","SASL_SSL"))
    }
    fun properties():Properties=Properties().apply {
        put("bootstrap.servers",bootstrap);put("security.protocol",protocol);put("sasl.mechanism","PLAIN")
        fun quoted(s:String)=s.replace("\\","\\\\").replace("\"","\\\"")
        put("sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${quoted(username)}\" password=\"${quoted(password)}\";")
        put("request.timeout.ms",5000);put("default.api.timeout.ms",10000);put("connections.max.idle.ms",30000)
        if(protocol=="SASL_SSL")put("ssl.endpoint.identification.algorithm","https")
    }
    fun verifyIdentities() {
        AdminClient.create(properties()).use { admin ->
            require(admin.describeCluster().clusterId().get(10,TimeUnit.SECONDS)==opening.json()["brokerClusterId"].textValue()){"finite broker cluster mismatch"}
            val names=FiniteBindingContract.roles.map{opening.topic(it).name}
            val topics=admin.describeTopics(names).allTopicNames().get(10,TimeUnit.SECONDS)
            val configs=admin.describeConfigs(names.map{ConfigResource(ConfigResource.Type.TOPIC,it)}).all().get(10,TimeUnit.SECONDS)
            for(role in FiniteBindingContract.roles) {
                val expected=opening.topic(role);val actual=topics.getValue(expected.name)
                require(actual.topicId().toString()==expected.id && actual.partitions().size==1 && actual.partitions().single().partition()==0){"finite topic identity/partition mismatch"}
                val config=configs.getValue(ConfigResource(ConfigResource.Type.TOPIC,expected.name))
                val needed=when(role){"CAPTURE_CHANGELOG"->opening.budget.stateBytes+1024;"CAPTURE"->opening.budget.captureBytes+1024L;"SOURCE"->opening.budget.sourceBytes+1024L;else->opening.budget.commandBytes+1024L}
                require(config.get("max.message.bytes").value().toLong()>=needed){"finite topic transport limit"}
                if(role=="CAPTURE_CHANGELOG") require(config.get("cleanup.policy").value()=="compact"){"finite managed changelog configuration"}
                if(role!="CAPTURE_CHANGELOG") require(config.get("retention.ms").value()=="-1" && config.get("retention.bytes").value()=="-1" && config.get("cleanup.policy").value()=="delete"){"finite retained history configuration"}
            }
        }
    }
    fun consumer():KafkaConsumer<ByteArray,ByteArray> = KafkaConsumer(properties().apply {
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer::class.java.name)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"none");put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,16)
        put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,(opening.budget.stateBytes+1024).toInt())
        put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,(opening.budget.stateBytes+1024).toInt())
    })
    fun cuts():ObjectNode {
        val root=FiniteBindingContract.strictObject("{}".toByteArray(),2);val json=opening.json()
        AdminClient.create(properties()).use { admin ->
            fun group(key:String,id:String) {
                val topic=if(key=="commandGroup")opening.topic("COMMAND").name else opening.topic("SOURCE").name
                val values=admin.listConsumerGroupOffsets(id).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS)
                require(values.keys==setOf(TopicPartition(topic,0))){"finite committed group cut unavailable"}
                val cut=values.getValue(TopicPartition(topic,0)).offset();require(cut in 0..FiniteBindingContract.MAX_OFFSET)
                root.putObject(key).put("groupId",id).put("nextOffset",cut.toString())
            }
            group("commandGroup",json["commandConsumerGroup"].textValue());group("captureSourceGroup",opening.applicationId)
            val ranges=root.putArray("ranges")
            consumer().use { committed ->
                for(role in FiniteBindingContract.roles) {
                    val topic=TopicPartition(opening.topic(role).name,0)
                    val begin=admin.listOffsets(mapOf(topic to OffsetSpec.earliest())).all().get(10,TimeUnit.SECONDS).getValue(topic).offset()
                    val end=admin.listOffsets(mapOf(topic to OffsetSpec.latest())).all().get(10,TimeUnit.SECONDS).getValue(topic).offset()
                    val lso=committed.endOffsets(listOf(topic),Duration.ofSeconds(10)).getValue(topic)
                    require(begin in 0..lso && lso<=end && end<=FiniteBindingContract.MAX_OFFSET){"finite broker cuts invalid"}
                    ranges.addObject().put("role",role).put("partition",0).put("beginningOffset",begin.toString()).put("endOffset",end.toString()).put("readCommittedEndOffset",lso.toString())
                }
            }
        }
        return root
    }
    fun read(role:String,begin:Long,end:Long,maximumRecords:Int,maximumBytes:Long):List<org.apache.kafka.clients.consumer.ConsumerRecord<ByteArray,ByteArray>> {
        require(begin in 0..end && end<=FiniteBindingContract.MAX_OFFSET)
        val rows=ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<ByteArray,ByteArray>>()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var bytes=0L
        consumer().use { reader ->
            val topic=TopicPartition(opening.topic(role).name,0);reader.assign(listOf(topic));reader.seek(topic,begin)
            while(reader.position(topic)<end) {
                require(System.nanoTime()<deadline){"finite history read timeout"}
                for(record in reader.poll(Duration.ofMillis(100))) if(record.offset()<end) {
                    require(record.offset()>=begin && rows.size<maximumRecords){"finite history record budget"}
                    bytes=Math.addExact(bytes,(record.value()?.size ?: 0).toLong());require(bytes<=maximumBytes){"finite history byte budget"};rows+=record
                }
            }
        }
        return rows
    }
    /** Positive committed managed state, bounded fold retaining only latest payload. */
    fun verifyManagedState(expected:ByteArray,range:com.fasterxml.jackson.databind.JsonNode) {
        val begin=range["beginningOffset"].textValue().toLong();val end=range["readCommittedEndOffset"].textValue().toLong()
        val maximumRecords=1+2*opening.budget.publications // genesis, admitted state updates, bounded restore duplicate allowance
        val maximumBytes=Math.multiplyExact(maximumRecords.toLong(),opening.budget.stateBytes)
        var count=0;var total=0L;var latest:ByteArray?=null
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        consumer().use {reader->
            val topic=TopicPartition(opening.topic("CAPTURE_CHANGELOG").name,0);reader.assign(listOf(topic));reader.seek(topic,begin)
            while(reader.position(topic)<end) {
                require(System.nanoTime()<deadline){"finite managed history read timeout"}
                for(record in reader.poll(Duration.ofMillis(100))) if(record.offset()<end) {
                    require(record.offset()>=begin && ++count<=maximumRecords && record.key()?.contentEquals("state".toByteArray())==true &&
                        record.value()!=null && record.value().size<=opening.budget.stateBytes){"finite managed changelog record budget/shape"}
                    total=Math.addExact(total,record.value().size.toLong());require(total<=maximumBytes){"finite managed changelog byte budget"}
                    latest=record.value()
                }
            }
        }
        require(latest?.contentEquals(expected)==true){"finite managed state lacks exact committed changelog receipt"}
    }
    private var producer:KafkaProducer<ByteArray,ByteArray>?=null
    @Synchronized fun commandPublisher():FiniteCommandPublisher {
        check(producer==null){"finite producer already opened"}
        val p=KafkaProducer<ByteArray,ByteArray>(properties().apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,ByteArraySerializer::class.java.name);put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG,"all");put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true)
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,1);put(ProducerConfig.BUFFER_MEMORY_CONFIG,8388608L)
            put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG,opening.budget.commandBytes+1024);put(ProducerConfig.COMPRESSION_TYPE_CONFIG,"none")
            put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,15000)
            put(ProducerConfig.MAX_BLOCK_MS_CONFIG,1000)
        });producer=p
        return FiniteCommandPublisher { e:StreamCommandEnvelope ->
            val result=CompletableFuture<FiniteBrokerAck>()
            p.send(ProducerRecord(opening.topic("COMMAND").name,0,e.natsMessageId.toByteArray(),e.payloadJson.toByteArray(),
                listOf(RecordHeader("reef-stream-subject",e.subject.toByteArray())))){ metadata,error ->
                if(error!=null)result.completeExceptionally(error)
                else if(metadata==null)result.completeExceptionally(IllegalStateException("finite ACK missing metadata"))
                else result.complete(FiniteBrokerAck(metadata.topic(),metadata.partition(),metadata.offset()))
            }
            result
        }
    }
    // close(Duration) bounds producer drain; external shutdown must verify callbacks/journal and process exit.
    override fun close() {producer?.close(Duration.ofSeconds(10));producer=null}
}
