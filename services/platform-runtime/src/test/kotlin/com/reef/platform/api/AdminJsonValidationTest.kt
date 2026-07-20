package com.reef.platform.api

import com.reef.platform.application.settlement.InMemorySettlementFactStore
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AdminJsonValidationTest {
    @Test
    fun riskGuardrailWritesRejectMalformedJsonBeforeSemanticValidation() {
        val gateway = RiskGuardrailGateway(
            api = testPlatformApi(),
            accountRiskControlStore = JsonValidationAccountRiskControlStore(),
            accountRiskDecisionLog = null,
            commandCircuitBreakerStore = JsonValidationCommandCircuitBreakerStore(),
            instrumentPriceCollarStore = JsonValidationInstrumentPriceCollarStore(),
            adminSessionAuth = testAdminSessionAuth()
        )

        val accountRisk = gateway.setAccountRiskControlResponse("""{"scopeType":""")
        val breaker = gateway.setCommandCircuitBreakerResponse("""{"scopeType":""")
        val collar = gateway.setInstrumentPriceCollarResponse("""{"instrumentId":""")

        listOf(accountRisk, breaker, collar).forEach { response ->
            assertEquals(400, response.status)
            assertContains(response.body, """"error":"invalid json payload"""")
        }
    }

    @Test
    fun settlementAdminWritesRejectMalformedJsonBeforeSemanticValidation() {
        val gateway = SettlementAdminGateway(
            settlementFactStore = InMemorySettlementFactStore(),
            settlementObligationMaterializer = null,
            postTradeProfileResolver = PostTradeProfileResolver(),
            scenarioRunPostTradeProfileLookup = { null },
            venueSessionPostTradeProfileLookup = { null },
            adminSessionAuth = testAdminSessionAuth()
        )

        val response = gateway.appendSettlementFactsResponse("""{"scenarioRunId":""")

        assertEquals(400, response.status)
        assertContains(response.body, """"error":"invalid json payload"""")
    }
}

private fun testPlatformApi(): PlatformApi {
    return PlatformApi(
        com.reef.platform.application.OrderApplicationService(
            runtimePersistence = InMemoryRuntimePersistence()
        )
    )
}

private fun testAdminSessionAuth(): AdminSessionAuth {
    return AdminSessionAuth(
        adminAuthService = null,
        adminIdentityService = null,
        adminGitHubOAuthClient = null,
        adminSessionCookieName = "reef_admin_session",
        adminSessionCookieSecure = true,
        localDevAdminAuthBypass = false,
        internalHttpExposureMode = InternalHttpExposureMode.Disabled
    )
}

private class JsonValidationAccountRiskControlStore : AccountRiskControlStore {
    private val controls = linkedMapOf<String, AccountRiskControl>()

    override fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String,
        maxQuantityUnits: String,
        maxNotional: String,
        currency: String
    ) {
        controls["$scopeType:$scopeId"] = AccountRiskControl(
            scopeType = scopeType,
            scopeId = scopeId,
            decision = decision,
            reason = reason,
            maxQuantityUnits = maxQuantityUnits,
            maxNotional = maxNotional,
            currency = currency
        )
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()
}

private class JsonValidationCommandCircuitBreakerStore : CommandCircuitBreakerStore {
    private val breakers = linkedMapOf<String, CommandCircuitBreakerState>()

    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? = null

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        breakers["$scopeType:$scopeId"] = CommandCircuitBreakerState(scopeType, scopeId, tripped, reason)
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> = breakers.values.toList()
}

private class JsonValidationInstrumentPriceCollarStore : InstrumentPriceCollarStore {
    private val collars = linkedMapOf<String, InstrumentPriceCollarState>()

    override fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError? = null

    override fun setCollar(
        instrumentId: String,
        minPrice: String,
        maxPrice: String,
        currency: String,
        reason: String
    ) {
        collars[instrumentId] = InstrumentPriceCollarState(instrumentId, minPrice, maxPrice, currency, reason)
    }

    override fun listCollars(): List<InstrumentPriceCollarState> = collars.values.toList()
}
