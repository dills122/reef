package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.kafka.clients.admin.LogDirDescription
import org.apache.kafka.clients.admin.ReplicaInfo
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.Node
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.TopicPartitionInfo
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.errors.KafkaStorageException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.DescribeTopicsResult
import org.apache.kafka.clients.admin.DescribeLogDirsResult
import org.apache.kafka.clients.admin.DescribeTopicsOptions
import org.apache.kafka.clients.admin.DescribeLogDirsOptions
import org.apache.kafka.common.KafkaFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinancialPhysicalInventoryTest {
    private val json = ObjectMapper()
    private val names = listOf("owned-input", "owned-results", "owned-app-financial-state-changelog")
    private val nodes = (0..2).map { Node(it, "broker$it", 9092) }
    private fun topics() = names.mapIndexed { i, name -> name to TopicDescription(name, false,
        listOf(TopicPartitionInfo(0, nodes[0], nodes, nodes)), emptySet(), Uuid(1, i.toLong() + 1)) }.toMap()
    private fun dirs() = (0..2).associateWith { mapOf("/var/lib/redpanda/data" to LogDirDescription(null,
        names.associate { TopicPartition(it, 0) to ReplicaInfo(123, 0, false) })) }
    private fun manifest(): ObjectNode = json.valueToTree(mapOf("runId" to "test", "provenance" to mapOf("hash" to "frozen"),
        "registeredResources" to mapOf("containers" to (0..2).map { mapOf("brokerId" to it) }),
        "topics" to topics().values.map { mapOf("name" to it.name(), "topicId" to it.topicId().toString(),
            "partitions" to listOf(mapOf("partition" to 0, "replicas" to listOf(0,1,2)))) }))
    @Test fun `actual RF3 replies bind raw receipts and normalized identities`() {
        val s = FinancialPhysicalInventory.inventoryFromReplies(manifest(), topics(), dirs(), 1000, 1001)
        assertEquals(9, s["replicas"].size()); assertEquals(1107, s["replicas"].sumOf { it["sizeBytes"].intValue() })
        assertEquals(listOf("describeTopics", "describeLogDirs"), s["rawReceipts"].map { it["operation"].asText() })
        s["rawReceipts"].forEach { receipt ->
            val bytes = receipt["rawJSON"].asText().toByteArray()
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, receipt["bytesSha256"].asText())
            assertTrue(json.readTree(bytes)["schema"].asText().startsWith("calcify-admin-"))
        }
    }
    @Test fun `actual API sizes and foreign rows retained without ownership`() {
        val d = dirs().toMutableMap(); val rows = d.getValue(0).getValue("/var/lib/redpanda/data").replicaInfos().toMutableMap()
        rows[TopicPartition(names[0],0)] = ReplicaInfo(321,7,false); rows[TopicPartition("foreign",0)] = ReplicaInfo(999,0,false)
        d[0] = mapOf("/var/lib/redpanda/data" to LogDirDescription(null,rows))
        val s = FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),d,1000,1001)
        assertEquals(321, s["replicas"].first { it["brokerId"].asInt()==0 && it["topic"].asText()==names[0] }["sizeBytes"].asInt())
        assertEquals(9,s["replicas"].size()); assertTrue(s["rawReceipts"][1]["rawJSON"].asText().contains("foreign"))
    }
    @Test fun `UUID recreation assignment drift and missing topics refuse`() {
        val base=topics(); val changed=base.toMutableMap()
        changed[names[0]]=TopicDescription(names[0],false,listOf(TopicPartitionInfo(0,nodes[0],nodes,nodes)),emptySet(),Uuid(7,7))
        assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),changed,dirs(),1000,1001) }
        changed[names[0]]=TopicDescription(names[0],false,listOf(TopicPartitionInfo(0,nodes[0],nodes.reversed(),nodes)),emptySet(),base.getValue(names[0]).topicId())
        assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),changed,dirs(),1000,1001) }
        assertFailsWith<IllegalArgumentException> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),base-names[0],dirs(),1000,1001) }
    }
    @Test fun `RF2 broker drift missing and duplicate replicas refuse`() {
        val changed=topics().toMutableMap(); val t=changed.getValue(names[0])
        changed[names[0]]=TopicDescription(names[0],false,listOf(TopicPartitionInfo(0,nodes[0],nodes.take(2),nodes.take(2))),emptySet(),t.topicId())
        assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),changed,dirs(),1000,1001) }
        assertFailsWith<IllegalArgumentException> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),dirs()-2,1000,1001) }
        val missing=dirs().toMutableMap(); missing[0]=mapOf("/var/lib/redpanda/data" to LogDirDescription(null,emptyMap()))
        assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),missing,1000,1001) }
        val duplicate=dirs().toMutableMap(); duplicate[0]=duplicate.getValue(0)+("/var/lib/redpanda/data/copy" to duplicate.getValue(0).values.first())
        assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),duplicate,1000,1001) }
    }
    @Test fun `actual log errors future negative unsafe partition path refuse with raw`() {
        for (replica in listOf(ReplicaInfo(123,0,true), ReplicaInfo(-1,0,false),ReplicaInfo(9007199254740992L,0,false))) {
            val d=dirs().toMutableMap(); val rows=d.getValue(0).values.first().replicaInfos().toMutableMap(); rows[TopicPartition(names[0],0)]=replica
            d[0]=mapOf("/var/lib/redpanda/data" to LogDirDescription(null,rows))
            assertTrue(assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),d,1000,1001) }.evidence["rawReceipts"].size()==2)
        }
        for (directory in listOf("/foreign", "/var/lib/redpanda/data/../escape")) {
            val d=dirs().toMutableMap(); d[0]=mapOf(directory to d.getValue(0).values.first())
            assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),d,1000,1001) }
        }
        val d=dirs().toMutableMap(); d[0]=mapOf("/var/lib/redpanda/data" to LogDirDescription(KafkaStorageException("actual failed"),emptyMap()))
        val failure=assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),d,1000,1001) }
        assertTrue(failure.evidence["rawReceipts"][1]["rawJSON"].asText().contains("actual failed"))
    }
    @Test fun `unsafe reversed or overlong clocks refuse`() {
        for ((start,end) in listOf(-1L to 1L,1001L to 1000L,1000L to 6001L,9007199254740992L to 9007199254740992L))
            assertFailsWith<IllegalArgumentException> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),dirs(),start,end) }
    }
    @Test fun `finite raw metadata bound includes foreign rows`() {
        val d=dirs().toMutableMap(); d[0]=mapOf("/var/lib/redpanda/data" to LogDirDescription(null,
            (0..8192).associate { TopicPartition("foreign-$it",0) to ReplicaInfo(1,0,false) }))
        assertFailsWith<IllegalArgumentException> { FinancialPhysicalInventory.inventoryFromReplies(manifest(),topics(),d,1000,1001) }
    }
    private fun nodeExecutable(): Path {
        val path = requireNotNull(System.getenv("PATH")) { "Node executable requires PATH" }
        require(path.length <= 32768) { "Node executable PATH exceeds bounded discovery" }
        val directories = path.split(java.io.File.pathSeparator)
        require(directories.size <= 256) { "Node executable PATH has too many entries" }
        return directories.asSequence().filter { it.isNotBlank() }.map { Path.of(it).resolve("node") }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toRealPath()
            ?: error("Node executable missing from PATH")
    }
    private fun adapterScript()=Path.of("../../scripts/dev/calcify-financial/physical-adapter.mjs").toRealPath()
    @Test fun `actual pinned Node clocks cover host interval without assuming JVM origin`() {
        val receipt=FinancialPhysicalInventory.captureHostClock(nodeExecutable(),adapterScript())
        val after=FinancialPhysicalInventory.captureHostClock(nodeExecutable(),adapterScript())
        assertTrue(receipt["verified"].asBoolean())
        assertTrue(after["sample"]["sampledAtMs"].longValue() >= receipt["sample"]["sampledAtMs"].longValue())
        assertEquals("jvm-system-nano",receipt["jvmClockScope"].asText())
        assertEquals(receipt["sample"]["sampledAtMs"].longValue() in receipt["jvmStartedAtMs"].longValue()..receipt["jvmCompletedAtMs"].longValue(),receipt["sameOriginObserved"].asBoolean())
        assertEquals(nodeExecutable().toString(),receipt["nodeExecutable"].asText())
        println(receipt)
    }
    @Test fun `clock drift process errors output overrun and hang refuse bounded`() {
        val root=Files.createTempDirectory("physical-clock-control").toRealPath()
        try {
            for ((name,source) in listOf("drift" to "console.log(JSON.stringify({clockScope:'invented-origin',sampledAtMs:0}))",
                "error" to "console.error('actual failure');process.exit(7)","overrun" to "console.log('x'.repeat(40000))",
                "hang" to "setInterval(()=>{},1000)")) {
                val script=root.resolve("$name.mjs"); Files.writeString(script,source)
                assertFailsWith<IllegalStateException> { FinancialPhysicalInventory.captureHostClock(nodeExecutable(),script) }
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
    }
    @Test fun `actual Admin API exception retains request and failure receipt`() {
        val admin=Proxy.newProxyInstance(Admin::class.java.classLoader,arrayOf(Admin::class.java)) { _,method,_ ->
            if(method.name=="describeTopics") throw KafkaStorageException("actual admin exception")
            error("unexpected ${method.name}")
        } as Admin
        val clock=FinancialPhysicalInventory.captureHostClock(nodeExecutable(),adapterScript())
        val failure=assertFailsWith<FinancialPhysicalInventory.Failure> { FinancialPhysicalInventory.snapshot(admin,manifest(),clock) }
        assertTrue(failure.evidence["error"]["message"].asText().contains("actual admin exception"))
        assertEquals(names,failure.evidence["topicRequest"].map { it.asText() })
    }
    @Test fun `actual Admin future path binds both host samples and original JVM operation windows`() {
        val requests=mutableListOf<String>()
        val topicResult=DescribeTopicsResult::class.java.declaredConstructors.single().let { c ->
            c.isAccessible=true; c.newInstance(null,topics().mapValues { KafkaFuture.completedFuture(it.value) }) as DescribeTopicsResult
        }
        val dirResult=DescribeLogDirsResult::class.java.declaredConstructors.single().let { c ->
            c.isAccessible=true; c.newInstance(dirs().mapValues { KafkaFuture.completedFuture(it.value) }) as DescribeLogDirsResult
        }
        val admin=Proxy.newProxyInstance(Admin::class.java.classLoader,arrayOf(Admin::class.java)) { _,method,args ->
            requests.add(method.name)
            when(method.name) {
                "describeTopics" -> { assertEquals(names,args!![0]); assertEquals(3000,(args[1] as DescribeTopicsOptions).timeoutMs()); topicResult }
                "describeLogDirs" -> { assertEquals(listOf(0,1,2),args!![0]); assertEquals(3000,(args[1] as DescribeLogDirsOptions).timeoutMs()); dirResult }
                else -> error("unexpected ${method.name}")
            }
        } as Admin
        val s=FinancialPhysicalInventory.snapshot(admin,manifest(),FinancialPhysicalInventory.captureHostClock(nodeExecutable(),adapterScript()))
        assertEquals(listOf("describeTopics","describeLogDirs"),requests)
        assertEquals("actual-host-covering-bracket",s["window"]["kind"].asText())
        assertEquals(s["hostClockReceipts"][0]["sample"]["sampledAtMs"].longValue(),s["window"]["startedAtMs"].longValue())
        assertEquals(s["hostClockReceipts"][1]["sample"]["sampledAtMs"].longValue(),s["window"]["completedAtMs"].longValue())
        s["rawReceipts"].forEach { r -> assertEquals("jvm-system-nano",r["jvmMonotonicWindow"]["clockScope"].asText()); assertEquals(s["window"]["startedAtMs"],r["startedAtMs"])
            assertEquals(s["window"]["completedAtMs"],r["completedAtMs"]) }
        assertEquals(9,s["replicas"].size())
    }
}
