package com.reef.platform.api

import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.platform.application.settlement.InMemorySettlementFactStore
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PlatformHttpSettlementRoutesBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun settlementFactsEndpointAppendsAndReadsP2FactsByScenarioRunId() {
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-api")
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-api")

            assertEquals(200, posted.status)
            assertContains(posted.body, "\"status\":\"ok\"")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"scenarioRunId\":\"p2-run-api\"")
            assertContains(fetched.body, "\"settlementObligationId\":\"obl-1\"")
            assertContains(fetched.body, "\"settlementInstructionId\":\"instruction-1\"")
            assertContains(fetched.body, "\"settlementAttemptId\":\"attempt-1\"")
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":2")
            assertContains(fetched.body, "\"reason\":\"CASH_LEG_FAILED\"")
            assertContains(fetched.body, "\"repairAction\":\"POST_CASH_LEG_REPAIR\"")
            assertContains(fetched.body, "\"settlementState\":\"RESOLVED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun hotPathSettlementFactsEndpointReadsAppendedFactsByScenarioRunId() {
        val settlementStore = InMemorySettlementFactStore()
        val server = PlatformHttpServer(
            port = 0,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            settlementFactStore = settlementStore
        )
        val readHeaders = Headers().apply {
            add("X-Client-Id", "client-hot-path-settlement")
            add("X-Participant-Id", "participant-1")
        }

        val posted = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/internal/admin/settlement/facts",
                query = null,
                headers = Headers(),
                remoteAddress = "127.0.0.1",
                body = p2SettlementFactsBody("p2-run-hot-path")
            )
        )
        val fetched = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "GET",
                path = "/api/v1/settlement/facts/p2-run-hot-path",
                query = null,
                headers = readHeaders,
                remoteAddress = "203.0.113.10",
                body = ""
            )
        )

        assertEquals(200, posted?.status)
        assertContains(posted?.body.orEmpty(), "\"status\":\"ok\"")
        assertEquals(200, fetched?.status)
        assertContains(fetched?.body.orEmpty(), "\"scenarioRunId\":\"p2-run-hot-path\"")
        assertContains(fetched?.body.orEmpty(), "\"settlementObligationId\":\"obl-1\"")
        assertContains(fetched?.body.orEmpty(), "\"settlementInstructionId\":\"instruction-1\"")
        assertContains(fetched?.body.orEmpty(), "\"settlementAttemptId\":\"attempt-1\"")
    }

    @Test
    fun adminGatewaySettlementFactsEndpointAppendsAndReadsP2FactsByScenarioRunId() {
        val auth = testAdminAuth()
        val serviceToken = auth.authService.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Admin,
            subjectActorId = "settlement-seeder",
            ttl = null
        )
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            settlementFactStore = settlementStore
        )
        try {
            val posted = post(
                server.address.port,
                "/admin/v1/settlement/facts",
                mapOf("Authorization" to "Bearer ${serviceToken.token}"),
                p2SettlementFactsBody("p2-run-admin-gateway")
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-admin-gateway")

            assertEquals(200, posted.status)
            assertContains(posted.body, "\"status\":\"ok\"")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"scenarioRunId\":\"p2-run-admin-gateway\"")
            assertContains(fetched.body, "\"settlementObligationId\":\"obl-1\"")
            assertContains(fetched.body, "\"settlementState\":\"RESOLVED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesConfiguredPostTradeProfileDefault() {
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-default", includePostTradeProfile = false)
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-default")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":4")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesPersistedActivePostTradeProfile() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "instant-post-trade-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 5,
                active = true
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence)
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-persisted-default", includePostTradeProfile = false)
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-persisted-default")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":5")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesVenueSessionPostTradeProfileOverride() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "instant-post-trade-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 6
            )
        )
        persistence.saveVenueSessionPostTradeProfile(
            com.reef.platform.domain.VenueSessionPostTradeProfile(
                venueSessionId = "session-fast",
                postTradeProfileId = "instant-post-trade-v1"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence),
            venueSessionPostTradeProfileLookup = { persistence.venueSessionPostTradeProfileId(it) }
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody(
                    scenarioRunId = "p2-run-venue-override",
                    includePostTradeProfile = false,
                    venueSessionId = "session-fast"
                )
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-venue-override")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":6")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesScenarioRunPostTradeProfileOverride() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 8
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile(
                scenarioRunId = "p2-run-scenario-override",
                postTradeProfileId = "scenario-instant-v1"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence),
            scenarioRunPostTradeProfileLookup = { persistence.scenarioRunPostTradeProfileId(it) }
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody(
                    scenarioRunId = "p2-run-scenario-override",
                    includePostTradeProfile = false
                )
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-scenario-override")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"scenario-instant-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":8")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointExplicitProfileWinsOverScenarioRunOverride() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 8
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile(
                scenarioRunId = "p2-run-explicit-wins",
                postTradeProfileId = "scenario-instant-v1"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence),
            scenarioRunPostTradeProfileLookup = { persistence.scenarioRunPostTradeProfileId(it) }
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-explicit-wins", includePostTradeProfile = true)
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-explicit-wins")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":2")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementMaterializeEndpointCreatesObligationsFromTrades() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 9
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile("run-materialize", "scenario-instant-v1")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-materialize", "buyer-1", "BUY", "run-materialize", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-materialize", "seller-1", "SELL", "run-materialize", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-materialize",
                    tradeId = "trade-materialize",
                    executionId = "exec-materialize",
                    buyOrderId = "buy-order-materialize",
                    sellOrderId = "sell-order-materialize",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z",
                    runId = "run-materialize"
                )
            )
        )
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence)
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize"}"""
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize")
            val obligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize")
            val ledger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize")
            val exceptions = apiGet(server.address.port, "/api/v1/settlement/exceptions/run-materialize")
            val proof = apiGet(server.address.port, "/api/v1/settlement/proof/run-materialize")
            val score = apiGet(server.address.port, "/api/v1/settlement/score/run-materialize")

            assertEquals(200, posted.status)
            assertContains(posted.body, "\"materializedObligations\":1")
            assertContains(posted.body, "\"materializedInstructions\":1")
            assertContains(posted.body, "\"materializedAttempts\":1")
            assertContains(posted.body, "\"materializedLegOutcomes\":2")
            assertContains(posted.body, "\"materializedLedgerEntries\":4")
            assertContains(posted.body, "\"materializedSettlements\":1")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"settlementObligationId\":\"settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementClearingSubmissionId\":\"settlement-clearing-submission-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementClearingAcceptanceId\":\"settlement-clearing-acceptance-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementNovationId\":\"settlement-novation-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementInstructionId\":\"settlement-instruction-settlement-obligation-trade-materialize-1\"")
            assertContains(fetched.body, "\"settlementAttemptId\":\"settlement-attempt-settlement-obligation-trade-materialize-1\"")
            assertContains(fetched.body, "\"settlementId\":\"settlement-final-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"ledgerEntryId\":\"settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertContains(fetched.body, "\"postTradeProfileId\":\"scenario-instant-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":9")
            assertContains(fetched.body, "\"cashAmount\":\"15025000000000\"")
            assertEquals(200, obligations.status)
            assertContains(obligations.body, "\"obligationsCount\":1")
            assertContains(obligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(obligations.body, "\"exceptionState\":\"NONE\"")
            assertContains(obligations.body, "\"clearingState\":\"NOVATION_RECORDED\"")
            assertContains(obligations.body, "\"settlementNovationId\":\"settlement-novation-settlement-obligation-trade-materialize\"")
            assertContains(obligations.body, "\"settlementInstructionId\":\"settlement-instruction-settlement-obligation-trade-materialize-1\"")
            assertContains(obligations.body, "\"settlementAttemptNumber\":1")
            assertContains(obligations.body, "\"cashLegState\":\"LEG_SUCCEEDED\"")
            assertContains(obligations.body, "\"securityLegState\":\"LEG_SUCCEEDED\"")
            assertContains(obligations.body, "\"ledgerEntryCount\":4")
            assertEquals(200, ledger.status)
            assertContains(ledger.body, "\"balancesCount\":4")
            assertContains(ledger.body, "\"settlementProofsCount\":1")
            assertContains(ledger.body, "\"participantId\":\"buyer-1\"")
            assertContains(ledger.body, "\"assetType\":\"CASH\"")
            assertContains(ledger.body, "\"netQuantity\":\"-15025000000000\"")
            assertContains(ledger.body, "\"proofState\":\"PROVEN\"")
            assertContains(ledger.body, "\"cashBalanced\":true")
            assertContains(ledger.body, "\"securityBalanced\":true")
            assertEquals(200, exceptions.status)
            assertContains(exceptions.body, "\"exceptionsCount\":0")
            assertContains(exceptions.body, "\"openCount\":0")
            assertEquals(200, proof.status)
            assertContains(proof.body, "\"proofStatus\":\"CLEAN\"")
            assertContains(proof.body, "\"checksumAlgorithm\":\"SHA-256\"")
            assertContains(proof.body, "\"profilePolicies\"")
            assertContains(proof.body, "\"causationGapsCount\":0")
            assertContains(proof.body, "\"ledgerEntryIds\"")
            assertContains(proof.body, "\"settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertEquals(200, score.status)
            assertContains(score.body, "\"agedFailAfterSeconds\":86400")
            assertContains(score.body, "\"participantsCount\":2")
            assertContains(score.body, "\"participantId\":\"buyer-1\"")
            assertContains(score.body, "\"pendingValue\":\"0\"")
            assertContains(score.body, "\"scorePenaltyPoints\":0")
            assertContains(score.body, "\"agedFailCount\":0")

            val reversed = post(
                server.address.port,
                "/internal/admin/settlement/reverse-ledger-entry",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize",
                  "ledgerEntryId":"settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit",
                  "actorId":"ops-user-1",
                  "reasonNote":"reverse buyer cash debit for operator test",
                  "occurredAt":"2026-01-01T00:00:03Z"
                }
                """.trimIndent()
            )
            val reversedFacts = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize")
            val reversedLedger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize")

            assertEquals(200, reversed.status)
            assertContains(reversed.body, "\"action\":\"REVERSE_LEDGER_ENTRY\"")
            assertContains(reversed.body, "\"reasonNote\":\"reverse buyer cash debit for operator test\"")
            assertContains(reversed.body, "\"ledgerEntryId\":\"settlement-ledger-reversal-settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertEquals(200, reversedFacts.status)
            assertContains(reversedFacts.body, "\"operatorActions\"")
            assertContains(reversedFacts.body, "\"settlementOperatorActionId\":\"operator-reverse-ledger-settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertEquals(200, reversedLedger.status)
            assertContains(reversedLedger.body, "\"settlementProofsCount\":1")
            assertContains(reversedLedger.body, "\"cashBalanced\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementMaterializeEndpointCreatesBreakWhenSeededResourcesAreInsufficient() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-materialize-fail", "buyer-1", "BUY", "run-materialize-fail", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-materialize-fail", "seller-1", "SELL", "run-materialize-fail", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-materialize-fail",
                    tradeId = "trade-materialize-fail",
                    executionId = "exec-materialize-fail",
                    buyOrderId = "buy-order-materialize-fail",
                    sellOrderId = "sell-order-materialize-fail",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z",
                    runId = "run-materialize-fail"
                )
            )
        )
        val resolver = PostTradeProfileResolver.envOnly(
            profileId = "instant-post-trade-v1",
            policyVersion = 4
        )
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = resolver
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4,
            postTradeProfileResolver = resolver
        )
        try {
            val seeded = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "resourcePositions":[
                    {
                      "resourcePositionId":"resource-run-materialize-fail-seller-security",
                      "correlationId":"corr-resource-fail",
                      "causationId":"seed-resource-fail",
                      "participantId":"seller-1",
                      "accountId":"account-seller-1",
                      "assetType":"SECURITY",
                      "assetId":"AAPL",
                      "quantity":"100",
                      "occurredAt":"2025-12-31T23:59:59Z"
                    }
                  ]
                }
                """.trimIndent()
            )
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-fail"}"""
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize-fail")
            val obligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-fail")
            val exceptions = apiGet(server.address.port, "/api/v1/settlement/exceptions/run-materialize-fail")

            assertEquals(200, seeded.status)
            assertEquals(200, posted.status)
            assertContains(posted.body, "\"materializedLedgerEntries\":0")
            assertContains(posted.body, "\"materializedSettlements\":0")
            assertContains(posted.body, "\"materializedBreaks\":1")
            assertContains(posted.body, "\"materializedResolutions\":0")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"resourcePositionId\":\"resource-run-materialize-fail-seller-security\"")
            assertContains(fetched.body, "\"legType\":\"CASH\"")
            assertContains(fetched.body, "\"state\":\"LEG_FAILED\"")
            assertContains(fetched.body, "\"reason\":\"CASH_LEG_FAILED\"")
            assertEquals(200, obligations.status)
            assertContains(obligations.body, "\"settlementState\":\"BROKEN\"")
            assertContains(obligations.body, "\"cashLegState\":\"LEG_FAILED\"")
            assertContains(obligations.body, "\"securityLegState\":\"LEG_SUCCEEDED\"")
            assertContains(obligations.body, "\"ledgerEntryCount\":0")
            assertEquals(200, exceptions.status)
            assertContains(exceptions.body, "\"exceptionsCount\":1")
            assertContains(exceptions.body, "\"openCount\":1")
            assertContains(exceptions.body, "\"exceptionType\":\"SETTLEMENT_BREAK\"")
            assertContains(exceptions.body, "\"severity\":\"MEDIUM\"")
            assertContains(exceptions.body, "\"ownerRole\":\"SETTLEMENT_OPS\"")
            assertContains(exceptions.body, "\"actionRequired\":\"POST_CASH_LEG_REPAIR\"")
            assertContains(exceptions.body, "\"repairAction\":\"POST_CASH_LEG_REPAIR\"")

            val missingOccurredAtRepair = post(
                server.address.port,
                "/internal/admin/settlement/repairs/cash",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-fail-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1"
                }
                """.trimIndent()
            )
            val wrongRepairRoute = post(
                server.address.port,
                "/internal/admin/settlement/repairs/security",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-fail-1",
                  "accountId":"account-seller-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:01Z"
                }
                """.trimIndent()
            )
            val repairPosted = post(
                server.address.port,
                "/internal/admin/settlement/repairs/cash",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "settlementRepairId":"repair-run-materialize-fail-1",
                  "resourcePositionId":"resource-run-materialize-fail-buyer-cash-repair",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-fail-1",
                  "accountId":"account-buyer-1",
                  "quantity":"15025000000000",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val repaired = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-fail"}"""
            )
            val repairedFetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize-fail")
            val repairedObligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-fail")
            val repairedLedger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize-fail")

            assertEquals(400, missingOccurredAtRepair.status)
            assertContains(missingOccurredAtRepair.body, "\"error\":\"occurredAt is required\"")
            assertEquals(400, wrongRepairRoute.status)
            assertContains(wrongRepairRoute.body, "\"error\":\"settlementBreakId is not a security break\"")
            assertEquals(200, repairPosted.status)
            assertContains(repairPosted.body, "\"resourcePositionId\":\"resource-run-materialize-fail-buyer-cash-repair\"")
            assertContains(repairPosted.body, "\"settlementRepairId\":\"repair-run-materialize-fail-1\"")
            assertContains(repairPosted.body, "\"repairAction\":\"POST_CASH_LEG_REPAIR\"")
            assertEquals(200, repaired.status)
            assertContains(repaired.body, "\"materializedAttempts\":1")
            assertContains(repaired.body, "\"materializedLedgerEntries\":4")
            assertContains(repaired.body, "\"materializedSettlements\":1")
            assertContains(repaired.body, "\"materializedResolutions\":1")
            assertEquals(200, repairedFetched.status)
            assertContains(repairedFetched.body, "\"settlementAttemptId\":\"settlement-attempt-settlement-obligation-trade-materialize-fail-2\"")
            assertContains(repairedFetched.body, "\"settlementResolutionId\":\"settlement-resolution-settlement-break-settlement-obligation-trade-materialize-fail-1-repair-run-materialize-fail-1\"")
            assertEquals(200, repairedObligations.status)
            assertContains(repairedObligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(repairedObligations.body, "\"exceptionState\":\"RESOLVED\"")
            assertContains(repairedObligations.body, "\"settlementAttemptNumber\":2")
            assertContains(repairedObligations.body, "\"ledgerEntryCount\":4")
            assertEquals(200, repairedLedger.status)
            assertContains(repairedLedger.body, "\"settlementProofsCount\":1")
            assertContains(repairedLedger.body, "\"proofState\":\"PROVEN\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementSecurityRepairCommandReattemptsBrokenSettlement() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-materialize-security-fail", "buyer-1", "BUY", "run-materialize-security-fail", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-materialize-security-fail", "seller-1", "SELL", "run-materialize-security-fail", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-materialize-security-fail",
                    tradeId = "trade-materialize-security-fail",
                    executionId = "exec-materialize-security-fail",
                    buyOrderId = "buy-order-materialize-security-fail",
                    sellOrderId = "sell-order-materialize-security-fail",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z",
                    runId = "run-materialize-security-fail"
                )
            )
        )
        val resolver = PostTradeProfileResolver.envOnly(
            profileId = "instant-post-trade-v1",
            policyVersion = 4
        )
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = resolver
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4,
            postTradeProfileResolver = resolver
        )
        try {
            val seeded = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-security-fail",
                  "resourcePositions":[
                    {
                      "resourcePositionId":"resource-run-materialize-security-fail-buyer-cash",
                      "correlationId":"corr-resource-security-fail",
                      "causationId":"seed-resource-security-fail",
                      "participantId":"buyer-1",
                      "accountId":"account-buyer-1",
                      "assetType":"CASH",
                      "assetId":"USD",
                      "quantity":"15025000000000",
                      "occurredAt":"2025-12-31T23:59:59Z"
                    }
                  ]
                }
                """.trimIndent()
            )
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-security-fail"}"""
            )
            val brokenObligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-security-fail")

            assertEquals(200, seeded.status)
            assertEquals(200, posted.status)
            assertContains(posted.body, "\"materializedLedgerEntries\":0")
            assertContains(posted.body, "\"materializedBreaks\":1")
            assertEquals(200, brokenObligations.status)
            assertContains(brokenObligations.body, "\"settlementState\":\"BROKEN\"")
            assertContains(brokenObligations.body, "\"cashLegState\":\"LEG_SUCCEEDED\"")
            assertContains(brokenObligations.body, "\"securityLegState\":\"LEG_FAILED\"")

            val wrongRepairRoute = post(
                server.address.port,
                "/internal/admin/settlement/repairs/cash",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-security-fail",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-security-fail-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:01Z"
                }
                """.trimIndent()
            )
            val repairPosted = post(
                server.address.port,
                "/internal/admin/settlement/repairs/security",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-security-fail",
                  "settlementRepairId":"repair-run-materialize-security-fail-1",
                  "resourcePositionId":"resource-run-materialize-security-fail-seller-security-repair",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-security-fail-1",
                  "accountId":"account-seller-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val repaired = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-security-fail"}"""
            )
            val repairedFetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize-security-fail")
            val repairedObligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-security-fail")
            val repairedLedger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize-security-fail")

            assertEquals(400, wrongRepairRoute.status)
            assertContains(wrongRepairRoute.body, "\"error\":\"settlementBreakId is not a cash break\"")
            assertEquals(200, repairPosted.status)
            assertContains(repairPosted.body, "\"resourcePositionId\":\"resource-run-materialize-security-fail-seller-security-repair\"")
            assertContains(repairPosted.body, "\"repairAction\":\"POST_SECURITY_LEG_REPAIR\"")
            assertContains(repairPosted.body, "\"assetType\":\"SECURITY\"")
            assertContains(repairPosted.body, "\"assetId\":\"AAPL\"")
            assertContains(repairPosted.body, "\"quantity\":\"100\"")
            assertEquals(200, repaired.status)
            assertContains(repaired.body, "\"materializedAttempts\":1")
            assertContains(repaired.body, "\"materializedLedgerEntries\":4")
            assertContains(repaired.body, "\"materializedSettlements\":1")
            assertContains(repaired.body, "\"materializedResolutions\":1")
            assertEquals(200, repairedFetched.status)
            assertContains(repairedFetched.body, "\"settlementAttemptId\":\"settlement-attempt-settlement-obligation-trade-materialize-security-fail-2\"")
            assertContains(repairedFetched.body, "\"settlementResolutionId\":\"settlement-resolution-settlement-break-settlement-obligation-trade-materialize-security-fail-1-repair-run-materialize-security-fail-1\"")
            assertEquals(200, repairedObligations.status)
            assertContains(repairedObligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(repairedObligations.body, "\"exceptionState\":\"RESOLVED\"")
            assertContains(repairedObligations.body, "\"settlementAttemptNumber\":2")
            assertContains(repairedObligations.body, "\"ledgerEntryCount\":4")
            assertEquals(200, repairedLedger.status)
            assertContains(repairedLedger.body, "\"settlementProofsCount\":1")
            assertContains(repairedLedger.body, "\"proofState\":\"PROVEN\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementForceSettleCommandRepairsAndMaterializesBreak() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-force-settle", "buyer-1", "BUY", "run-force-settle", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-force-settle", "seller-1", "SELL", "run-force-settle", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-force-settle",
                    tradeId = "trade-force-settle",
                    executionId = "exec-force-settle",
                    buyOrderId = "buy-order-force-settle",
                    sellOrderId = "sell-order-force-settle",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z",
                    runId = "run-force-settle"
                )
            )
        )
        val resolver = PostTradeProfileResolver.envOnly("instant-post-trade-v1", 4)
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = resolver
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4,
            postTradeProfileResolver = resolver
        )
        try {
            val seeded = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-force-settle",
                  "resourcePositions":[
                    {
                      "resourcePositionId":"resource-run-force-settle-seller-security",
                      "correlationId":"corr-force-settle",
                      "causationId":"seed-force-settle",
                      "participantId":"seller-1",
                      "accountId":"account-seller-1",
                      "assetType":"SECURITY",
                      "assetId":"AAPL",
                      "quantity":"100",
                      "occurredAt":"2025-12-31T23:59:59Z"
                    }
                  ]
                }
                """.trimIndent()
            )
            val broken = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-force-settle"}"""
            )
            val missingReason = post(
                server.address.port,
                "/internal/admin/settlement/force-settle",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-force-settle",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-force-settle-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val forced = post(
                server.address.port,
                "/internal/admin/settlement/force-settle",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-force-settle",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-force-settle-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1",
                  "reasonNote":"force settle cash fail in scenario",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val facts = apiGet(server.address.port, "/api/v1/settlement/facts/run-force-settle")
            val obligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-force-settle")
            val proof = apiGet(server.address.port, "/api/v1/settlement/proof/run-force-settle")

            assertEquals(200, seeded.status)
            assertEquals(200, broken.status)
            assertContains(broken.body, "\"materializedBreaks\":1")
            assertEquals(400, missingReason.status)
            assertContains(missingReason.body, "\"error\":\"reasonNote is required\"")
            assertEquals(200, forced.status)
            assertContains(forced.body, "\"action\":\"FORCE_SETTLE\"")
            assertContains(forced.body, "\"reasonNote\":\"force settle cash fail in scenario\"")
            assertContains(forced.body, "\"materializedSettlements\":1")
            assertContains(forced.body, "\"materializedResolutions\":1")
            assertEquals(200, facts.status)
            assertContains(facts.body, "\"settlementOperatorActionId\":\"operator-force-settle-settlement-break-settlement-obligation-trade-force-settle-1\"")
            assertEquals(200, obligations.status)
            assertContains(obligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(obligations.body, "\"exceptionState\":\"RESOLVED\"")
            assertEquals(200, proof.status)
            assertContains(proof.body, "\"operatorActionsCount\":1")
            assertContains(proof.body, "\"proofStatus\":\"CLEAN\"")
        } finally {
            server.stop(0)
        }
    }
}
