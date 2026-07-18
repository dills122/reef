package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountRiskCheckExtensionTest {
    @Test
    fun extensionRejectsBeforePrimaryCheck() {
        val check = ChainedAccountRiskCheck(
            primary = AllowAllAccountRiskCheck(),
            extensions = listOf(AccountRiskCheckExtension {
                AccountRiskCheckResult(AccountRiskDecision.DISABLED_BOT, message = "optional policy disabled actor")
            })
        )

        val result = check.evaluate(request())

        assertEquals(AccountRiskDecision.DISABLED_BOT, result.decision)
        assertEquals("optional policy disabled actor", result.message)
    }

    @Test
    fun extensionCanDeferToPrimaryCheck() {
        val check = ChainedAccountRiskCheck(
            primary = StaticAccountRiskCheck(rejectedAccounts = setOf("account-1")),
            extensions = listOf(AccountRiskCheckExtension { null })
        )

        assertEquals(AccountRiskDecision.REJECT, check.evaluate(request()).decision)
    }

    private fun request(): AccountRiskCheckRequest = AccountRiskCheckRequest(
        clientId = "client-1",
        route = "/api/v1/orders/submit",
        commandType = "SubmitOrder",
        commandId = "command-1",
        idempotencyKey = "idem-1",
        correlationId = "corr-1",
        actorId = "actor-1",
        participantId = "participant-1",
        accountId = "account-1",
        botId = "bot-1",
        runId = "run-1",
        venueSessionId = "session-1",
        instrumentId = "AAPL",
        orderId = "order-1",
        payloadHash = "hash-1"
    )
}
