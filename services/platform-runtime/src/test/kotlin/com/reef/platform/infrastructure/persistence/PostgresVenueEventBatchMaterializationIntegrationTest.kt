package com.reef.platform.infrastructure.persistence

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostgresVenueEventBatchMaterializationIntegrationTest {
    @Test
    fun materializesVenueEventBatchIdempotentlyWhenMigratedPostgresIsAvailable() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val batch = venueEventBatch(suffix)

        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(0, persistence.materializeVenueEventBatch(batch))

        val outcome = persistence.canonicalCommandOutcome("cmd-$suffix")
        assertNotNull(outcome)
        assertEquals("batch-$suffix", outcome.batchId)
        assertEquals("engine-0", outcome.shardId)
        assertEquals(batch.partition, outcome.partition)
        assertEquals(1001L, outcome.streamSequence)
        assertEquals("CancelOrder", outcome.commandType)
        assertEquals("rejected", outcome.resultStatus)
        assertEquals("ORDER_ALREADY_FILLED", outcome.rejectCode)

        val reference = persistence.venueEventBatchCommandReference("cmd-$suffix")
        assertNotNull(reference)
        assertEquals("batch-$suffix", reference.batchId)
        assertEquals("engine-0", reference.shardId)
        assertEquals(batch.partition, reference.partition)
        assertEquals(1001L, reference.streamSequence)
        assertEquals("CancelOrder", reference.commandType)
        assertEquals("ORDER_ALREADY_FILLED", reference.rejectCode)

        assertFailsWith<Exception> {
            persistence.materializeVenueEventBatch(batch.copy(payloadChecksum = "different-$suffix"))
        }
    }

    @Test
    fun projectsMaterializedSubmitOutcomesWhenMigratedPostgresIsAvailable() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val projectionDataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            projectionDataSource = projectionDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-normalized-venue-outcomes-$suffix"
        val sequence = uniqueSequence(suffix)
        val batch = submitVenueEventBatch(suffix, sequence)

        insertCommandPayload(dataSource, "submit-cmd-$suffix", submitCommandPayload(suffix))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))
        assertEquals(0, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))
        assertEquals(1, countRows(dataSource, "runtime.projection_batch_claims", projectionName))

        val result = persistence.submitResult("submit-cmd-$suffix")
        assertNotNull(result)
        assertEquals("submit-event-$suffix", result.accepted?.eventId)
        assertEquals("submit-order-$suffix", result.accepted?.orderId)
        assertEquals("engine-order-$suffix", result.accepted?.engineOrderId)
        assertEquals("2026-07-04T18:01:00Z", result.accepted?.occurredAt)

        val events = persistence.eventsForOrder("submit-order-$suffix")
        assertEquals(1, events.size)
        assertEquals("OrderAccepted", events.first().eventType)
        assertEquals("venue-event-batch-projector", events.first().producer)
        assertEquals("trace-$suffix", events.first().traceId)
        assertEquals("cause-$suffix", events.first().causationId)
        assertEquals("corr-$suffix", events.first().correlationId)
        val order = persistence.acceptedOrder("submit-order-$suffix")
        assertNotNull(order)
        assertEquals("AAPL", order.instrumentId)
        assertEquals("participant-$suffix", order.participantId)
        assertEquals("account-$suffix", order.accountId)
        assertEquals("100", order.quantityUnits)

        val status = persistence.projectionStatus(projectionName, listOf(batch.partition), source = "venue-event-batch")
        assertEquals(0, status.lag)
        assertEquals(sequence, status.watermarks.single().lastPartitionSequence)
    }

    @Test
    fun projectsExecutionsAndTradesCarriedInResultPayloadWhenMigratedPostgresIsAvailable() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-normalized-venue-fills-$suffix"
        val batch = fillingVenueEventBatch(suffix)

        insertCommandPayload(dataSource, "match-cmd-$suffix", submitCommandPayload(suffix).replace("submit-cmd-$suffix", "match-cmd-$suffix"))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))
        assertEquals(1, countRows(dataSource, "runtime.projection_batch_claims", projectionName))

        val executions = persistence.executionsForOrder("match-order-$suffix")
        assertEquals(1, executions.size)
        assertEquals("exec-$suffix", executions.single().executionId)
        assertEquals("150250000000", executions.single().executionPrice)
        assertEquals("MAKER", executions.single().liquidityRole)

        val trades = persistence.tradesForOrder("match-order-$suffix")
        assertEquals(1, trades.size)
        assertEquals("trade-$suffix", trades.single().tradeId)
        assertEquals("match-order-$suffix", trades.single().buyOrderId)
        assertEquals("resting-order-$suffix", trades.single().sellOrderId)
    }

    @Test
    fun canonicalBatchIdentityMatchesPostgresEncodingWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val candidates = listOf(
            ProjectionBatchIdentityCandidate(3, 844424930131969, "command-a", "batch-a", "SubmitOrder", "payload-hash-a"),
            ProjectionBatchIdentityCandidate(7, 1970324836974593, "command-b", "batch-b", "CancelOrder", "payload-hash-b")
        )
        val candidatesJson = projectionCandidatesJson(candidates)

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT runtime.runtime_projection_batch_identity_v1(?, ?, ?, ?, ?::jsonb)"
            ).use { ps ->
                ps.setString(1, "runtime-command-status")
                ps.setString(2, "REEF_VENUE_EVENTS")
                ps.setString(3, ProjectionStage.CommandStatus.configValue)
                ps.setBoolean(4, false)
                ps.setString(5, candidatesJson)
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(
                        ProjectionBatchIdentityV1.digest(
                            "runtime-command-status",
                            "REEF_VENUE_EVENTS",
                            ProjectionStage.CommandStatus,
                            false,
                            candidates
                        ),
                        rs.getString(1)
                    )
                }
            }
        }
    }

    @Test
    fun compatBootstrapPreservesMigratedSameStoreClaimWrapperWhenPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        assertTrue(projectCommandOutcomesFunctionDefinition(dataSource).contains("runtime_claim_projection_batch_v1"))

        PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Compat
        )

        assertTrue(projectCommandOutcomesFunctionDefinition(dataSource).contains("runtime_claim_projection_batch_v1"))
    }

    @Test
    fun sameStoreProjectionAppliesTheMembershipClaimedBeforeConcurrentMaterialization() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-claimed-membership-$suffix"
        val partitionA = uniquePartition(suffix, 7)
        val partitionB = uniquePartition(suffix, 8)
        val sequenceBase = uniqueSequence(suffix) * 10
        val triggerToken = suffix.replace("-", "")
        val triggerFunction = "runtime.test_pause_projection_claim_$triggerToken"
        val triggerName = "test_pause_projection_claim_$triggerToken"
        val advisoryKey = suffix.hashCode().toLong() * 31L + 17L
        val executor = Executors.newSingleThreadExecutor()

        materializeTestSubmit(persistence, dataSource, suffix, "a1", partitionA, sequenceBase + 1)
        materializeTestSubmit(persistence, dataSource, suffix, "b1", partitionB, sequenceBase + 3)
        materializeTestSubmit(persistence, dataSource, suffix, "b2", partitionB, sequenceBase + 4)

        dataSource.connection.use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE FUNCTION $triggerFunction() RETURNS trigger
                    LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                      IF NEW.projection_name = '$projectionName' THEN
                        PERFORM pg_advisory_lock($advisoryKey);
                        PERFORM pg_advisory_unlock($advisoryKey);
                      END IF;
                      RETURN NEW;
                    END;
                    ${'$'}${'$'}
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TRIGGER $triggerName
                    AFTER INSERT ON runtime.projection_batch_claims
                    FOR EACH ROW EXECUTE FUNCTION $triggerFunction()
                    """.trimIndent()
                )
            }
        }

        try {
            dataSource.connection.use { blocker ->
                blocker.prepareStatement("SELECT pg_advisory_lock(?)").use { ps ->
                    ps.setLong(1, advisoryKey)
                    ps.execute()
                }
                try {
                    val projected = executor.submit<Long> {
                        persistence.projectCanonicalCommandOutcomes(
                            projectionName,
                            3,
                            listOf(partitionA, partitionB),
                            includeFills = true,
                            eventStream = "REEF_VENUE_EVENTS"
                        )
                    }
                    waitForBlockedAdvisoryLock(dataSource)

                    // This row becomes eligible after the claim. The old same-store wrapper
                    // reselected and substituted a2 for b2 because both batches had size 3.
                    materializeTestSubmit(persistence, dataSource, suffix, "a2", partitionA, sequenceBase + 2)

                    blocker.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                        ps.setLong(1, advisoryKey)
                        ps.execute()
                    }

                    assertEquals(3, projected.get(10, TimeUnit.SECONDS))
                    assertEquals(1, countRows(dataSource, "runtime.runtime_events", "submit-order-$suffix-b2", "order_id"))
                    assertEquals(0, countRows(dataSource, "runtime.runtime_events", "submit-order-$suffix-a2", "order_id"))
                } finally {
                    blocker.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                        ps.setLong(1, advisoryKey)
                        ps.execute()
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            dataSource.connection.use { conn ->
                conn.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $triggerName ON runtime.projection_batch_claims")
                    statement.execute("DROP FUNCTION IF EXISTS $triggerFunction()")
                }
            }
        }
    }

    @Test
    fun sameStoreProjectionUsesConfiguredRetryHorizon() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val horizonMs = 1_234L
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            envLookup = projectionRetryEnv(horizonMs = horizonMs)
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-same-store-horizon-$suffix"
        val batch = submitVenueEventBatch(suffix, uniqueSequence(suffix))

        insertCommandPayload(dataSource, "submit-cmd-$suffix", submitCommandPayload(suffix))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))

        val storedHorizonMs = dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT EXTRACT(EPOCH FROM (retry_deadline_at - created_at)) * 1000
                FROM runtime.projection_batch_claims
                WHERE projection_name = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getDouble(1)
                }
            }
        }

        assertTrue(storedHorizonMs in (horizonMs - 250.0)..(horizonMs + 250.0))
    }

    @Test
    fun sameStoreExactDuplicateClaimSkipsEffectsAfterWatermarkRewind() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-same-store-duplicate-$suffix"
        val batch = submitVenueEventBatch(suffix, uniqueSequence(suffix))

        insertCommandPayload(dataSource, "submit-cmd-$suffix", submitCommandPayload(suffix))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE runtime.projection_watermarks
                SET last_partition_seq = 0, updated_at = now()
                WHERE projection_name = ? AND partition_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setInt(2, batch.partition)
                assertEquals(1, ps.executeUpdate())
            }
        }

        assertEquals(0, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))
        assertEquals(1, countRows(dataSource, "runtime.projection_batch_claims", projectionName))
        assertEquals(1, countRows(dataSource, "runtime.runtime_events", "submit-order-$suffix", "order_id"))
    }

    @Test
    fun ambiguousCommitDoesNotRepeatProjectionEffectsWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val orderId = "submit-order-$suffix"
        val projectionDataSource = CommitAmbiguityOnceDataSource(dataSource) {
            dataSource.connection.use { conn ->
                conn.prepareStatement("DELETE FROM runtime.order_lifecycle_dirty WHERE order_id = ?").use { ps ->
                    ps.setString(1, orderId)
                    assertEquals(1, ps.executeUpdate())
                }
            }
        }
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            projectionDataSource = projectionDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            envLookup = projectionRetryEnv()
        )
        val projectionName = "runtime-ambiguous-commit-$suffix"

        insertCommandPayload(dataSource, "submit-cmd-$suffix", submitCommandPayload(suffix))

        // The first commit applied the batch; the retry reads the completed claim and
        // returns zero so the outer worker cannot count the same work twice.
        val batch = submitVenueEventBatch(suffix, uniqueSequence(suffix))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO runtime.order_lifecycle_dirty(order_id) VALUES (?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, orderId)
                assertEquals(1, ps.executeUpdate())
            }
        }

        assertEquals(0, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition)))
        assertEquals(1, countRows(dataSource, "runtime.projection_batch_claims", projectionName))
        assertEquals(1, countRows(dataSource, "runtime.submit_results", "submit-cmd-$suffix", "command_id"))
        assertEquals(1, countRows(dataSource, "runtime.runtime_events", "submit-order-$suffix", "order_id"))
        assertEquals(0, countRows(dataSource, "runtime.order_lifecycle_dirty", orderId, "order_id"))
    }

    @Test
    fun retryPastItsDatabaseDeadlineIsFencedWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val projectionDataSource = CommitAmbiguityOnceDataSource(dataSource, delayAfterCommitMs = 30)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            projectionDataSource = projectionDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            envLookup = projectionRetryEnv(horizonMs = 5)
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-expired-retry-$suffix"

        insertCommandPayload(dataSource, "submit-cmd-$suffix", submitCommandPayload(suffix))
        val batch = submitVenueEventBatch(suffix, uniqueSequence(suffix))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))

        assertFailsWith<IllegalStateException> {
            persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(batch.partition))
        }
        assertEquals(1, countRows(dataSource, "runtime.projection_batch_claims", projectionName))
        assertEquals(1, countRows(dataSource, "runtime.runtime_events", "submit-order-$suffix", "order_id"))
    }

    @Test
    fun separatedStoreDeadlineFencesCandidatesPausedBeforeTheirClaim() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val projectionDataSource = RuntimeDataSources.dataSource(
            System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST"),
            System.getenv("RUNTIME_POSTGRES_USER_TEST"),
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")
        )
        val pausingCanonicalDataSource = PauseAfterCandidateReadDataSource(dataSource)
        val env = projectionRetryEnv(horizonMs = 500)
        val pausedPersistence = PostgresRuntimePersistence(
            dataSource = pausingCanonicalDataSource,
            projectionDataSource = projectionDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            envLookup = env
        )
        val advancingPersistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            projectionDataSource = projectionDataSource,
            bootstrapMode = PostgresBootstrapMode.Validate,
            envLookup = env
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-separated-late-claim-$suffix"
        val partition = uniquePartition(suffix, 9)
        val sequenceBase = uniqueSequence(suffix) * 10
        val executor = Executors.newSingleThreadExecutor()

        materializeTestSubmit(advancingPersistence, dataSource, suffix, "a", partition, sequenceBase + 1)
        materializeTestSubmit(advancingPersistence, dataSource, suffix, "b", partition, sequenceBase + 2)

        try {
            val pausedResult = executor.submit<Throwable?> {
                try {
                    pausedPersistence.projectCanonicalCommandOutcomes(
                        projectionName,
                        1,
                        listOf(partition),
                        includeFills = true,
                        eventStream = "REEF_VENUE_EVENTS"
                    )
                    null
                } catch (ex: Throwable) {
                    ex
                }
            }
            assertTrue(pausingCanonicalDataSource.awaitCandidateRead())

            assertEquals(
                1,
                advancingPersistence.projectCanonicalCommandOutcomes(
                    projectionName,
                    1,
                    listOf(partition),
                    includeFills = true,
                    eventStream = "REEF_VENUE_EVENTS"
                )
            )
            assertEquals(
                1,
                advancingPersistence.projectCanonicalCommandOutcomes(
                    projectionName,
                    1,
                    listOf(partition),
                    includeFills = true,
                    eventStream = "REEF_VENUE_EVENTS"
                )
            )
            val originalEarliestClaim = earliestClaimCreatedAt(dataSource, projectionName)
            Thread.sleep(750)

            pausingCanonicalDataSource.releaseCandidateRead()
            val failure = pausedResult.get(10, TimeUnit.SECONDS)

            assertTrue(failure is IllegalStateException, "expected expired authority failure, got $failure")
            assertEquals(originalEarliestClaim, earliestClaimCreatedAt(dataSource, projectionName))
            assertEquals(2, countRows(dataSource, "runtime.projection_batch_claims", projectionName))
        } finally {
            pausingCanonicalDataSource.releaseCandidateRead()
            executor.shutdownNow()
        }
    }

    private fun fillingVenueEventBatch(suffix: String): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "fill-batch-$suffix",
            shardId = "engine-0",
            partition = uniquePartition(suffix, 6),
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 2001,
            lastSequence = 2001,
            commandCount = 1,
            createdAt = "2026-07-04T18:02:00Z",
            payloadChecksum = "fill-checksum-$suffix",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "match-cmd-$suffix",
                    commandType = "SubmitOrder",
                    streamSequence = 2001,
                    deliveredCount = 1,
                    payloadHash = "match-payload-hash-$suffix",
                    instrumentId = "AAPL",
                    orderId = "match-order-$suffix",
                    resultStatus = "accepted",
                    resultPayloadJson = """
                        {
                          "accepted":{"eventId":"match-event-$suffix","engineOrderId":"match-engine-order-$suffix","occurredAt":"2026-07-04T18:02:00Z"},
                          "executions":[{"eventId":"exec-evt-$suffix","executionId":"exec-$suffix","orderId":"match-order-$suffix","instrumentId":"AAPL","quantityUnits":"100","executionPrice":"150250000000","currency":"USD","occurredAt":"2026-07-04T18:02:00Z","liquidityRole":"MAKER"}],
                          "trades":[{"eventId":"trade-evt-$suffix","tradeId":"trade-$suffix","executionId":"exec-$suffix","buyOrderId":"match-order-$suffix","sellOrderId":"resting-order-$suffix","instrumentId":"AAPL","quantityUnits":"100","price":"150250000000","currency":"USD","occurredAt":"2026-07-04T18:02:00Z"}]
                        }
                    """.trimIndent()
                )
            )
        )
    }

    private fun insertCommandPayload(dataSource: javax.sql.DataSource, commandId: String, payloadJson: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO command_log.command_payloads(command_id, payload_json)
                VALUES (?, ?::jsonb)
                ON CONFLICT (command_id) DO UPDATE SET payload_json = EXCLUDED.payload_json
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setString(2, payloadJson)
                ps.executeUpdate()
            }
        }
    }

    private fun materializeTestSubmit(
        persistence: PostgresRuntimePersistence,
        dataSource: DataSource,
        suffix: String,
        label: String,
        partition: Int,
        sequence: Long
    ) {
        val labeledSuffix = "$suffix-$label"
        insertCommandPayload(dataSource, "submit-cmd-$labeledSuffix", submitCommandPayload(labeledSuffix))
        assertEquals(
            1,
            persistence.materializeVenueEventBatch(
                submitVenueEventBatch(labeledSuffix, sequence).copy(partition = partition)
            )
        )
    }

    private fun waitForBlockedAdvisoryLock(dataSource: DataSource) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted)"
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }
            if (waiting) return
            Thread.sleep(10)
        }
        error("projection claim did not reach the advisory-lock test hook")
    }

    private fun migratedDataSourceOrNull(): DataSource? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        return RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
    }

    private fun projectionCandidatesJson(candidates: List<ProjectionBatchIdentityCandidate>): String {
        return com.reef.platform.api.JsonCodec.writeArray(
            candidates.map { candidate ->
                mapOf(
                    "partitionId" to candidate.partitionId,
                    "streamSequence" to candidate.streamSequence,
                    "commandId" to candidate.commandId,
                    "canonicalBatchId" to candidate.canonicalBatchId,
                    "commandType" to candidate.commandType,
                    "payloadHash" to candidate.payloadHash
                )
            }
        )
    }

    private fun projectCommandOutcomesFunctionDefinition(dataSource: DataSource): String {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT pg_get_functiondef(
                  'runtime.runtime_project_canonical_command_outcomes(text,integer,integer[],boolean,text,bigint)'::regprocedure
                )
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1)
                }
            }
        }
    }

    private fun projectionRetryEnv(horizonMs: Long = 60_000): (String) -> String? = { key ->
        when (key) {
            "STREAM_ACK_PROJECTOR_DB_RETRY_ATTEMPTS" -> "2"
            "STREAM_ACK_PROJECTOR_DB_RETRY_BACKOFF_MS" -> "0"
            "STREAM_ACK_PROJECTOR_DB_RETRY_HORIZON_MS" -> horizonMs.toString()
            else -> null
        }
    }

    private fun countRows(
        dataSource: DataSource,
        table: String,
        value: String,
        column: String = "projection_name"
    ): Long {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun earliestClaimCreatedAt(dataSource: DataSource, projectionName: String): Timestamp {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT MIN(created_at) FROM runtime.projection_batch_claims WHERE projection_name = ?"
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getTimestamp(1)
                }
            }
        }
    }

    private class CommitAmbiguityOnceDataSource(
        private val delegate: DataSource,
        private val delayAfterCommitMs: Long = 0,
        private val afterAmbiguousCommit: () -> Unit = {}
    ) : DataSource by delegate {
        private val failNextCommit = AtomicBoolean(true)

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(username: String?, password: String?): Connection =
            wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java)
            ) { _, method, args ->
                if (method.name == "commit" && failNextCommit.compareAndSet(true, false)) {
                    invoke(connection, method, args)
                    afterAmbiguousCommit()
                    if (delayAfterCommitMs > 0) Thread.sleep(delayAfterCommitMs)
                    throw SQLException("simulated ambiguous projection commit", "40001")
                }
                invoke(connection, method, args)
            } as Connection
        }

        private fun invoke(target: Connection, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            return try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (ex: InvocationTargetException) {
                throw ex.targetException
            }
        }
    }

    private class PauseAfterCandidateReadDataSource(
        private val delegate: DataSource
    ) : DataSource by delegate {
        private val candidateRead = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val pauseNextCandidate = AtomicBoolean(true)

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(username: String?, password: String?): Connection =
            wrap(delegate.getConnection(username, password))

        fun awaitCandidateRead(): Boolean = candidateRead.await(10, TimeUnit.SECONDS)

        fun releaseCandidateRead() {
            release.countDown()
        }

        private fun wrap(connection: Connection): Connection {
            val candidateQueryPrepared = AtomicBoolean(false)
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java)
            ) { _, method, args ->
                if (method.name == "prepareStatement") {
                    val sql = args?.firstOrNull() as? String
                    if (sql != null &&
                        sql.contains("FROM runtime.canonical_command_outcomes canonical") &&
                        sql.contains("ORDER BY stream_sequence")
                    ) {
                        candidateQueryPrepared.set(true)
                    }
                }
                if (method.name == "close" &&
                    candidateQueryPrepared.get() &&
                    pauseNextCandidate.compareAndSet(true, false)
                ) {
                    candidateRead.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) {
                        "timed out waiting to release captured projection candidates"
                    }
                }
                invoke(connection, method, args)
            } as Connection
        }

        private fun invoke(target: Connection, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            return try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (ex: InvocationTargetException) {
                throw ex.targetException
            }
        }
    }

    private fun submitCommandPayload(suffix: String): String {
        return """
            {
              "commandId":"submit-cmd-$suffix",
              "traceId":"trace-$suffix",
              "causationId":"cause-$suffix",
              "correlationId":"corr-$suffix",
              "actorId":"actor-$suffix",
              "occurredAt":"2026-07-04T18:01:00Z",
              "orderId":"submit-order-$suffix",
              "instrumentId":"AAPL",
              "participantId":"participant-$suffix",
              "accountId":"account-$suffix",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()
    }

    private fun venueEventBatch(suffix: String): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "batch-$suffix",
            shardId = "engine-0",
            partition = uniquePartition(suffix, 4),
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 1001,
            lastSequence = 1001,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = "checksum-$suffix",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "cmd-$suffix",
                    commandType = "CancelOrder",
                    streamSequence = 1001,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-$suffix",
                    instrumentId = "AAPL",
                    orderId = "ord-$suffix",
                    resultStatus = "rejected",
                    rejectCode = "ORDER_ALREADY_FILLED",
                    resultPayloadJson = """{"rejected":{"code":"ORDER_ALREADY_FILLED"}}"""
                )
            )
        )
    }

    private fun submitVenueEventBatch(suffix: String, sequence: Long = 1002): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "submit-batch-$suffix",
            shardId = "engine-0",
            partition = uniquePartition(suffix, 5),
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = sequence,
            lastSequence = sequence,
            commandCount = 1,
            createdAt = "2026-07-04T18:01:00Z",
            payloadChecksum = "submit-checksum-$suffix",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "submit-cmd-$suffix",
                    commandType = "SubmitOrder",
                    streamSequence = sequence,
                    deliveredCount = 1,
                    payloadHash = "submit-payload-hash-$suffix",
                    instrumentId = "AAPL",
                    orderId = "submit-order-$suffix",
                    resultStatus = "accepted",
                    resultPayloadJson = """{"accepted":{"eventId":"submit-event-$suffix","engineOrderId":"engine-order-$suffix","occurredAt":"2026-07-04T18:01:00Z"}}"""
                )
            )
        )
    }

    private fun uniqueSequence(suffix: String): Long =
        1_000_000L + (suffix.hashCode().toLong() and 0xffffffffL)

    private fun uniquePartition(suffix: String, lane: Int): Int =
        100 + ((suffix.hashCode().toLong() and 0x7fffffffL) % 1_000_000L).toInt() * 10 + lane
}
