package com.reef.platform.api

import com.reef.platform.domain.PersistedOrder
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlatformHttpCommandIntakeBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun apiV1SubmitReturnsRateLimitedFromHook() {
        val server = testServer(
            boundary = ExternalApiBoundary(
                authHook = AllowAllAuthHook(),
                rateLimitHook = object : RateLimitHook {
                    override fun allow(clientId: String, route: String): BoundaryError? {
                        return BoundaryError(429, "RATE_LIMITED", "rate limit exceeded")
                    }
                }
            )
        )
        try {
            val reset = post(server.address.port, "/internal/perf/hot-path", emptyMap(), "")
            assertEquals(200, reset.status)

            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-1"
                ),
                body = "{}"
            )
            assertEquals(429, response.status)
            assertContains(response.body, "\"code\":\"RATE_LIMITED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReplaysFirstResponseForSameIdempotencyKey() {
        val server = testServerWithGateway(EchoOrderEngineGateway())
        try {
            val reset = post(server.address.port, "/internal/perf/hot-path", emptyMap(), "")
            assertEquals(200, reset.status)

            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-1"
            )
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = """
                    {
                      "commandId":"cmd-1",
                      "traceId":"trace-1",
                      "causationId":"",
                      "correlationId":"corr-1",
                      "actorId":"bot-1",
                      "occurredAt":"2026-05-22T00:00:00Z",
                      "orderId":"ord-first",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"100",
                      "limitPrice":"150250000000",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = """
                    {
                      "commandId":"cmd-2",
                      "traceId":"trace-2",
                      "causationId":"",
                      "correlationId":"corr-2",
                      "actorId":"bot-2",
                      "occurredAt":"2026-05-22T00:00:01Z",
                      "orderId":"ord-second",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"200",
                      "limitPrice":"150250000001",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertEquals(first.body, second.body)
            assertContains(second.body, "\"orderId\":\"ord-first\"")

            val hotPath = get(server.address.port, "/internal/perf/hot-path")
            assertEquals(200, hotPath.status)
            assertContains(hotPath.body, "\"api.mutation.total\"")
            assertContains(hotPath.body, "\"api.parse.submitOrder\"")
            assertContains(hotPath.body, "\"runtime.submitOrder.total\"")
            assertContains(hotPath.body, "\"api.writeResponse\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitCapturesReceivedAndCompletedLifecycle() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-capture-1"
                ),
                body = """
                    {
                      "commandId":"cmd-capture-1",
                      "traceId":"trace-capture-1",
                      "causationId":"",
                      "correlationId":"corr-capture-1",
                      "actorId":"bot-capture-1",
                      "occurredAt":"2026-05-22T00:00:00Z",
                      "orderId":"ord-capture-1",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"100",
                      "limitPrice":"150250000000",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            assertEquals(200, response.status)
            assertEquals(1, captureStore.receivedCalls)
            assertEquals(1, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
            assertEquals("cmd-capture-1", JsonCodec.parseObject(captureStore.lastReceivedPayload).string("commandId"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsParticipantAndAccountOutsideAuthenticatedScopeBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            participantIds = setOf("participant-1"),
                            accountIds = setOf("account-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Authorization" to "Bearer token-1",
                "Idempotency-Key" to "idem-scope-submit"
            )
            val participantSpoof = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-scope-participant", "trace-scope-participant", "ord-scope-participant")
                    .replace("\"participantId\":\"participant-1\"", "\"participantId\":\"participant-2\"")
            )
            val accountSpoof = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-scope-account"),
                body = validSubmitBody("cmd-scope-account", "trace-scope-account", "ord-scope-account")
                    .replace("\"accountId\":\"account-1\"", "\"accountId\":\"account-2\"")
            )

            assertEquals(403, participantSpoof.status)
            assertContains(participantSpoof.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertContains(participantSpoof.body, "participant scope")
            assertEquals(403, accountSpoof.status)
            assertContains(accountSpoof.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertContains(accountSpoof.body, "account scope")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1LifecycleMutationsRejectOwnershipClaimsOutsideAuthenticatedScopeBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            participantIds = setOf("participant-1"),
                            accountIds = setOf("account-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Authorization" to "Bearer token-1",
                "Idempotency-Key" to "idem-lifecycle-owner"
            )
            val lifecycleContext =
                ""","instrumentId":"AAPL","participantId":"participant-2","accountId":"account-1"${streamRoutingExtra()}"""
            val cases = listOf(
                "/api/v1/orders/cancel" to validCancelBody(
                    "cmd-lifecycle-owner-cancel",
                    "trace-lifecycle-owner-cancel",
                    "ord-lifecycle-owner",
                    extra = lifecycleContext
                ),
                "/api/v1/orders/modify" to validModifyBody(
                    "cmd-lifecycle-owner-modify",
                    "trace-lifecycle-owner-modify",
                    "ord-lifecycle-owner",
                    extra = lifecycleContext
                )
            )
            cases.forEachIndexed { index, (route, body) ->
                val denied = post(
                    port = server.address.port,
                    path = route,
                    headers = headers + ("Idempotency-Key" to "idem-lifecycle-owner-$index"),
                    body = body
                )
                assertEquals(403, denied.status, route)
                assertContains(denied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
                assertContains(denied.body, "participant scope")
            }
            assertEquals(0, captureStore.receivedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectActorOutsideAuthenticatedScopeBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            actorIds = setOf("bot-capture-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to validSubmitBody("cmd-scope-submit", "trace-scope-submit", "ord-scope-submit"),
                "/api/v1/orders/cancel" to validCancelBody("cmd-scope-cancel", "trace-scope-cancel", "ord-scope-cancel"),
                "/api/v1/orders/modify" to validModifyBody("cmd-scope-modify", "trace-scope-modify", "ord-scope-modify")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Authorization" to "Bearer token-1",
                        "Idempotency-Key" to "idem-scope-actor-$index"
                    ),
                    body = body.replace("\"actorId\":\"bot-capture-1\"", "\"actorId\":\"bot-capture-2\"")
                )

                assertEquals(403, response.status, route)
                assertContains(response.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
                assertContains(response.body, "actor scope")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMalformedJsonBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            listOf(
                "/api/v1/orders/submit",
                "/api/v1/orders/cancel",
                "/api/v1/orders/modify"
            ).forEachIndexed { index, route ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-malformed-$index",
                        "X-Correlation-Id" to "corr-malformed-$index"
                    ),
                    body = """{"commandId":"""
                )

                assertEquals(400, response.status)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"invalid json payload\"")
                assertContains(response.body, "\"correlationId\":\"corr-malformed-$index\"")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMalformedOccurredAtBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to validSubmitBody("cmd-time-submit", "trace-time-submit", "ord-time-submit"),
                "/api/v1/orders/cancel" to validCancelBody("cmd-time-cancel", "trace-time-cancel", "ord-time-cancel"),
                "/api/v1/orders/modify" to validModifyBody("cmd-time-modify", "trace-time-modify", "ord-time-modify")
            )
            cases.forEachIndexed { index, (route, validBody) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-invalid-time-$index"
                    ),
                    body = validBody.replace("2026-05-22T00:00:00Z", "not-a-timestamp")
                )

                assertEquals(400, response.status, route)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "invalid occurredAt: not-a-timestamp")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectUnknownFieldsBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to validSubmitBody("cmd-unknown-submit", "trace-unknown-submit", "ord-unknown-submit", extra = ""","unexpected":"value""""),
                "/api/v1/orders/cancel" to validCancelBody("cmd-unknown-cancel", "trace-unknown-cancel", "ord-unknown-cancel", extra = ""","unexpected":"value""""),
                "/api/v1/orders/modify" to validModifyBody("cmd-unknown-modify", "trace-unknown-modify", "ord-unknown-modify", extra = ""","unexpected":"value"""")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-unknown-$index"
                    ),
                    body = body
                )

                assertEquals(400, response.status)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"unknown field: unexpected\"")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMissingRequiredFieldsBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to bodyWithoutField(validSubmitBody("cmd-missing-submit", "trace-missing-submit", "ord-missing-submit"), "orderId"),
                "/api/v1/orders/cancel" to bodyWithoutField(validCancelBody("cmd-missing-cancel", "trace-missing-cancel", "ord-missing-cancel"), "reason"),
                "/api/v1/orders/modify" to bodyWithoutField(validModifyBody("cmd-missing-modify", "trace-missing-modify", "ord-missing-modify"), "limitPrice")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-missing-$index"
                    ),
                    body = body
                )

                assertEquals(400, response.status, route)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"missing required field:")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsInvalidEnumValuesBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-invalid-enum"
                ),
                body = validSubmitBody("cmd-invalid-enum", "trace-invalid-enum", "ord-invalid-enum")
                    .replace("\"side\":\"BUY\"", "\"side\":\"BID\"")
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "\"message\":\"invalid side: BID\"")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsUnauthorizedActorBeforeEngineCall() {
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            seedOrderAuthorization = false
        )
        try {
            seedReferenceData(server.address.port)
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-unauthorized"
                ),
                body = validSubmitBody("cmd-unauthorized", "trace-unauthorized", "ord-unauthorized")
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"rejected\"")
            assertContains(response.body, "\"code\":\"AUTHORIZATION_ERROR\"")
            assertContains(response.body, "\"reason\":\"actorId missing permission order.submit\"")
            assertEquals(0, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReturnsRetryableErrorWhenEngineTransportFails() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = ThrowingEngineGateway(),
            captureStore = captureStore
        )
        try {
            seedReferenceData(server.address.port)
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-engine-down"
                ),
                body = validSubmitBody("cmd-engine-down", "trace-engine-down", "ord-engine-down")
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"runtime unavailable\"")
            assertFalse(response.body.contains("\"rejected\""))
            assertEquals(1, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(1, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderResolvesAndCancelsUnderlyingOrder() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ord-resolved-1",
                engineOrderId = "eng-ord-resolved-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "100000000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-06T00:00:00Z",
                clientOrderId = "co-1",
                runId = "run-1",
                venueSessionId = "session-1"
            )
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-1"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-1",
                    "traceId" to "trace-cancel-by-client-order-1",
                    "correlationId" to "corr-cancel-by-client-order-1",
                    "causationId" to "cause-cancel-by-client-order-1",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1",
                    "clientOrderId" to "co-1",
                    "reason" to "test cancel"
                )
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"orderId\":\"ord-resolved-1\"")
            assertEquals(1, gateway.cancelCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderAuthenticatesBeforeClientOrderLookup() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ord-auth-before-lookup",
                engineOrderId = "eng-ord-auth-before-lookup",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "100000000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-06T00:00:00Z",
                clientOrderId = "co-auth-before-lookup",
                runId = "run-1",
                venueSessionId = "session-1"
            )
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            boundary = ExternalApiBoundary(
                authHook = StaticTokenAuthHook(
                    mapOf(
                        "client-1" to StaticTokenClientConfig(
                            token = "token-1",
                            principal = ExternalApiPrincipal(
                                clientId = "client-1",
                                actorIds = setOf("bot-1"),
                                participantIds = setOf("participant-1"),
                                accountIds = setOf("account-1")
                            )
                        )
                    )
                )
            ),
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer wrong",
                    "Idempotency-Key" to "idem-cancel-by-client-order-auth"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-auth",
                    "traceId" to "trace-cancel-by-client-order-auth",
                    "correlationId" to "corr-cancel-by-client-order-auth",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1",
                    "clientOrderId" to "co-auth-before-lookup",
                    "reason" to "test cancel"
                )
            )

            assertEquals(401, response.status)
            assertContains(response.body, "\"code\":\"UNAUTHORIZED\"")
            assertEquals(0, gateway.cancelCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderReturns404WhenClientOrderUnknown() {
        val server = testServerWithGateway(gateway = EchoOrderEngineGateway())
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-missing"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-missing",
                    "traceId" to "trace-cancel-by-client-order-missing",
                    "correlationId" to "corr-cancel-by-client-order-missing",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1",
                    "clientOrderId" to "co-unknown",
                    "reason" to "test cancel"
                )
            )

            assertEquals(404, response.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderReturns400WhenJsonMalformed() {
        val server = testServerWithGateway(gateway = EchoOrderEngineGateway())
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-malformed"
                ),
                body = """{"participantId":"""
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "invalid json payload")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderReturns400WhenMissingRequiredFields() {
        val server = testServerWithGateway(gateway = EchoOrderEngineGateway())
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-invalid"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-invalid",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1"
                )
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "missing required field: clientOrderId")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ProjectionMutationEndpointsRequireApiWriteBoundary() {
        val server = testServerWithGateway(StaticAcceptedEngineGateway())
        try {
            val lifecycleDenied = post(
                server.address.port,
                "/api/v1/orders/lifecycle-state",
                emptyMap(),
                ""
            )
            val snapshotsDenied = post(
                server.address.port,
                "/api/v1/market-data/snapshots",
                emptyMap(),
                ""
            )
            val lifecycleAllowed = post(
                server.address.port,
                "/api/v1/orders/lifecycle-state",
                mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-lifecycle-rebuild"
                ),
                ""
            )
            val snapshotsAllowed = post(
                server.address.port,
                "/api/v1/market-data/snapshots",
                mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-snapshot-refresh"
                ),
                ""
            )

            assertEquals(401, lifecycleDenied.status)
            assertContains(lifecycleDenied.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            assertEquals(401, snapshotsDenied.status)
            assertContains(snapshotsDenied.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            assertEquals(200, lifecycleAllowed.status)
            assertEquals(200, snapshotsAllowed.status)
        } finally {
            server.stop(0)
        }
    }
}
