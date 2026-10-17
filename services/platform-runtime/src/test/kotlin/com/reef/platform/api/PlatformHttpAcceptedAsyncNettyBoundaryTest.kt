package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformHttpAcceptedAsyncNettyBoundaryTest : PlatformHttpBoundaryTestSupport() {
    @Test
    fun asyncCommandStatsEndpointReturnsQueueDepths() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-async-stats"
                ),
                body = validSubmitBody("cmd-async-stats", "trace-async-stats", "ord-async-stats")
            )
            val stats = get(server.address.port, "/internal/commands/async/stats")

            assertEquals(202, submit.status)
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"captured-ack\"")
            assertContains(stats.body, "\"workerThreads\":1")
            assertContains(stats.body, "\"sampleMs\":100")
            assertContains(stats.body, "\"RECEIVED\":1")
            assertContains(stats.body, "\"PROCESSING\":0")
            assertContains(stats.body, "\"COMPLETED\":0")
            assertContains(stats.body, "\"FAILED\":0")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun acceptedAsyncSubmitReturnsReceiptBeforeEngineCompletes() {
        val gateway = BlockingFirstSubmitGateway()
        val server = testServerWithGateway(
            gateway = gateway,
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-accepted-async",
                    "Idempotency-Key" to "idem-accepted-async"
                ),
                body = validSubmitBody("cmd-accepted-async", "trace-accepted-async", "ord-accepted-async")
            )
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-accepted-async",
                headers = apiReadHeaders("client-accepted-async")
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-accepted-async\"")
            assertContains(response.body, "\"processingMode\":\"accepted-async\"")
            assertTrue(gateway.awaitFirstSubmit())
            assertEquals(200, status.status)
            assertContains(status.body, "\"processingMode\":\"accepted-async\"")
            assertTrue(status.body.contains("\"status\":\"ACCEPTED\"") || status.body.contains("\"status\":\"IN_FLIGHT\""))
            assertTrue(status.body.contains("\"internalStatus\":\"RECEIVED\"") || status.body.contains("\"internalStatus\":\"PROCESSING\""))

            gateway.release()
            val completed = waitForCommandStatus(
                server.address.port,
                "cmd-accepted-async",
                "COMPLETED",
                headers = apiReadHeaders("client-accepted-async")
            )
            assertContains(completed.body, "\"status\":\"COMPLETED\"")
            assertContains(completed.body, "\"responseStatus\":200")
        } finally {
            gateway.release()
            server.stop(0)
        }
    }

    @Test
    fun acceptedAsyncStatsEndpointReportsLaneDrain() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-accepted-async-stats",
                    "Idempotency-Key" to "idem-accepted-async-stats"
                ),
                body = validSubmitBody("cmd-accepted-async-stats", "trace-accepted-async-stats", "ord-accepted-async-stats")
            )
            waitForCommandStatus(
                server.address.port,
                "cmd-accepted-async-stats",
                "COMPLETED",
                headers = apiReadHeaders("client-accepted-async-stats")
            )
            val stats = get(server.address.port, "/internal/commands/async/stats")

            assertEquals(202, response.status)
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"accepted-async\"")
            assertContains(stats.body, "\"acceptedAsync\"")
            assertContains(stats.body, "\"enabled\":true")
            assertContains(stats.body, "\"activeLaneCount\"")
            assertContains(stats.body, "\"lanes\"")
            assertContains(stats.body, "\"received\":1")
            assertContains(stats.body, "\"completed\":1")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun nettyHotPathAcceptedAsyncSubmitStatusAndStats() {
        val server = testNettyHotPathServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val health = get(server.port, "/health")
            val response = post(
                port = server.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-accepted-async",
                    "Idempotency-Key" to "idem-netty-accepted-async"
                ),
                body = validSubmitBody("cmd-netty-accepted-async", "trace-netty-accepted-async", "ord-netty-accepted-async")
            )
            val completed = waitForCommandStatus(
                server.port,
                "cmd-netty-accepted-async",
                "COMPLETED",
                headers = apiReadHeaders("client-netty-accepted-async")
            )
            val stats = get(server.port, "/internal/commands/async/stats")

            assertEquals(200, health.status)
            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-netty-accepted-async\"")
            assertEquals(200, completed.status)
            assertContains(completed.body, "\"status\":\"COMPLETED\"")
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"accepted-async\"")
            assertContains(stats.body, "\"inFlightPerLane\"")
            assertContains(stats.body, "\"completed\":1")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathServesAdminRiskGatewayReads() {
        val riskStore = RecordingAccountRiskStore()
        riskStore.upsertControl(
            scopeType = "ACCOUNT",
            scopeId = "account-1",
            decision = AccountRiskDecision.REJECT,
            reason = "test risk",
            maxQuantityUnits = "",
            maxNotional = "",
            currency = "USD"
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            accountRiskCheck = riskStore,
            localDevAdminAuthBypass = true
        )
        try {
            val controls = get(server.port, "/admin/v1/risk/account-controls")

            assertEquals(200, controls.status, controls.body)
            assertContains(controls.body, "\"scopeId\":\"account-1\"")
            assertContains(controls.body, "\"decision\":\"REJECT\"")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathStreamAckPublishesThroughPartitionedPipeline() {
        val publisher = RecordingStreamCommandPublisher()
        val streamPublisher = PartitionedStreamCommandPublisher(
            delegate = publisher,
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = streamPublisher
        )
        try {
            val response = post(
                port = server.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream"
                ),
                body = validSubmitBody("cmd-netty-stream", "trace-netty-stream", "ord-netty-stream", extra = streamRoutingExtra())
            )
            val health = get(server.port, "/internal/stream-ack/health")

            assertEquals(202, response.status)
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-netty-stream\"")
            assertEquals(1, publisher.published.size)
            assertEquals(200, health.status)
            assertContains(health.body, "\"publishMode\":\"partitioned-blocking-delegate:sync\"")
            assertContains(health.body, "\"publishLaneCount\":16")
            assertContains(health.body, "\"publishCompleted\":1")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathStreamAckPublishesModifyAndCancelRoutes() {
        val publisher = RecordingStreamCommandPublisher()
        val streamPublisher = PartitionedStreamCommandPublisher(
            delegate = publisher,
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = streamPublisher
        )
        try {
            val modifyCancelRoutingExtra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
            val modify = post(
                port = server.port,
                path = "/api/v1/orders/modify",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream-modify"
                ),
                body = validModifyBody("cmd-netty-stream-modify", "trace-netty-stream-modify", "ord-netty-stream", extra = modifyCancelRoutingExtra)
            )
            val cancel = post(
                port = server.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream-cancel"
                ),
                body = validCancelBody("cmd-netty-stream-cancel", "trace-netty-stream-cancel", "ord-netty-stream", extra = modifyCancelRoutingExtra)
            )

            assertEquals(202, modify.status, modify.body)
            assertEquals(202, cancel.status, cancel.body)
            assertContains(modify.body, "\"processingMode\":\"stream-ack\"")
            assertContains(cancel.body, "\"processingMode\":\"stream-ack\"")
            assertEquals(
                listOf("/api/v1/orders/modify", "/api/v1/orders/cancel"),
                publisher.published.map { it.route }
            )
            assertEquals(listOf("ModifyOrder", "CancelOrder"), publisher.published.map { it.commandType })
        } finally {
            server.stop()
        }
    }
}
