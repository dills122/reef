package com.reef.arena.controlplane.arena

import com.reef.platform.api.AccountRiskCheckRequest
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.AllowAllAccountRiskCheck
import com.reef.platform.api.ChainedAccountRiskCheck
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

class ArenaBotVersionRiskCheckTest {
    @Test
    fun rejectsDisabledBotVersionBeforeDelegateRiskCheck() {
        val store = seededStore(ArenaBotVersionStatus.Quarantined)
        val check = chainedCheck(store)

        val result = check.evaluate(accountRiskRequest(botVersion = "v1"))

        assertEquals(AccountRiskDecision.DISABLED_BOT, result.decision)
        assertEquals("BOT_DISABLED", result.code)
        assertEquals("bot version is Quarantined: bot-1/v1", result.message)
    }

    @Test
    fun allowsApprovedBotVersionToContinue() {
        val store = seededStore(ArenaBotVersionStatus.Approved)
        val check = chainedCheck(store)

        val result = check.evaluate(accountRiskRequest(botVersion = "v1"))

        assertEquals(AccountRiskDecision.ALLOW, result.decision)
    }

    @Test
    fun ignoresRequestsWithoutBotVersion() {
        val store = seededStore(ArenaBotVersionStatus.Quarantined)
        val check = chainedCheck(store)

        val result = check.evaluate(accountRiskRequest(botVersion = ""))

        assertEquals(AccountRiskDecision.ALLOW, result.decision)
    }

    @Test
    fun refreshesOutsideTheIngressPathAndUsesTheCachedVersion() {
        val executor = QueuedExecutor()
        val store = seededStore(ArenaBotVersionStatus.Quarantined)
        val check = ArenaBotVersionRiskCheck(store, refreshExecutor = executor)

        assertEquals(null, check.evaluate(accountRiskRequest(botVersion = "v1")))
        assertEquals(1, executor.size)

        executor.runNext()

        assertEquals(AccountRiskDecision.DISABLED_BOT, check.evaluate(accountRiskRequest(botVersion = "v1"))?.decision)
    }

    @Test
    fun backsOffAfterArenaStoreFailureWithoutBlockingOrRetryingEveryRequest() {
        val executor = QueuedExecutor()
        var now = 0L
        val store = object : ArenaBotRegistryStore by seededStore(ArenaBotVersionStatus.Quarantined) {
            override fun version(botId: String, versionId: String): ArenaBotVersion? = error("arena database unavailable")
        }
        val check = ArenaBotVersionRiskCheck(
            store = store,
            refreshExecutor = executor,
            nanoTime = { now },
            failureBackoffNanos = 10
        )

        assertEquals(null, check.evaluate(accountRiskRequest(botVersion = "v1")))
        assertEquals(1, executor.size)
        executor.runNext()

        assertEquals(null, check.evaluate(accountRiskRequest(botVersion = "v1")))
        assertEquals(0, executor.size)

        now = 10
        assertEquals(null, check.evaluate(accountRiskRequest(botVersion = "v1")))
        assertEquals(1, executor.size)
    }

    private fun seededStore(status: ArenaBotVersionStatus): InMemoryArenaBotRegistryStore {
        val store = InMemoryArenaBotRegistryStore()
        val service = ArenaControlPlaneService(store) { Instant.parse("2026-07-05T12:00:00Z") }
        service.registerBot(
            RegisterArenaBotCommand(
                botId = "bot-1",
                fileName = "bot-1.ts",
                metadata = ArenaBotMetadata(
                    name = "Bot 1",
                    publisher = "Publisher",
                    email = "publisher@example.com"
                )
            )
        )
        service.registerVersion(
            RegisterArenaBotVersionCommand(
                botId = "bot-1",
                versionId = "v1",
                sourceHash = "sha256:source",
                artifactHash = "sha256:artifact",
                sdkVersion = "1.5.0",
                apiVersion = "v1",
                dependencyManifestHash = "sha256:deps"
            )
        )
        listOf(
            ArenaBotVersionStatus.Submitted,
            ArenaBotVersionStatus.ChecksPassed,
            ArenaBotVersionStatus.Approved
        ).forEach { next ->
            service.transitionVersion("bot-1", "v1", next, "operator-1", "seed", "corr-1")
        }
        if (status != ArenaBotVersionStatus.Approved) {
            service.transitionVersion("bot-1", "v1", status, "operator-1", "seed disabled", "corr-2")
        }
        return store
    }

    private fun accountRiskRequest(botVersion: String): AccountRiskCheckRequest {
        return AccountRiskCheckRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            commandType = "SubmitOrder",
            commandId = "cmd-1",
            idempotencyKey = "idem-1",
            correlationId = "corr-1",
            actorId = "actor-1",
            participantId = "participant-1",
            accountId = "account-1",
            botId = "bot-1",
            botVersion = botVersion,
            runId = "run-1",
            venueSessionId = "session-1",
            instrumentId = "AAPL",
            orderId = "ord-1",
            payloadHash = "hash-1"
        )
    }

    private fun chainedCheck(store: InMemoryArenaBotRegistryStore): ChainedAccountRiskCheck {
        return ChainedAccountRiskCheck(
            AllowAllAccountRiskCheck(),
            listOf(ArenaBotVersionRiskCheck(store, refreshExecutor = Executor { it.run() }))
        )
    }

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        val size: Int get() = commands.size

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runNext() {
            commands.removeFirst().run()
        }
    }
}
