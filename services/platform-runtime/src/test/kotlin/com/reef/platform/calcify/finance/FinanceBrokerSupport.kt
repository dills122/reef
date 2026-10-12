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
    val bootstrap = "127.0.0.1:$port"

    fun start(): DisposableRedpanda {
        val run = ProcessBuilder("docker", "run", "-d", "--rm", "--name", name, "-p", "$port:$port", IMAGE, "redpanda", "start",
            "--mode", "dev-container", "--smp", "1", "--memory", "1G", "--reserve-memory", "0M", "--overprovisioned", "--node-id", "0", "--check=false",
            "--kafka-addr", "0.0.0.0:$port", "--advertise-kafka-addr", "127.0.0.1:$port").redirectErrorStream(true).start()
        val out = run.inputStream.bufferedReader().readText()
        check(run.waitFor() == 0) { "docker run failed: $out" }
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (true) {
            try { admin().use { it.describeCluster().clusterId().get(5, TimeUnit.SECONDS); it.createTopics(listOf(NewTopic("readiness-probe", 1, 1.toShort()))).all().get(8, TimeUnit.SECONDS); it.deleteTopics(listOf("readiness-probe")).all().get(8, TimeUnit.SECONDS) }; return this }
            catch (e: Exception) { if (System.nanoTime() >= deadline) { val logs = ProcessBuilder("docker", "logs", "--tail", "30", name).redirectErrorStream(true).start().inputStream.bufferedReader().readText(); error("redpanda not ready: $e\n$logs") }; Thread.sleep(500) }
        }
    }

    fun admin(): AdminClient = AdminClient.create(Properties().apply { put("bootstrap.servers", bootstrap); put("request.timeout.ms", 10000); put("default.api.timeout.ms", 15000) })
    override fun close() { ProcessBuilder("docker", "rm", "-f", "-v", name).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }

    companion object { const val IMAGE = "redpandadata/redpanda:v26.2.3" }
}

/** Redpanda with SASL/SCRAM and authorization enabled; users are created through rpk inside the container before SASL is switched on. */
internal class SecuredRedpanda(private val users: List<String>) : AutoCloseable {
    val broker = DisposableRedpanda()
    val bootstrap get() = broker.bootstrap
    fun password(user: String) = "${user}-pw"
    private fun rpk(vararg args: String): String {
        val p = ProcessBuilder(listOf("docker", "exec", broker.name, "rpk") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "rpk ${args.toList()} failed: $out" }; return out
    }
    fun start(): SecuredRedpanda {
        broker.start()
        for (u in listOf("admin") + users) rpk("security", "user", "create", u, "-p", password(u), "--mechanism", "SCRAM-SHA-256")
        rpk("cluster", "config", "set", "superusers", "[\"admin\"]")
        rpk("cluster", "config", "set", "enable_sasl", "true")
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            try { AdminClient.create(auth("admin").apply { put("request.timeout.ms", 5000); put("default.api.timeout.ms", 8000); put("bootstrap.servers", bootstrap) }).use { it.describeCluster().clusterId().get(8, TimeUnit.SECONDS); it.createTopics(listOf(NewTopic("readiness-probe", 1, 1.toShort()))).all().get(8, TimeUnit.SECONDS); it.deleteTopics(listOf("readiness-probe")).all().get(8, TimeUnit.SECONDS) }; return this }
            catch (e: Exception) { check(System.nanoTime() < deadline) { "secured redpanda not ready: $e" }; Thread.sleep(500) }
        }
    }
    fun auth(user: String, password: String = password(user)) = Properties().apply {
        put("security.protocol", "SASL_PLAINTEXT"); put("sasl.mechanism", "SCRAM-SHA-256")
        put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"$user\" password=\"$password\";")
    }
    fun admin(user: String): AdminClient = AdminClient.create(auth(user).apply { put("bootstrap.servers", bootstrap); put("request.timeout.ms", 10000); put("default.api.timeout.ms", 15000) })
    override fun close() = broker.close()
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
    val bindingDigest = captureBinding.digest      // one digest per run: the store, the projection and the envelope check share it
    val config = FinanceProcessorConfig(bindingDigest, captureBinding, finance, CAPTURE, CONTROL, DECISIONS, APP_ID)
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

    fun streamsProperties(bootstrap: String, stateDir: String) = FinanceTopology.streamsProperties(APP_ID, bootstrap, stateDir).apply {
        // A SIGKILLed member must be evicted quickly, or the next start waits out the default session timeout before it owns the partition.
        put(StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG), 6000); put(StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG), 1000)
    }

    fun streams(bootstrap: String, stateDir: String, auth: Properties = Properties()) = KafkaStreams(FinanceTopology.build(config, persistent = true), streamsProperties(bootstrap, stateDir).apply { putAll(auth) })

    fun createTopics(admin: AdminClient) {
        val history = mapOf("retention.ms" to "-1", "retention.bytes" to "-1", "cleanup.policy" to "delete")
        // The changelog is a registered binding topic provisioned up front (compacted), not something the app creates on first start.
        admin.createTopics(listOf(CAPTURE, CONTROL, DECISIONS).map { NewTopic(it, 1, 1.toShort()).configs(history) } +
            NewTopic(changelog, 1, 1.toShort()).configs(mapOf("cleanup.policy" to "compact"))).all().get(30, TimeUnit.SECONDS)
    }

    fun producer(bootstrap: String, auth: Properties = Properties()) = KafkaProducer<ByteArray, ByteArray>(Properties().apply {
        putAll(auth)
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ProducerConfig.ACKS_CONFIG, "all"); put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name); put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
    })

    fun send(producer: KafkaProducer<ByteArray, ByteArray>, topic: String, value: ByteArray) { producer.send(ProducerRecord(topic, 0, ByteArray(0), value)).get(15, TimeUnit.SECONDS) }

    private fun consumer(bootstrap: String, auth: Properties = Properties()) = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        putAll(auth)
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); put(ConsumerConfig.GROUP_ID_CONFIG, "reader-${System.nanoTime()}")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    })

    /** Every read-committed record of a topic from offset 0 to its read-committed end. */
    fun readAll(bootstrap: String, topic: String, auth: Properties = Properties()): List<Pair<ByteArray?, ByteArray?>> = consumer(bootstrap, auth).use { c ->
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
    fun uncommittedEnd(bootstrap: String, topic: String, auth: Properties = Properties()): Long = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        putAll(auth)
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap); put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name); put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    }).use { it.endOffsets(listOf(TopicPartition(topic, 0))).getValue(TopicPartition(topic, 0)) }

    /** First record a read-committed consumer sees at or after [offset], or null when none exists before the read-committed end. */
    fun firstVisible(bootstrap: String, topic: String, offset: Long, auth: Properties = Properties()): Long? = consumer(bootstrap, auth).use { c ->
        val tp = TopicPartition(topic, 0); c.assign(listOf(tp)); c.seek(tp, offset)
        val end = c.endOffsets(listOf(tp)).getValue(tp)
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (c.position(tp) < end && System.nanoTime() < deadline) { c.poll(Duration.ofMillis(200)).firstOrNull()?.let { return@use it.offset() } }
        null
    }

    fun readCommittedEnd(bootstrap: String, topic: String, auth: Properties = Properties()): Long = consumer(bootstrap, auth).use { it.endOffsets(listOf(TopicPartition(topic, 0))).getValue(TopicPartition(topic, 0)) }

    fun committed(admin: AdminClient, topic: String): Long? =
        admin.listConsumerGroupOffsets(APP_ID).partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS)[TopicPartition(topic, 0)]?.offset()

    fun waitUntil(what: String, seconds: Long = 90, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(200) }
    }

    /** The real evidence an activation verifier reads: production [FinanceEvidenceReader] over the given client properties. */
    fun evidence(bootstrap: String, @Suppress("UNUSED_PARAMETER") admin: AdminClient, auth: Properties = Properties()): FinanceRestoreEvidence =
        FinanceEvidenceReader(Properties().apply { putAll(auth); put("bootstrap.servers", bootstrap) }, APP_ID, bindingDigest, CAPTURE, CONTROL, DECISIONS, changelog).read()
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
