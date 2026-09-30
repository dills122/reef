package com.reef.platform.calcify

import com.reef.platform.api.JsonCodec
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler

/** Opt-in resolver role. Kafka Streams owns checkpoint/output transactions and recovery. */
internal object CalcifyResolverRuntime {
    fun run() {
        val bootstrap=RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS","localhost:9092")
        val source=RuntimeEnv.string("CALCIFY_SOURCE_TOPIC","REEF_VENUE_EVENTS")
        val generation=RuntimeEnv.int("CALCIFY_SOURCE_GENERATION",1,min=1)
        val topicId=CalcifySourceRegistration.verify(bootstrap,source,generation,
            RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL","jdbc:postgresql://localhost:5432/reef?currentSchema=runtime"),
            RuntimeEnv.string("RUNTIME_POSTGRES_USER","reef"),RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD","reef"),bindIfAbsent=false)
        val settings=ResolverSettings(generation,source,topicId,
            RuntimeEnv.string("CALCIFY_VERIFIED_TOPIC","REEF_VERIFIED_COMMITMENTS_V1"),
            RuntimeEnv.string("CALCIFY_RESOLVED_TOPIC","REEF_MATCH_CONTEXT_RESOLVED_V1"),
            maxPending=RuntimeEnv.int("CALCIFY_RESOLVER_MAX_PENDING",200,min=2),
            maxSourceBytes=RuntimeEnv.int("CALCIFY_RESOLVER_MAX_SOURCE_BYTES",4*1024*1024,min=1),
            maxTargetBytes=RuntimeEnv.int("CALCIFY_RESOLVER_MAX_TARGET_BYTES",16*1024*1024,min=1))
        val replication=RuntimeEnv.int("CALCIFY_RESOLVER_REPLICATION_FACTOR",3,min=1).also {require(it<=Short.MAX_VALUE)}
        ensureTopics(bootstrap,settings,replication)
        val appId=RuntimeEnv.string("CALCIFY_RESOLVER_APPLICATION_ID","reef-calcify-resolver-v1-g$generation")
        val config=Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG,appId);put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap)
            put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,StreamsConfig.EXACTLY_ONCE_V2)
            put(StreamsConfig.STATE_DIR_CONFIG,RuntimeEnv.string("CALCIFY_RESOLVER_STATE_DIR","/tmp/reef-calcify-resolver"))
            put(StreamsConfig.REPLICATION_FACTOR_CONFIG,replication)
            put(StreamsConfig.topicPrefix("min.insync.replicas"),minOf(2,replication))
            put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG,RuntimeEnv.int("CALCIFY_RESOLVER_STANDBYS",1,min=0))
            put(StreamsConfig.NUM_STREAM_THREADS_CONFIG,RuntimeEnv.int("CALCIFY_RESOLVER_THREADS",1,min=1))
            put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG,100)
            put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG,8L*1024*1024)
            put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG,ResolverRocksConfig::class.java)
            put(StreamsConfig.consumerPrefix("max.poll.records"),minOf(100,settings.maxPending/2))
            put(StreamsConfig.consumerPrefix("auto.offset.reset"),"earliest")
            put(StreamsConfig.consumerPrefix("allow.auto.create.topics"),false)
        }
        val lanes=ConcurrentHashMap<Int,Map<String,Any>>()
        val gate=ResolverConsumerGate()
        val topology=CalcifyResolverProcessor.topology(settings,{BrokerVenueSourceReader(bootstrap,settings,it)},
            {partition,blocked->ResolverConsumerGate.blocked(settings.verifiedTopic,partition,blocked)},
            {partition,stats->if(stats.isEmpty()) lanes.remove(partition) else {lanes[partition]=stats;println(JsonCodec.writeObject("type" to "calcify-resolver-lane","stats" to stats))}})
        val streams=KafkaStreams(topology,config,gate)
        val stopped=CountDownLatch(1)
        streams.setUncaughtExceptionHandler {ex->System.err.println("Calcify resolver infrastructure failure: ${ex.javaClass.simpleName}: ${ex.message}");StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT}
        streams.setStateListener {next,_->if(next==KafkaStreams.State.ERROR || next==KafkaStreams.State.NOT_RUNNING) stopped.countDown()}
        val health=HttpServer.create(InetSocketAddress(RuntimeEnv.string("CALCIFY_RESOLVER_HEALTH_BIND","127.0.0.1"),RuntimeEnv.int("CALCIFY_RESOLVER_HEALTH_PORT",8089,min=1)),0)
        health.createContext("/healthz") {exchange->
            val alive=streams.state()!=KafkaStreams.State.ERROR && streams.state()!=KafkaStreams.State.NOT_RUNNING
            val body=JsonCodec.writeObject("alive" to alive,"state" to streams.state().name).toByteArray()
            exchange.sendResponseHeaders(if(alive) 200 else 503,body.size.toLong());exchange.responseBody.use {it.write(body)}
        }
        health.createContext("/readyz") {exchange->
            val ready=streams.state()==KafkaStreams.State.RUNNING && lanes.values.none {it["fault"]!=""}
            val body=JsonCodec.writeObject("ready" to ready,"state" to streams.state().name,"lanes" to lanes.toSortedMap()).toByteArray()
            exchange.sendResponseHeaders(if(ready) 200 else 503,body.size.toLong());exchange.responseBody.use {it.write(body)}
        }
        health.createContext("/metrics") {exchange->
            val body=JsonCodec.writeObject("state" to streams.state().name,"lanes" to lanes.toSortedMap()).toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use {it.write(body)}
        }
        val shutdown=Thread {streams.close(Duration.ofSeconds(10));health.stop(0);stopped.countDown()}
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {health.start();streams.start();stopped.await()} finally {
            streams.close(Duration.ofSeconds(10));health.stop(0)
            try {Runtime.getRuntime().removeShutdownHook(shutdown)} catch(_:IllegalStateException) { }
        }
        check(streams.state()!=KafkaStreams.State.ERROR) {"Calcify resolver terminated with infrastructure error"}
    }

    private fun ensureTopics(bootstrap:String,settings:ResolverSettings,replication:Int) {
        AdminClient.create(Properties().apply {put("bootstrap.servers",bootstrap)}).use {admin->
            val names=listOf(settings.sourceTopic,settings.verifiedTopic)
            val existing=admin.describeTopics(names).allTopicNames().get(10,TimeUnit.SECONDS)
            val partitions=existing.getValue(settings.sourceTopic).partitions().size
            require(existing.getValue(settings.verifiedTopic).partitions().size==partitions) {"Calcify source/verified partition count mismatch"}
            val listed=admin.listTopics().names().get(10,TimeUnit.SECONDS)
            if(settings.outputTopic !in listed) {
                try {admin.createTopics(listOf(NewTopic(settings.outputTopic,partitions,replication.toShort()).configs(mapOf("cleanup.policy" to "delete","min.insync.replicas" to minOf(2,replication).toString())))).all().get(10,TimeUnit.SECONDS)} catch(ex:java.util.concurrent.ExecutionException) {if(ex.cause !is org.apache.kafka.common.errors.TopicExistsException) throw ex}
            }
            val all=admin.describeTopics(names+settings.outputTopic).allTopicNames().get(10,TimeUnit.SECONDS)
            for((name,description) in all) {
                require(description.partitions().size==partitions && description.partitions().all {it.replicas().size>=replication}) {"Calcify topic replication/partition mismatch: $name"}
                val resource=ConfigResource(ConfigResource.Type.TOPIC,name)
                val config=admin.describeConfigs(listOf(resource)).all().get(10,TimeUnit.SECONDS).getValue(resource)
                require(config.get("min.insync.replicas").value().toInt()>=minOf(2,replication)) {"Calcify topic min ISR too small: $name"}
                require("compact" !in config.get("cleanup.policy").value().split(',')) {"Calcify source/output history must not compact: $name"}
            }
        }
    }
}
