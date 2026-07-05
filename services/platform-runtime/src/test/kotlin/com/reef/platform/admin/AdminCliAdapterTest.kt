package com.reef.platform.admin

import com.reef.platform.api.AccountRiskControl
import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.CommandCircuitBreakerState
import com.reef.platform.api.CommandCircuitBreakerStore
import com.reef.platform.api.CommandCircuitBreakerRequest
import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains

class AdminCliAdapterTest {
    @Test
    fun executesReferenceDataUpsertCommands() {
        val service = AdminApplicationService(InMemoryRuntimePersistence())
        val accountRiskControls = RecordingAccountRiskControlStore()
        val commandCircuitBreakers = RecordingCommandCircuitBreakerStore()
        val cli = AdminCliAdapter(
            service,
            accountRiskControls = { accountRiskControls },
            commandCircuitBreakers = { commandCircuitBreakers }
        )

        assertContains(cli.execute(listOf("instrument-upsert", "AAPL", "AAPL")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("participant-upsert", "participant-1", "Participant 1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("account-upsert", "account-1", "participant-1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("role-upsert", "ops_role", "reference.write,auth.admin")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("role-assign", "ops-2", "ops_role")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("roles-list")), "\"rolesCount\":")
        assertContains(cli.execute(listOf("actor-roles", "ops-2")), "\"rolesCount\":")
        assertContains(
            cli.execute(listOf("account-risk-set", "bot", "bot-1", "disabled-bot", "operator", "disabled")),
            "\"decision\":\"DISABLED_BOT\""
        )
        assertContains(cli.execute(listOf("account-risk-list")), "\"controlsCount\":1")
        assertContains(
            cli.execute(listOf("breaker-set", "instrument", "AAPL", "trip", "halted")),
            "\"tripped\":true"
        )
        assertContains(cli.execute(listOf("breaker-list")), "\"breakersCount\":1")
        assertContains(cli.execute(listOf("calendar-upsert", "us-default", "America/New_York", "T+1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("calendar-list")), "\"profilesCount\":")
        assertContains(cli.execute(listOf("override-upsert", "MANUAL_REPAIR", "manual operational repair")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("override-list")), "\"reasonsCount\":")
        assertContains(cli.execute(listOf("sim-start", "scenario-1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("sim-pause")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("sim-stop")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("sim-state")), "\"status\":\"")
        assertContains(cli.execute(listOf("events-recent", "10")), "\"eventsCount\":")
    }

    @Test
    fun returnsUsageForUnknownCommand() {
        val cli = AdminCliAdapter(AdminApplicationService(InMemoryRuntimePersistence()))
        val output = cli.execute(listOf("unknown"))
        assertContains(output, "admin commands:")
    }
}

private class RecordingAccountRiskControlStore : AccountRiskControlStore {
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
        controls["$scopeType|$scopeId"] = AccountRiskControl(scopeType, scopeId, decision, reason, maxQuantityUnits, maxNotional, currency)
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()
}

private class RecordingCommandCircuitBreakerStore : CommandCircuitBreakerStore {
    private val breakers = linkedMapOf<String, CommandCircuitBreakerState>()

    override fun evaluate(request: CommandCircuitBreakerRequest) = null

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        breakers["$scopeType|$scopeId"] = CommandCircuitBreakerState(scopeType, scopeId, tripped, reason)
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> = breakers.values.toList()
}
