package com.reef.platform.calcify.finance

import java.time.Duration
import java.util.Properties
import javax.sql.DataSource
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/** Research-only inventory. Logical payload, allocated disk and WAL traffic are separate quantities. */
internal class CapacityByteInventory(private val ds: DataSource, private val rp: DisposableRedpanda, private val pg: String) {
    private val topics = listOf("RATE_CAPTURE", "RATE_CONTROL", "RATE_DECISIONS", "capacity-rate-capacity-finance-changelog")
    private val starts = HashMap<TopicPartition, Long>()
    private var walStart = ""

    private fun sql(query: String): List<List<String>> = ds.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery(query).use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) ?: "0" }) } }
    } }

    private fun command(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        return out.trim().replace('\n', ';')
    }

    private fun consumer() = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        put("bootstrap.servers", rp.bootstrap); put("enable.auto.commit", false); put("isolation.level", "read_committed")
        put("key.deserializer", ByteArrayDeserializer::class.java.name); put("value.deserializer", ByteArrayDeserializer::class.java.name)
    })

    fun baseline() {
        consumer().use { c ->
            val partitions = topics.flatMap { t -> c.partitionsFor(t).map { TopicPartition(t, it.partition()) } }
            starts.putAll(c.endOffsets(partitions))
        }
        walStart = sql("SELECT pg_current_wal_insert_lsn()::text").single().single()
        sizes("baseline")
    }

    fun finish(trades: Int, decisionsPerTrade: Long, uncompacted: Boolean) {
        val wal = sql("SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(), '$walStart')::text").single().single()
        println("BYTE-WAL trades=$trades generated_bytes=$wal bytes_per_trade=${wal.toDouble() / trades}")
        sizes("final")
        consumer().use { c ->
            val ends = c.endOffsets(starts.keys)
            for (topic in topics) {
                val partitions = starts.keys.filter { it.topic() == topic }
                c.assign(partitions); partitions.forEach { c.seek(it, starts.getValue(it)) }
                var count = 0L; var keys = 0L; var values = 0L
                var echoedInput = 0L; var binaryKeySavings = 0L
                val deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos()
                while (partitions.any { c.position(it) < ends.getValue(it) }) {
                    check(System.nanoTime() < deadline) { "inventory timed out on $topic" }
                    for (r in c.poll(Duration.ofMillis(500))) {
                        if (r.offset() >= ends.getValue(TopicPartition(r.topic(), r.partition()))) continue
                        count++; keys += r.key()?.size ?: 0; values += r.value()?.size ?: 0
                        if (topic == "RATE_DECISIONS") {
                            val decision = reef.contracts.calcify.finance.v1.FinanceDecisionV1.parseFrom(r.value())
                            echoedInput += com.google.protobuf.CodedOutputStream.computeMessageSize(9, decision.input)
                        }
                        if (topic.endsWith("-changelog")) {
                            val key = r.key()
                            if (key != null && key.size >= 4 && key[0] == 'e'.code.toByte() && key[1] == '/'.code.toByte() && key[3] == '/'.code.toByte())
                                binaryKeySavings += (key.size - 4) / 2
                        }
                    }
                }
                val expected = when (topic) {
                    "RATE_CAPTURE" -> trades.toLong()
                    "RATE_CONTROL" -> 0L
                    "RATE_DECISIONS" -> trades * decisionsPerTrade
                    else -> if (uncompacted) trades * (if (decisionsPerTrade == 1L) 9L else 11L) else null
                }
                check(expected == null || count == expected) { "$topic: expected $expected records, counted $count" }
                println("BYTE-SHAPE topic=$topic echoed_input_bytes=$echoedInput binary_key_savings=$binaryKeySavings")
                println("BYTE-TOPIC topic=$topic trades=$trades records=$count keys=$keys values=$values records_per_trade=${count.toDouble()/trades} payload_per_trade=${(keys+values).toDouble()/trades}")
            }
        }
    }

    private fun sizes(phase: String) {
        for (row in sql("""SELECT c.relname, pg_relation_size(c.oid), pg_table_size(c.oid), pg_indexes_size(c.oid),
            CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_total_relation_size(c.reltoastrelid) END,
            pg_total_relation_size(c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='boundary' AND c.relkind='r' ORDER BY c.relname""")) {
            val count = sql("SELECT count(*) FROM boundary.${row[0]}").single().single()
            println("BYTE-PG phase=$phase table=${row[0]} heap=${row[1]} table_with_aux=${row[2]} indexes=${row[3]} toast=${row[4]} total=${row[5]} rows=$count")
        }
        for (row in sql("SELECT indexrelname, pg_relation_size(indexrelid) FROM pg_stat_user_indexes WHERE schemaname='boundary' ORDER BY indexrelname"))
            println("BYTE-INDEX phase=$phase index=${row[0]} bytes=${row[1]}")
        println("BYTE-DISK phase=$phase broker_kib=" + command("docker", "exec", rp.name, "du", "-sk", "/var/lib/redpanda/data"))
        for (topic in topics) println("BYTE-DISK phase=$phase topic=$topic kib=" + command("docker", "exec", rp.name, "du", "-sk", "/var/lib/redpanda/data/kafka/$topic"))
        println("BYTE-DISK phase=$phase postgres_kib=" + command("docker", "exec", pg, "du", "-sk", "/var/lib/postgresql/data"))
        println("BYTE-DISK phase=$phase wal_kib=" + command("docker", "exec", pg, "du", "-sk", "/var/lib/postgresql/data/pg_wal"))
        println("BYTE-SETTINGS phase=$phase " + sql("SELECT name,setting FROM pg_settings WHERE name IN ('wal_compression','full_page_writes','fsync','synchronous_commit')").joinToString())
    }
}
