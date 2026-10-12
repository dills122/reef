package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import java.net.ServerSocket
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1

/** A single-node Redpanda in a throwaway container on a host port; removed on close. Docker CLI only, default bridge network. */
internal class DisposableRedpanda : AutoCloseable {
    val port = ServerSocket(0).use { it.localPort }
    val name = "reef-finance-it-${System.nanoTime().toString(36)}"
    val bootstrap = "localhost:$port"

    fun start(): DisposableRedpanda {
        val run = ProcessBuilder("docker", "run", "-d", "--rm", "--name", name, "-p", "$port:$port", IMAGE, "redpanda", "start",
            "--mode", "dev-container", "--smp", "1", "--memory", "1G", "--reserve-memory", "0M", "--overprovisioned", "--node-id", "0", "--check=false",
            "--kafka-addr", "0.0.0.0:$port", "--advertise-kafka-addr", "localhost:$port").redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        check(run.waitFor() == 0) { "docker run failed: $out" }
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (true) {
            try { admin().use { it.describeCluster().clusterId().get(5, TimeUnit.SECONDS) }; return this }
            catch (e: Exception) { check(System.nanoTime() < deadline) { "redpanda not ready: $e" }; Thread.sleep(500) }
        }
    }

    fun admin(): AdminClient = AdminClient.create(Properties().apply { put("bootstrap.servers", bootstrap); put("request.timeout.ms", 10000); put("default.api.timeout.ms", 15000) })
    override fun close() { ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }

    companion object { const val IMAGE = "redpandadata/redpanda:v26.2.3" }
}

internal object FinanceBrokerFixture {
    const val APP_ID = "finance-it"
    const val CAPTURE = "IT_CAPTURE"
    const val CONTROL = "IT_CONTROL"
    const val DECISIONS = "IT_DECISIONS"
    val changelog = "$APP_ID-finite-finance-changelog"
    val buyer = AccountId("buyer-account")
    val seller = AccountId("seller-account")
    val usd = AssetId("USD")
    val aapl = AssetId("fixture-base-AAPL")
    const val PRICE = 100_000_000_000L
    val captureBinding = FiniteLifecycleFixtures.binding()
    val finance = FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("p3-opening"))
    val bindingDigest = FiniteLifecycleContract.sha("binding-v2-it".toByteArray())
    val config = FinanceProcessorConfig(bindingDigest, captureBinding, finance, CAPTURE, CONTROL, DECISIONS)
    val ledger = FinanceLedger(finance)

    fun fund(id: String, account: AccountId, asset: AssetId, amount: Long) = FinanceInput.Fund(ActionKey("it", "control", id), Authority.OPERATOR, account, asset, amount)
    fun settle(executionId: String, attempt: Int) = FinanceInput.Settle(ActionKey("it", "repair", "$executionId-$attempt"), Authority.OPERATOR, ExecutionKey("p3-run", "p3-session", "AAPL", executionId), attempt)
    fun executionId(i: Int) = "exec-it-$i"

    /** A valid capture envelope with one ready member of 1 unit at [PRICE], derived from the real fixture so every other field is genuine. */
    fun envelope(i: Int): FiniteLifecycleCaptureV1 {
        val base = FiniteLifecycleFixtures.sequence().completedRecordsList.single { it.source.sourceOffset == 2L }
        val ready = base.membersList.indexOfFirst { it.disposition.name == "LIFECYCLE_READY_EXECUTION" }
        val edited = base.toBuilder().also { b ->
            b.getMembersBuilder(ready).tradeBuilder.factBuilder.setExecutionId(executionId(i)).quantityBuilder.setUnits("1")
            b.getMembersBuilder(ready).tradeBuilder.factBuilder.priceBuilder.setNanos(PRICE.toString())
        }.build()
        // Keep exactly one ready member: drop the second trade so every envelope books once.
        val keep = edited.membersList.withIndex().filter { (idx, m) -> m.disposition.name != "LIFECYCLE_READY_EXECUTION" || idx == ready }.map { it.value }
        val one = edited.toBuilder().clearMembers().addAllMembers(keep).setMemberCount(keep.size).build()
        return one.toBuilder().setContentDigest(FiniteLifecycleContract.digest(one)).build()
    }

    fun streamsProperties(bootstrap: String, stateDir: String) = Properties().apply {
        put(StreamsConfig.APPLICATION_ID_CONFIG, APP_ID); put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2); put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1)
        put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 0); put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1); put(StreamsConfig.STATE_DIR_CONFIG, stateDir)
        put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100); put(StreamsConfig.MAX_TASK_IDLE_MS_CONFIG, 0)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        // A SIGKILLed member must be evicted quickly, or the next start waits out the default session timeout before it owns the partition.
        put(StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG), 6000); put(StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG), 1000)
    }

    fun streams(bootstrap: String, stateDir: String) = KafkaStreams(FinanceTopology.build(config, persistent = true), streamsProperties(bootstrap, stateDir))

    fun createTopics(admin: AdminClient) {
        val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
        admin.createTopics(listOf(CAPTURE, CONTROL, DECISIONS).map { NewTopic(it, 1, 1.toShort()).configs(history) }).all().get(30, TimeUnit.SECONDS)
    }

    fun producer(bootstrap: String) = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
    })

    fun send(producer: KafkaProducer<ByteArray, ByteArray>, topic: String, value: ByteArray) { producer.send(ProducerRecord(topic, 0, ByteArray(0), value)).get(15, TimeUnit.SECONDS) }

    private fun consumer(bootstrap: String) = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); put(ConsumerConfig.GROUP_ID_CONFIG, "reader-${System.nanoTime()}")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    })

    /** Every read-committed record of a topic from offset 0 to its read-committed end. */
    fun readAll(bootstrap: String, topic: String): List<Pair<ByteArray?, ByteArray?>> = consumer(bootstrap).use { c ->
        val tp = TopicPartition(topic, 0); c.assign(listOf(tp)); c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp)).getValue(tp)
        val out = ArrayList<Pair<ByteArray?, ByteArray?>>()
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (c.position(tp) < end) {
            c.poll(Duration.ofMillis(200)).forEach { out += it.key() to it.value() }
            check(System.nanoTime() < deadline) { "timed out reading $topic" }
        }
        out
    }

    /** End offset including records of an open (not yet committed or aborted) transaction; greater than the read-committed end while one is in flight. */
    fun uncommittedEnd(bootstrap: String, topic: String): Long = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    }).use { it.endOffsets(listOf(TopicPartition(topic, 0))).getValue(TopicPartition(topic, 0)) }

    fun readCommittedEnd(bootstrap: String, topic: String): Long = consumer(bootstrap).use { it.endOffsets(listOf(TopicPartition(topic, 0))).getValue(TopicPartition(topic, 0)) }

    fun committed(admin: AdminClient, topic: String): Long? =
        admin.listConsumerGroupOffsets(APP_ID).partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS)[TopicPartition(topic, 0)]?.offset()

    fun waitUntil(what: String, seconds: Long = 90, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(200) }
    }

    /** The real evidence an activation verifier would read: decision log, changelog contents, committed offsets and ends. */
    fun evidence(bootstrap: String, admin: AdminClient): FinanceRestoreEvidence {
        val store = com.reef.platform.calcify.MemoryLifecycleKv()
        for ((k, v) in readAll(bootstrap, changelog)) if (v != null) store.entries[String(k!!, Charsets.UTF_8)] = v else store.entries.remove(String(k!!, Charsets.UTF_8))
        return FinanceRestoreEvidence(bindingDigest, store, readAll(bootstrap, DECISIONS).map { it.second!! },
            committed(admin, CAPTURE), committed(admin, CONTROL), readCommittedEnd(bootstrap, CAPTURE), readCommittedEnd(bootstrap, CONTROL))
    }
}

/** Child-JVM entry point for the crash test: runs the finance Streams app until it is killed. */
internal object FinanceStreamsApp {
    @JvmStatic fun main(args: Array<String>) {
        val streams = FinanceBrokerFixture.streams(args[0], args[1])
        streams.setUncaughtExceptionHandler { org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
        streams.start()
        println("FINANCE-APP-STARTED")
        Thread.currentThread().join()
    }
}
