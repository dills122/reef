package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat

/** Research probe of selected test kernel. No broker, managed adapter, SQL or capacity claim. */
object KernelProbe {
    private val json = ObjectMapper()
    private fun node(value: Any): JsonNode = json.valueToTree(value)
    private fun action(id: Int, kind: String): JsonNode {
        val execution = "probe-$id"
        val payload = if (kind == "CAPTURE") mapOf("executionId" to execution, "runId" to "probe",
            "venueSessionId" to "venue", "instrumentId" to "ACME", "buyOrderId" to "buy-$id",
            "sellOrderId" to "sell-$id", "buyerAccount" to "buyer", "sellerAccount" to "seller",
            "cashAsset" to "USD_NANO", "securityAsset" to "ACME_SHARE", "quantity" to "1",
            "priceNanos" to "10000000", "dueTick" to "0")
        else mapOf("executionId" to execution, "runId" to "probe", "venueSessionId" to "venue",
            "instrumentId" to "ACME", "attempt" to "1")
        return node(mapOf("namespace" to "research", "domain" to "hot", "actionId" to "$execution-$kind",
            "kind" to kind, "payload" to payload))
    }
    private fun owner(count: Int, cash: Long = count * 10000000L, history: Boolean = false) = FinancialKernel(
        node(mapOf("buyerCash" to cash.toString(), "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to count.toString())),
        node(mapOf("policy" to "gross-p1")), retainHistory = history)

    private fun checkEconomics(owner: FinancialKernel, count: Int) {
        val state = owner.ownerView()
        check(state["balances"]["buyerCash"].asText() == "0")
        check(state["balances"]["sellerCash"].asText() == (count * 10000000L).toString())
        check(state["balances"]["buyerShares"].asText() == count.toString())
        check(state["balances"]["sellerShares"].asText() == "0")
        check(state["obligations"].size() == count)
        check(state["obligations"].all { it["status"].asText() == "SETTLED" })
        check(owner.historySequence() == count * 2L + 1)
    }
    private fun correctness(): Map<String, Any> {
        val owner = owner(2, history = true)
        for (id in 0..1) {
            check(owner.execute(action(id, "CAPTURE"), false)["disposition"].asText() == "CAPTURED")
            check(owner.execute(action(id, "SETTLE"), false)["disposition"].asText() == "SETTLED")
        }
        checkEconomics(owner, 2)
        val before = owner.historySequence()
        check(owner.execute(action(1, "SETTLE"), false)["disposition"].asText() == "PRIOR_RESULT")
        check(owner.historySequence() == before)
        check(FinancialKernel.replay(owner.history(), false).ownerView() == owner.ownerView())
        check(FinancialKernel.restore(owner.checkpoint(), emptyList(), false).ownerView() == owner.ownerView())
        val poor = owner(1, cash = 0, history = true)
        poor.execute(action(0, "CAPTURE"), false)
        val failure = poor.execute(action(0, "SETTLE"), false)
        check(failure["disposition"].asText() == "INSUFFICIENT_CASH" && failure["journalLegs"].asInt() == 0)
        val changed = action(0, "CAPTURE").deepCopy<ObjectNode>()
        (changed["payload"] as ObjectNode).put("quantity", "2")
        check(poor.execute(changed, false)["disposition"].asText() == "ACTION_CONFLICT")
        check(poor.ownerView()["obligations"].size() == 1)
        val broken = owner.history().map { it.deepCopy<JsonNode>() }
        (broken.last() as ObjectNode).put("checksum", "broken")
        check(runCatching { FinancialKernel.replay(broken, false) }.isFailure)
        return mapOf("fullyFunded" to true, "duplicateNoHistory" to true, "replayExact" to true,
            "checkpointRestoreExact" to true, "insufficientCashZeroLegs" to true,
            "changedRetryConflict" to true, "corruptHistoryRejected" to true)
    }
    private fun canonicalCorpusDigest(): String {
        val method = FinancialKernel.Companion.javaClass.getDeclaredMethod("canonical", JsonNode::class.java)
        method.isAccessible = true
        val digest = MessageDigest.getInstance("SHA-256")
        val random = java.util.Random(20261011)
        repeat(1000) { index ->
            val value = json.createObjectNode().apply {
                put("z\"\\\n", "Unicode \uD83E\uDDAD / \t \u0000 value-$index")
                set<JsonNode>("a", node(listOf(null, true, false, random.nextLong(), "", "é")))
                put("large", java.math.BigInteger("123456789012345678901234567890").add(java.math.BigInteger.valueOf(index.toLong())))
                put("decimal", java.math.BigDecimal("1.2300E+40"))
                put("double", random.nextDouble())
                set<JsonNode>("nested", node(mapOf("b" to index, "a" to emptyList<String>())))
            }
            val encoded = method.invoke(FinancialKernel.Companion, value) as String
            digest.update(encoded.toByteArray(Charsets.UTF_8)); digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }
    @JvmStatic fun main(args: Array<String>) {
        val count = args.firstOrNull()?.toInt() ?: 10000
        require(count in 1000..50000)
        val checks = correctness()
        val warm = owner(2000)
        repeat(2000) { id -> warm.execute(action(id, "CAPTURE"), false); warm.execute(action(id, "SETTLE"), false) }
        checkEconomics(warm, 2000)
        val results = mutableListOf<Map<String, Any>>()
        for (encode in listOf(false, true, true, false)) {
            val owner = owner(count)
            var bytes = 0L
            val windows = mutableListOf<Double>()
            val heap = ManagementFactory.getMemoryMXBean()
            var sampledHeap = heap.heapMemoryUsage.used
            val start = System.nanoTime()
            var windowStart = start
            repeat(count) { id ->
                check(owner.execute(action(id, "CAPTURE"), false)["disposition"].asText() == "CAPTURED")
                if (encode) bytes += json.writeValueAsBytes(owner.lastRecord()).size
                check(owner.execute(action(id, "SETTLE"), false)["disposition"].asText() == "SETTLED")
                if (encode) bytes += json.writeValueAsBytes(owner.lastRecord()).size
                if ((id + 1) % 1000 == 0) {
                    val now = System.nanoTime()
                    windows.add((now - windowStart) / 1000000.0); windowStart = now
                    sampledHeap = maxOf(sampledHeap, heap.heapMemoryUsage.used)
                }
            }
            val elapsed = (System.nanoTime() - start) / 1000000.0
            checkEconomics(owner, count)
            results.add(mapOf("encodeResult" to encode, "trades" to count, "actions" to count * 2,
                "elapsedMs" to elapsed, "tradesPerSecond" to count * 1000.0 / elapsed,
                "each1000TradesMs" to windows, "encodedHistoryBytesExcludingGenesis" to bytes,
                "sampledHeapBytes" to sampledHeap, "finalEconomicsChecked" to true,
                "finalHistoryChecksum" to owner.lastRecord()!!["checksum"].asText()))
        }
        println(json.writeValueAsString(mapOf("schema" to "calcify-kernel-research-v1", "checks" to checks,
            "canonicalCorpusCases" to 1000, "canonicalCorpusSha256" to canonicalCorpusDigest(),
            "java" to System.getProperty("java.version"), "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "scope" to "selected test kernel including action construction; no broker/SQL; sampled heap shared JVM, no attribution or hard bound; sequential ABBA diagnostic, not controlled benchmark",
            "warmupTrades" to 2000, "results" to results)))
    }
}
