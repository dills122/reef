package com.reef.platform.admin

import com.reef.platform.api.AccountRiskControl
import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.CommandCircuitBreakerState
import com.reef.platform.api.CommandCircuitBreakerStore
import com.reef.platform.api.CommandCircuitBreakerRequest
import com.reef.platform.api.InstrumentPriceCollarRequest
import com.reef.platform.api.InstrumentPriceCollarState
import com.reef.platform.api.InstrumentPriceCollarStore
import com.reef.platform.application.admin.ArenaBotVersionDecisionCommand
import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.application.arena.ArenaBotMetadata
import com.reef.platform.application.arena.ArenaBotVersionStatus
import com.reef.platform.application.arena.ArenaControlPlaneService
import com.reef.platform.application.arena.InMemoryArenaBotRegistryStore
import com.reef.platform.application.arena.RegisterArenaBotCommand
import com.reef.platform.application.arena.RegisterArenaBotVersionCommand
import com.reef.platform.api.JsonCodec
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains

class AdminCliAdapterTest {
    @Test
    fun executesReferenceDataUpsertCommands() {
        val arenaStore = seededArenaStore()
        val accountRiskControls = RecordingAccountRiskControlStore()
        val service = AdminApplicationService(
            InMemoryRuntimePersistence(),
            arenaRegistryStore = arenaStore,
            accountRiskControlStore = accountRiskControls
        )
        val commandCircuitBreakers = RecordingCommandCircuitBreakerStore()
        val instrumentPriceCollars = RecordingInstrumentPriceCollarStore()
        val cli = AdminCliAdapter(
            service,
            accountRiskControls = { accountRiskControls },
            commandCircuitBreakers = { commandCircuitBreakers },
            instrumentPriceCollars = { instrumentPriceCollars }
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
        assertContains(
            cli.execute(listOf("price-collar-set", "AAPL", "150000000000", "151000000000", "USD", "regular", "band")),
            "\"instrumentId\":\"AAPL\""
        )
        assertContains(cli.execute(listOf("price-collar-list")), "\"collarsCount\":1")
        assertContains(
            cli.execute(listOf("arena-bot-version-transition", "bot-1", "v1", "quarantine", "scanner", "regression")),
            "\"botVersionStatus\":\"Quarantined\""
        )
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

    @Test
    fun rejectsInvalidPriceCollarCliBands() {
        val cli = AdminCliAdapter(
            AdminApplicationService(InMemoryRuntimePersistence()),
            instrumentPriceCollars = { RecordingInstrumentPriceCollarStore() }
        )

        assertContains(cli.execute(listOf("price-collar-set", "AAPL", "bad", "151000000000")), "usage: price-collar-set")
        assertContains(cli.execute(listOf("price-collar-set", "AAPL", "151000000000", "150000000000")), "usage: price-collar-set")
    }

    @Test
    fun cliJsonResponsesEscapeStringFieldsWithJsonCodec() {
        val cli = AdminCliAdapter(AdminApplicationService(InMemoryRuntimePersistence()))
        val response = cli.execute(listOf("actor-roles", "ops-\"quoted\"\nactor"))

        val json = JsonCodec.parseObject(response)
        assertContains(json.string("actorId"), "quoted")
        assertContains(response, "\\n")
    }
}

private fun seededArenaStore(): InMemoryArenaBotRegistryStore {
    val store = InMemoryArenaBotRegistryStore()
    val service = ArenaControlPlaneService(store) { java.time.Instant.parse("2026-07-05T12:00:00Z") }
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
    return store
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

private class RecordingInstrumentPriceCollarStore : InstrumentPriceCollarStore {
    private val collars = linkedMapOf<String, InstrumentPriceCollarState>()

    override fun evaluate(request: InstrumentPriceCollarRequest) = null

    override fun setCollar(instrumentId: String, minPrice: String, maxPrice: String, currency: String, reason: String) {
        collars[instrumentId] = InstrumentPriceCollarState(instrumentId, minPrice, maxPrice, currency, reason)
    }

    override fun listCollars(): List<InstrumentPriceCollarState> = collars.values.toList()
}
