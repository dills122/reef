package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.calcify.finance.FinanceAttemptRow
import com.reef.platform.calcify.finance.FinanceCheckpointRow
import com.reef.platform.calcify.finance.FinanceDecisionRow
import com.reef.platform.calcify.finance.FinanceObligationRow
import com.reef.platform.calcify.finance.FinancePositionRow
import com.reef.platform.calcify.finance.FinanceReadStore
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S3-A1: the finance read routes through BOTH HTTP adapters (JDK server and the Netty hot-path server) with the same assertions. */
class CalcifyFinanceReadRoutesTest {
    private val binding = "a".repeat(64)
    private val other = "b".repeat(64)

    private class FakeStore : FinanceReadStore {
        val calls = AtomicInteger()
        var fail = false
        private fun <T> counted(block: () -> T): T { calls.incrementAndGet(); if (fail) throw IllegalStateException("jdbc:postgresql://secret-host/reef password=hunter2"); return block() }
        override fun checkpoint(bindingDigest: String) = counted { if (bindingDigest == "a".repeat(64)) FinanceCheckpointRow(bindingDigest, 1, 3, "c".repeat(64)) else null }
        override fun decisions(bindingDigest: String, afterSequence: Long, limit: Int) = counted {
            (1L..3L).filter { it > afterSequence }.take(limit).map { FinanceDecisionRow(it, "ns", "dom", "act-$it", "FUNDED", "d".repeat(64), "e".repeat(64), "f".repeat(64)) }
        }
        override fun positions(bindingDigest: String, accounts: Set<String>?) = counted {
            listOf(FinancePositionRow("acct-1", "USD", 100), FinancePositionRow("acct-2", "USD", -100)).filter { accounts == null || it.accountId in accounts }
        }
        override fun obligations(bindingDigest: String, accounts: Set<String>?) = counted {
            listOf(FinanceObligationRow("r", "s", "AAPL", "e1", "acct-1", "acct-2", 3, 100, "PENDING", 300, 3), FinanceObligationRow("r", "s", "AAPL", "e2", "acct-3", "acct-2", 1, 100, "SETTLED", 0, 0))
                .filter { accounts == null || it.buyerAccountId in accounts || it.sellerAccountId in accounts }
        }
        override fun attempts(bindingDigest: String, accounts: Set<String>?) = counted {
            listOf(FinanceAttemptRow("r", "s", "AAPL", "e1", 1, "INSUFFICIENT_CASH"), FinanceAttemptRow("r", "s", "AAPL", "e2", 1, "SETTLED"))
                .filter { accounts == null || (it.executionId == "e1" && ("acct-1" in accounts || "acct-2" in accounts)) || (it.executionId == "e2" && ("acct-3" in accounts || "acct-2" in accounts)) }
        }
    }

    private val authHook = object : AuthHook {
        override fun authorize(clientId: String, token: String?): BoundaryError? = null
        override fun principal(clientId: String) = when (clientId) {
            "trader-1" -> ExternalApiPrincipal(clientId, accountIds = setOf("acct-1"))
            "trader-2" -> ExternalApiPrincipal(clientId, accountIds = setOf("acct-2"))
            else -> ExternalApiPrincipal(clientId)          // authenticated, but with no account scope
        }
    }

    private fun delegate(store: FinanceReadStore?): PlatformHttpServer = PlatformHttpServer(
        port = 0, api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence())), boundary = ExternalApiBoundary(authHook = authHook),
        idempotencyStore = InMemoryIdempotencyStore(), idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(), financeReadStore = store)

    /** Runs [block] once per adapter with that adapter's port. */
    private fun eachAdapter(store: FinanceReadStore?, block: (adapter: String, port: Int) -> Unit) {
        val jdk = delegate(store).start()
        try { block("jdk", jdk.address.port) } finally { jdk.stop(0) }
        val netty = PlatformNettyHotPathServer(delegate = delegate(store), port = 0, applicationThreads = 2).start()
        try { block("netty", netty.port) } finally { netty.stop() }
    }

    private data class Resp(val status: Int, val body: String)
    private fun request(method: String, port: Int, path: String, headers: Map<String, String> = emptyMap()): Resp {
        val c = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method; c.instanceFollowRedirects = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
        return Resp(c.responseCode, stream?.bufferedReader()?.readText().orEmpty())
    }
    private fun get(port: Int, path: String, client: String? = null) = request("GET", port, path, client?.let { mapOf("X-Client-Id" to it) } ?: emptyMap())

    private val op = "/internal/boundary/calcify/finance"
    private val pt = "/api/v1/calcify/finance"

    @Test fun operatorRoutesReadEverythingForAnyBinding() = eachAdapter(FakeStore()) { adapter, port ->
        val checkpoint = get(port, "$op/$binding/checkpoint")
        assertEquals(200, checkpoint.status, adapter); assertContains(checkpoint.body, "\"lastSequence\":3"); assertContains(checkpoint.body, "\"bindingDigest\":\"$binding\"")
        val decisions = get(port, "$op/$binding/decisions?afterSequence=1&limit=1")
        assertContains(decisions.body, "\"sequence\":2"); assertFalse(decisions.body.contains("\"sequence\":3"), adapter)
        assertContains(get(port, "$op/$binding/positions").body, "acct-2")
        assertContains(get(port, "$op/$binding/obligations").body, "\"executionId\":\"e2\"")
        assertContains(get(port, "$op/$binding/attempts").body, "INSUFFICIENT_CASH")
        assertEquals(404, get(port, "$op/$other/checkpoint").status, "$adapter unknown binding")
    }

    @Test fun operatorRoutesRefuseMalformedRequests() = eachAdapter(FakeStore()) { adapter, port ->
        assertEquals(400, get(port, "$op/not-a-digest/checkpoint").status, adapter)
        assertEquals(400, get(port, "$op/${binding.uppercase()}/checkpoint").status, adapter)
        assertEquals(404, get(port, "$op/$binding/ledger").status, adapter)
        assertEquals(404, get(port, "$op/$binding").status, adapter)
        assertEquals(400, get(port, "$op/$binding/decisions?limit=0").status, adapter)
        assertEquals(400, get(port, "$op/$binding/decisions?limit=321").status, adapter)
        assertEquals(400, get(port, "$op/$binding/decisions?afterSequence=-1").status, adapter)
        for (method in listOf("POST", "PUT", "DELETE")) assertEquals(405, request(method, port, "$op/$binding/checkpoint").status, "$adapter $method")
    }

    @Test fun participantSeesOnlyItsOwnAccountsAndEverythingElseIs403() = eachAdapter(FakeStore()) { adapter, port ->
        val own = get(port, "$pt/$binding/positions?accountId=acct-1", "trader-1")
        assertEquals(200, own.status, adapter); assertContains(own.body, "acct-1"); assertFalse(own.body.contains("acct-2"), adapter)
        assertEquals(403, get(port, "$pt/$binding/positions?accountId=acct-2", "trader-1").status, "$adapter other account")
        assertEquals(403, get(port, "$pt/$binding/positions", "trader-1").status, "$adapter missing accountId")
        assertEquals(403, get(port, "$pt/$binding/positions?accountId=acct-1", "unscoped").status, "$adapter no account scope is a refusal, not unrestricted")
        assertEquals(401, get(port, "$pt/$binding/positions?accountId=acct-1").status, "$adapter no client")
        val obligations = get(port, "$pt/$binding/obligations?accountId=acct-1", "trader-1")
        assertContains(obligations.body, "\"executionId\":\"e1\""); assertFalse(obligations.body.contains("\"executionId\":\"e2\""), adapter)
        val counterparty = get(port, "$pt/$binding/obligations?accountId=acct-2", "trader-2")
        assertContains(counterparty.body, "e1"); assertContains(counterparty.body, "e2")
        val attempts = get(port, "$pt/$binding/attempts?accountId=acct-1", "trader-1")
        assertContains(attempts.body, "INSUFFICIENT_CASH"); assertFalse(attempts.body.contains("\"SETTLED\""), adapter)
    }

    @Test fun checkpointAndDecisionsAreOperatorOnlyOnTheParticipantRoutes() = eachAdapter(FakeStore()) { adapter, port ->
        for (resource in listOf("checkpoint", "decisions")) {
            assertEquals(403, get(port, "$pt/$binding/$resource", "trader-1").status, "$adapter $resource")
            assertEquals(403, get(port, "$pt/$binding/$resource?accountId=acct-1", "trader-1").status, "$adapter $resource with account")
        }
        assertEquals(401, get(port, "$pt/$binding/checkpoint").status, adapter)
        assertEquals(405, request("POST", port, "$pt/$binding/positions?accountId=acct-1", mapOf("X-Client-Id" to "trader-1")).status, adapter)
    }

    @Test fun eachRouteIsExactlyOneStoreQueryAndRefusalsNeverReachTheStore() {
        val store = FakeStore()
        eachAdapter(store) { adapter, port ->
            store.calls.set(0)
            for (path in listOf("$op/$binding/checkpoint", "$op/$binding/decisions", "$op/$binding/positions", "$op/$binding/obligations", "$op/$binding/attempts")) {
                val before = store.calls.get(); get(port, path); assertEquals(1, store.calls.get() - before, "$adapter $path")
            }
            val before = store.calls.get()
            get(port, "$pt/$binding/positions?accountId=acct-2", "trader-1"); get(port, "$pt/$binding/positions?accountId=acct-1"); get(port, "$pt/$binding/checkpoint", "trader-1"); get(port, "$op/zz/checkpoint")
            assertEquals(before, store.calls.get(), "$adapter refusals did not query")
        }
    }

    @Test fun unconfiguredStoreIs503AndStoreFailuresNeverLeakDriverText() {
        eachAdapter(null) { adapter, port ->
            assertEquals(503, get(port, "$op/$binding/checkpoint").status, adapter)
            assertEquals(503, get(port, "$pt/$binding/positions?accountId=acct-1", "trader-1").status, adapter)
        }
        eachAdapter(FakeStore().also { it.fail = true }) { adapter, port ->
            val r = get(port, "$op/$binding/positions")
            assertEquals(503, r.status, adapter); assertFalse(r.body.contains("hunter2") || r.body.contains("secret-host") || r.body.contains("jdbc"), "$adapter leaked: ${r.body}")
        }
    }
}
