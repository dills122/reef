package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.math.BigDecimal
import javax.sql.DataSource

class PostgresInstrumentPriceCollarStore(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val cacheTtlMillis: Long = 1_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : InstrumentPriceCollarStore {
    private data class CachedState(
        val state: InstrumentPriceCollarState?,
        val expiresAtMillis: Long
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedState>()

    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryInstrumentPriceCollars(names.instrumentPriceCollars)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.instrumentPriceCollars} (
                      instrument_id TEXT NOT NULL PRIMARY KEY,
                      min_price TEXT NOT NULL DEFAULT '',
                      max_price TEXT NOT NULL DEFAULT '',
                      currency TEXT NOT NULL DEFAULT '',
                      reason TEXT NOT NULL DEFAULT '',
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError? {
        if (request.commandType != "SubmitOrder") return null
        if (request.instrumentId.isBlank()) return null
        val collar = stateFor(request.instrumentId) ?: return null
        val expectedCurrency = collar.currency.trim()
        if (expectedCurrency.isNotBlank() && !request.currency.equals(expectedCurrency, ignoreCase = true)) {
            return null
        }
        val price = request.limitPrice.toBigDecimalOrNull() ?: return null
        val minPrice = collar.minPrice.toBigDecimalOrNull()
        if (minPrice != null && price < minPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_LOW",
                "limit price below collar for ${collar.instrumentId}: ${price.toPlainString()} < ${minPrice.toPlainString()}"
            )
        }
        val maxPrice = collar.maxPrice.toBigDecimalOrNull()
        if (maxPrice != null && price > maxPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_HIGH",
                "limit price above collar for ${collar.instrumentId}: ${price.toPlainString()} > ${maxPrice.toPlainString()}"
            )
        }
        return null
    }

    override fun setCollar(instrumentId: String, minPrice: String, maxPrice: String, currency: String, reason: String) {
        require(instrumentId.isNotBlank()) { "instrumentId is required" }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.instrumentPriceCollars}(instrument_id, min_price, max_price, currency, reason, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (instrument_id)
                DO UPDATE SET min_price = EXCLUDED.min_price,
                              max_price = EXCLUDED.max_price,
                              currency = EXCLUDED.currency,
                              reason = EXCLUDED.reason,
                              updated_at = NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.setString(2, minPrice)
                ps.setString(3, maxPrice)
                ps.setString(4, currency)
                ps.setString(5, reason)
                ps.executeUpdate()
            }
        }
        cache.remove(instrumentId)
    }

    override fun listCollars(): List<InstrumentPriceCollarState> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT instrument_id, min_price, max_price, currency, reason, updated_at
                FROM ${names.instrumentPriceCollars}
                ORDER BY instrument_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<InstrumentPriceCollarState>()
                    while (rs.next()) {
                        rows.add(
                            InstrumentPriceCollarState(
                                instrumentId = rs.getString("instrument_id"),
                                minPrice = rs.getString("min_price").orEmpty(),
                                maxPrice = rs.getString("max_price").orEmpty(),
                                currency = rs.getString("currency").orEmpty(),
                                reason = rs.getString("reason").orEmpty(),
                                updatedAt = rs.getString("updated_at").orEmpty()
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    private fun stateFor(instrumentId: String): InstrumentPriceCollarState? {
        val now = nowMillis()
        cache[instrumentId]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.state
        }
        val loaded = loadState(instrumentId)
        if (cacheTtlMillis > 0) {
            cache[instrumentId] = CachedState(loaded, now + cacheTtlMillis)
        }
        return loaded
    }

    private fun loadState(instrumentId: String): InstrumentPriceCollarState? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT instrument_id, min_price, max_price, currency, reason, updated_at
                FROM ${names.instrumentPriceCollars}
                WHERE instrument_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return InstrumentPriceCollarState(
                        instrumentId = rs.getString("instrument_id"),
                        minPrice = rs.getString("min_price").orEmpty(),
                        maxPrice = rs.getString("max_price").orEmpty(),
                        currency = rs.getString("currency").orEmpty(),
                        reason = rs.getString("reason").orEmpty(),
                        updatedAt = rs.getString("updated_at").orEmpty()
                    )
                }
            }
        }
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? {
        val trimmed = trim()
        if (trimmed.isBlank()) return null
        return try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun connection() = dataSource.connection
}
