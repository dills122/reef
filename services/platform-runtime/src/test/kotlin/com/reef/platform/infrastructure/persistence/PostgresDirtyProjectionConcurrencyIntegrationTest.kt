package com.reef.platform.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresDirtyProjectionConcurrencyIntegrationTest {
    @Test
    fun lifecycleRedirtyCannotBeLostBetweenRecomputeAndMarkerDeletion() = race(market = false, beforeClaim = false)

    @Test
    fun marketRedirtyCannotBeLostBetweenRecomputeAndMarkerDeletion() = race(market = true, beforeClaim = false)

    @Test
    fun lifecycleClaimOfConcurrentlyUpdatedMarkerUsesFreshBusinessSnapshot() = race(market = false, beforeClaim = true)

    @Test
    fun marketClaimOfConcurrentlyUpdatedMarkerUsesFreshBusinessSnapshot() = race(market = true, beforeClaim = true)

    private fun race(market: Boolean, beforeClaim: Boolean) {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "dirty-race-${UUID.randomUUID()}")
        val schema = "dirty_race_${UUID.randomUUID().toString().replace("-", "")}"
        val names = PostgresRuntimeSqlNames(runtimeSchema = schema)
        val executor = Executors.newFixedThreadPool(2)
        val key = UUID.randomUUID().mostSignificantBits
        try {
            val persistence = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            source.connection.use { observer ->
                observer.exec("CREATE TABLE ${names.orderLifecycleDirty}(order_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                observer.exec("CREATE TABLE ${names.marketDataSnapshotDirty}(instrument_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                val functionNames = listOf("runtime_reject_execution_replay_conflict", "runtime_persist_submit_outcome_status_stage", "runtime_project_order_lifecycle_state", "runtime_project_market_data_snapshots")
                functionNames.forEach { name -> observer.exec(latestFunction(name).replace("runtime.", "$schema.")) }
                val planMigration = Path.of(System.getenv("REEF_DIRTY_PROJECTION_MIGRATION_DIR_TEST") ?: "../../scripts/dev/db/migrations/runtime")
                    .resolve("0056_lifecycle_parameter_sensitive_plans.sql")
                if (Files.exists(planMigration)) {
                    observer.exec(Files.readString(planMigration).replace("runtime.", "$schema."))
                    assertEquals(1L, observer.scalar("SELECT count(*) FROM pg_proc WHERE oid='$schema.runtime_project_order_lifecycle_state(integer)'::regprocedure AND 'plan_cache_mode=force_custom_plan'=ANY(proconfig)"))
                }
                observer.exec("SET plan_cache_mode='force_generic_plan'")
                observer.exec("INSERT INTO ${names.orders}(order_id,engine_order_id,instrument_id,participant_id,account_id,side,order_type,quantity_units,limit_price,currency,time_in_force,accepted_at) VALUES ('order','engine','instrument','participant','account','BUY','LIMIT','10','12','USD','GTC','2026-09-24T00:00:00Z')")
                observer.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) VALUES ('order')")
                assertEquals(1L, observer.scalar("SELECT $schema.runtime_project_order_lifecycle_state(500)"))
                assertEquals(1L, observer.scalar("SELECT (current_setting('plan_cache_mode')='force_generic_plan')::integer"), "function-scoped planning must restore caller setting")
                if (!market) observer.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) VALUES ('order')")
                val dirty = if (market) names.marketDataSnapshotDirty else names.orderLifecycleDirty
                val function = if (market) "runtime_project_market_data_snapshots" else "runtime_project_order_lifecycle_state"
                val projectSql = if (market) "SELECT $schema.$function('market','source',0,0,500)" else "SELECT $schema.$function(500)"
                if (beforeClaim) {
                    observer.exec("""
                        CREATE FUNCTION $schema.claim_barrier() RETURNS INTEGER LANGUAGE plpgsql VOLATILE AS ${'$'}${'$'}
                        BEGIN
                          PERFORM pg_advisory_lock($key);
                          PERFORM pg_advisory_unlock($key);
                          RETURN 0;
                        END;
                        ${'$'}${'$'}
                    """.trimIndent())
                    val original = latestFunction(function)
                    assertEquals(1, Regex("LIMIT effective_batch_size").findAll(original).count(), "instrument exactly the bounded claim, not recompute SQL")
                    // OFFSET 0 changes no rows. Its expression is evaluated before claim row locks,
                    // after this SQL statement's MVCC snapshot has been established.
                    observer.exec(original.replace("LIMIT effective_batch_size", "LIMIT effective_batch_size OFFSET $schema.claim_barrier()").replace("runtime.", "$schema."))
                } else {
                    observer.exec("""
                        CREATE FUNCTION $schema.delete_barrier() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                        BEGIN
                          PERFORM pg_advisory_lock($key);
                          PERFORM pg_advisory_unlock($key);
                          RETURN OLD;
                        END;
                        ${'$'}${'$'}
                    """.trimIndent())
                    observer.exec("CREATE TRIGGER pause_delete BEFORE DELETE ON $dirty FOR EACH ROW EXECUTE FUNCTION $schema.delete_barrier()")
                }
                source.connection.use { blocker ->
                    blocker.exec("SELECT pg_advisory_lock($key)")
                    try {
                        source.connection.use { consumer ->
                            source.connection.use { producer ->
                                consumer.exec("SET statement_timeout = '15s'")
                                producer.exec("SET statement_timeout = '15s'")
                                val consumerPid = consumer.scalar("SELECT pg_backend_pid()")
                                val producerPid = producer.scalar("SELECT pg_backend_pid()")
                                val draining = executor.submit<Long> { consumer.scalar(projectSql) }
                                waitUntil("consumer advisory barrier") {
                                    observer.scalar("SELECT count(*) FROM pg_locks WHERE pid = $consumerPid AND locktype = 'advisory' AND NOT granted") == 1L
                                }
                                val producing = executor.submit<Long> {
                                    producer.autoCommit = false
                                    try {
                                        val count = producer.scalar("SELECT $schema.runtime_persist_submit_outcome_status_stage(${payload()}::jsonb)")
                                        if (market) producer.scalar("SELECT $schema.runtime_project_order_lifecycle_state(500)")
                                        producer.commit()
                                        count
                                    } catch (ex: Exception) {
                                        producer.rollback()
                                        throw ex
                                    } finally { producer.autoCommit = true }
                                }
                                var producerWaited = false
                                if (beforeClaim) {
                                    // Producer must commit while the consumer's old claim snapshot is paused.
                                    assertEquals(1L, producing.get(10, TimeUnit.SECONDS))
                                } else {
                                    waitUntil("producer committed or blocked by consumer", producing) {
                                        producerWaited = observer.scalar("SELECT count(*) FROM unnest(pg_blocking_pids($producerPid::integer)) blocker WHERE blocker = $consumerPid") > 0
                                        producerWaited
                                    }
                                }
                                blocker.exec("SELECT pg_advisory_unlock($key)")
                                assertEquals(1L, draining.get(10, TimeUnit.SECONDS))
                                assertEquals(1L, producing.get(10, TimeUnit.SECONDS))
                                if (!beforeClaim) {
                                    assertEquals(1L, observer.scalar("SELECT count(*) FROM $dirty"), "committed concurrent update must retain a marker after old consumer commit")
                                    assertTrue(producerWaited, "producer must serialize with selected dirty row")
                                }
                                observer.scalar(projectSql)
                                assertEquals(0L, observer.scalar("SELECT count(*) FROM $dirty"))
                                val table = if (market) names.marketDataSnapshots else names.orderLifecycleState
                                val incremental = observer.rows(table)
                                persistence.rebuildOrderLifecycleState()
                                if (market) persistence.refreshMarketDataSnapshots("market", "source")
                                assertEquals(observer.rows(table), incremental, "empty queue must mean complete business equality; exclude only updated_at")
                            }
                        }
                    } finally { blocker.exec("SELECT pg_advisory_unlock_all()") }
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(20, TimeUnit.SECONDS)
            source.connection.use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            (source as? AutoCloseable)?.close()
        }
    }

    private fun payload() = """'[{"commandId":"command","resultType":"ACCEPTED","eventId":"event","orderId":"order","engineOrderId":"engine","code":"","reason":"","occurredAt":"2026-09-24T00:01:00Z","executions":[{"eventId":"fill","executionId":"fill","orderId":"order","instrumentId":"instrument","quantityUnits":"2","executionPrice":"12","currency":"USD","occurredAt":"2026-09-24T00:01:00Z"}],"trades":[]}]'"""

    private fun latestFunction(name: String): String {
        val dir = Path.of(System.getenv("REEF_DIRTY_PROJECTION_MIGRATION_DIR_TEST") ?: "../../scripts/dev/db/migrations/runtime")
        val prefix = "CREATE OR REPLACE FUNCTION runtime.$name("
        val sql = Files.list(dir).use { paths -> paths.filter { it.toString().endsWith(".sql") }.sorted().toList().map(Files::readString).last { prefix in it } }
        val start = sql.indexOf(prefix)
        return sql.substring(start, sql.indexOf("$$;", start) + 3)
    }

    private fun waitUntil(description: String, future: Future<*>? = null, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition() || future?.isDone == true) return
            Thread.yield()
        }
        error("timed out waiting for $description")
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getLong(1) }
    }
    private fun Connection.rows(table: String): List<String> = createStatement().use { statement ->
        statement.executeQuery("SELECT (to_jsonb(row) - 'updated_at')::TEXT FROM $table row ORDER BY 1").use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }
}
