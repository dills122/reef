package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PlatformHttpStreamAckBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun streamAckPublishesCommandAndReturnsAcceptedReferenceWithoutExecutingEngine() {
        val publisher = RecordingStreamCommandPublisher()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-1"
                ),
                body = validSubmitBody("cmd-stream-1", "trace-stream-1", "ord-stream-1", extra = streamRoutingExtra())
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-stream-1\"")
            assertContains(response.body, "\"status\":\"ACCEPTED\"")
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-stream-1\"")
            assertEquals(1, publisher.published.size)
            assertEquals("client-1|/api/v1/orders/submit|idem-stream-1", publisher.published.single().natsMessageId)
            assertEquals(0, gateway.submitCalls)

            val hotPath = get(server.address.port, "/internal/perf/hot-path")
            assertEquals(200, hotPath.status)
            assertContains(hotPath.body, "\"api.streamAck.reserve\"")
            assertContains(hotPath.body, "\"api.streamAck.publishAck\"")
            assertContains(hotPath.body, "\"api.streamAck.markPublished\"")
            assertContains(hotPath.body, "\"api.streamAck.total\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckCommandStatusReturnsScopedStreamReferenceBeforeMaterialization() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-status-pending"
                ),
                body = validSubmitBody("cmd-stream-status-pending", "trace-stream-status-pending", "ord-stream-status-pending", extra = streamRoutingExtra())
            )
            assertEquals(202, submit.status)

            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-stream-status-pending",
                headers = apiReadHeaders()
            )

            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"source\":\"stream_reference\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRiskDisabledBotRejectsBeforeReserveOrPublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher,
            accountRiskCheck = StaticAccountRiskCheck(disabledBots = setOf("bot-1"))
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-risk-disabled-bot"
                ),
                body = validSubmitBody("cmd-stream-risk-disabled-bot", "trace-stream-risk-disabled-bot", "ord-stream-risk-disabled-bot", extra = streamRoutingExtra())
            )

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"BOT_DISABLED\"")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-risk-disabled-bot"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckReplaysSameIdempotencyKeyAndBodyWithoutRepublishing() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-replay"
            )
            val body = validSubmitBody("cmd-stream-replay", "trace-stream-replay", "ord-stream-replay", extra = streamRoutingExtra())
            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            val second = post(server.address.port, "/api/v1/orders/submit", headers, body)

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertContains(first.body, "\"commandId\":\"cmd-stream-replay\"")
            assertContains(second.body, "\"commandId\":\"cmd-stream-replay\"")
            assertContains(second.body, "\"statusUrl\":\"/api/v1/commands/cmd-stream-replay\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsSameIdempotencyKeyWithDifferentBody() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-conflict"
            )
            val first = post(
                server.address.port,
                "/api/v1/orders/submit",
                headers,
                validSubmitBody("cmd-stream-conflict", "trace-stream-conflict", "ord-stream-conflict", extra = streamRoutingExtra())
            )
            val second = post(
                server.address.port,
                "/api/v1/orders/submit",
                headers,
                validSubmitBody("cmd-stream-conflict-2", "trace-stream-conflict-2", "ord-stream-conflict-2", extra = streamRoutingExtra())
            )

            assertEquals(202, first.status)
            assertEquals(409, second.status)
            assertContains(second.body, "\"code\":\"IDEMPOTENCY_PAYLOAD_CONFLICT\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRequiresRoutingMetadataBeforePublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-missing-routing"
                ),
                body = bodyWithoutField(
                    validCancelBody("cmd-stream-missing-routing", "trace-stream-missing-routing", "ord-stream-missing-routing"),
                    "runId"
                )
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "missing required field: runId")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-missing-routing"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun hotPathRejectsWrongInstrumentQuoteBeforeReserveOrPublish() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val publisher = RecordingStreamCommandPublisher()
        val intake = InMemoryStreamCommandIntakeStore()
        val server = PlatformHttpServer(port = 0,
            boundary = ExternalApiBoundary(), idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            api = PlatformApi(OrderApplicationService(EchoOrderEngineGateway(), persistence)),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intake, streamCommandPublisher = publisher)
        val headers = Headers().apply { add("X-Client-Id", "client-1"); add("Idempotency-Key", "hot-wrong-quote") }
        val response = server.handleHotPathRequest(PlatformHotPathRequest("POST", "/api/v1/orders/submit", null, headers,
            "127.0.0.1", validSubmitBody("hot-wrong-quote", "hot-wrong-quote", "hot-wrong-quote", extra = streamRoutingExtra()).replace("\"USD\"", "\"CAD\"")))
        assertEquals(400, response?.status)
        assertContains(response?.body.orEmpty(), "CURRENCY_MISMATCH")
        assertEquals(0, publisher.published.size)
        assertEquals(null, intake.findByCommandId("hot-wrong-quote"))
    }

    @Test
    fun streamAckRejectsWrongInstrumentQuoteBeforeReserveOrPublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(gateway = gateway, commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore, streamCommandPublisher = publisher)
        try {
            val response = post(server.address.port, "/api/v1/orders/submit",
                mapOf("X-Client-Id" to "client-1", "Idempotency-Key" to "wrong-quote"),
                validSubmitBody("wrong-quote", "wrong-quote", "wrong-quote", extra = streamRoutingExtra()).replace("\"USD\"", "\"CAD\""))
            assertEquals(400, response.status)
            assertContains(response.body, "CURRENCY_MISMATCH")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("wrong-quote"))
        } finally { server.stop(0) }
    }

    @Test
    fun streamAckRejectsMalformedJsonBeforeReserveOrPublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-malformed"
                ),
                body = """{"commandId":"cmd-stream-malformed""""
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "\"message\":\"invalid json payload\"")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-malformed"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckReturnsUnavailableWhenPublishAckFails() {
        val publisher = RecordingStreamCommandPublisher(fail = true)
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-publish-fail"
                ),
                body = validSubmitBody("cmd-stream-publish-fail", "trace-stream-publish-fail", "ord-stream-publish-fail", extra = streamRoutingExtra())
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"stream command publish unavailable\"")
            assertEquals(1, publisher.published.size)
            assertEquals(0L, intakeStore.findByCommandId("cmd-stream-publish-fail")?.streamSequence)

            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-stream-publish-fail",
                headers = apiReadHeaders()
            )
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckLatePublishAckAfterResponseTimeoutReplaysAcceptedReference() {
        val store = InMemoryStreamCommandIntakeStore()
        val publisher = DelayedAckStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = store,
            streamCommandPublisher = publisher,
            streamCommandPublishResponseTimeoutMs = 25L
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-late-ack"
            )
            val body = validSubmitBody("cmd-stream-late-ack", "trace-stream-late-ack", "ord-stream-late-ack", extra = streamRoutingExtra())
            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(503, first.status)
            assertContains(first.body, "timed out waiting 25ms")
            assertEquals(1, publisher.published.size)

            val beforeAckStatus = get(
                server.address.port,
                "/api/v1/commands/cmd-stream-late-ack",
                headers = apiReadHeaders()
            )
            assertEquals(404, beforeAckStatus.status)

            publisher.complete(StreamPublishAck("REEF_COMMANDS", 123L))

            val replay = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, replay.status)
            assertContains(replay.body, "\"commandId\":\"cmd-stream-late-ack\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRetryAfterPublishAckBeforeMarkerUpdateRepublishesAndConverges() {
        val store = SkipFirstPublishedMarkerIntakeStore(InMemoryStreamCommandIntakeStore())
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = store,
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-marker-crash"
            )
            val body = validSubmitBody(
                "cmd-stream-marker-crash",
                "trace-stream-marker-crash",
                "ord-stream-marker-crash",
                extra = streamRoutingExtra()
            )

            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, first.status)
            assertContains(first.body, "\"commandId\":\"cmd-stream-marker-crash\"")
            assertEquals(1, publisher.published.size)
            assertEquals(0L, store.findByCommandId("cmd-stream-marker-crash")?.streamSequence)

            val retry = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, retry.status)
            assertContains(retry.body, "\"commandId\":\"cmd-stream-marker-crash\"")
            assertEquals(2, publisher.published.size)
            assertEquals(2L, store.findByCommandId("cmd-stream-marker-crash")?.streamSequence)

            val replay = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, replay.status)
            assertContains(replay.body, "\"commandId\":\"cmd-stream-marker-crash\"")
            assertEquals(2, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckHealthEndpointReturnsStreamSnapshot() {
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = RecordingStreamCommandPublisher(),
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = "REEF_COMMANDS",
                    messageCount = 3,
                    byteCount = 512,
                    maxBytes = 1024,
                    storageUtilization = 0.5,
                    publishAckLastMs = 7,
                    publishAckMaxMs = 11,
                    sourceTopicFrontiers = listOf(
                        SourceTopicFrontier(partition = 2, lastOffsetExclusive = 43)
                    ),
                    acceptedSourceFrontiers = listOf(
                        AcceptedSourceFrontier(
                            partition = 2,
                            accepted = 3,
                            firstOffsetInclusive = 40,
                            lastOffsetExclusive = 43
                        )
                    ),
                    producerMetrics = mapOf("request-latency-max" to 13.0)
                )
            )
        )
        try {
            val response = get(server.address.port, "/internal/stream-ack/health")

            assertEquals(200, response.status)
            assertContains(response.body, "\"available\":true")
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"stream\":\"REEF_COMMANDS\"")
            assertContains(response.body, "\"messages\":3")
            assertContains(response.body, "\"storageUtilization\":0.5")
            assertContains(response.body, "\"publishAckLastMs\":7")
            assertContains(
                response.body,
                "\"sourceTopicFrontiers\":[{\"partition\":2,\"lastOffsetExclusive\":\"43\"}]"
            )
            assertContains(
                response.body,
                "\"acceptedSourceFrontiers\":[{\"partition\":2,\"accepted\":\"3\",\"firstOffsetInclusive\":\"40\",\"lastOffsetExclusive\":\"43\"}]"
            )
            assertContains(response.body, "\"producerMetrics\":{\"request-latency-max\":13.0}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckBackpressureCachesStreamHealthWithinSampleWindow() {
        val publisher = RecordingStreamCommandPublisher()
        val healthCheck = CountingStreamCommandHealthCheck(
            StreamCommandHealthSnapshot(
                available = true,
                streamName = "REEF_COMMANDS",
                byteCount = 100,
                maxBytes = 1000,
                storageUtilization = 0.1
            )
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = healthCheck,
            streamCommandBackpressureSampleMs = 10_000L
        )
        try {
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-cache-1"
                ),
                body = validSubmitBody("cmd-stream-health-cache-1", "trace-stream-health-cache-1", "ord-stream-health-cache-1", extra = streamRoutingExtra())
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-cache-2"
                ),
                body = validSubmitBody("cmd-stream-health-cache-2", "trace-stream-health-cache-2", "ord-stream-health-cache-2", extra = streamRoutingExtra())
            )

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertEquals(2, publisher.published.size)
            assertEquals(1, healthCheck.calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsBeforePublishWhenStreamHealthUnavailable() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = false,
                    streamName = "REEF_COMMANDS",
                    error = "stream not found"
                )
            )
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-down"
                ),
                body = validSubmitBody("cmd-stream-health-down", "trace-stream-health-down", "ord-stream-health-down", extra = streamRoutingExtra())
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"code\":\"STREAM_COMMAND_STREAM_UNAVAILABLE\"")
            assertEquals(0, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsBeforePublishWhenStreamStorageIsOverThreshold() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = "REEF_COMMANDS",
                    byteCount = 950,
                    maxBytes = 1000,
                    storageUtilization = 0.95
                )
            ),
            streamCommandMaxStorageUtilization = 0.90
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-storage-full"
                ),
                body = validSubmitBody("cmd-stream-storage-full", "trace-stream-storage-full", "ord-stream-storage-full", extra = streamRoutingExtra())
            )

            assertEquals(429, response.status)
            assertContains(response.body, "\"code\":\"STREAM_COMMAND_BACKPRESSURE\"")
            assertEquals(0, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }
}
