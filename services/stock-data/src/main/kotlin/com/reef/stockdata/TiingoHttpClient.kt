package com.reef.stockdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TiingoRateLimitedException(message: String) : Exception(message)
class TiingoTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)
class TiingoUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
class TiingoInvalidPayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class TiingoSymbolNotSupportedException(message: String) : Exception(message)
class TiingoNoHistoricalDataException(message: String) : Exception(message)

data class TiingoIexQuote(
    val ticker: String,
    val tngoLast: BigDecimal?,
    val last: BigDecimal?,
    val prevClose: BigDecimal?,
    val open: BigDecimal?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val volume: Long?,
    val quoteTimestamp: Instant?,
    val rawPayload: String,
)

data class TiingoEodRow(
    val date: LocalDate,
    val close: BigDecimal,
    val open: BigDecimal?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val volume: Long?,
    val rawPayload: String,
)

/** Thin transport boundary so provider logic is testable without real network calls. */
interface TiingoHttpClient {
    fun getIexCurrent(symbol: String): TiingoIexQuote
    fun getEodOnOrBefore(symbol: String, onOrBefore: LocalDate): TiingoEodRow
}

class JdkTiingoHttpClient(
    private val config: TiingoConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.providerTimeoutMs))
        .build(),
) : TiingoHttpClient {

    private val mapper = ObjectMapper()

    override fun getIexCurrent(symbol: String): TiingoIexQuote {
        val uri = URI.create("${config.baseUrl}/iex/${symbol}?token=${config.apiToken}")
        val body = sendWithRetries(uri, symbol)
        val root = parseJson(body)
        val node = (if (root.isArray) root.firstOrNull() else root)
            ?: throw TiingoInvalidPayloadException("empty IEX current payload for $symbol")

        return TiingoIexQuote(
            ticker = node.path("ticker").asText(symbol),
            tngoLast = node.decimalOrNull("tngoLast"),
            last = node.decimalOrNull("last"),
            prevClose = node.decimalOrNull("prevClose"),
            open = node.decimalOrNull("open"),
            high = node.decimalOrNull("high"),
            low = node.decimalOrNull("low"),
            volume = node.longOrNull("volume"),
            quoteTimestamp = node.instantOrNull("quoteTimestamp") ?: node.instantOrNull("timestamp"),
            rawPayload = body,
        )
    }

    override fun getEodOnOrBefore(symbol: String, onOrBefore: LocalDate): TiingoEodRow {
        val startDate = onOrBefore.minusDays(10)
        val uri = URI.create(
            "${config.baseUrl}/tiingo/daily/${symbol}/prices" +
                "?startDate=$startDate&endDate=$onOrBefore&token=${config.apiToken}",
        )
        val body = sendWithRetries(uri, symbol)
        val root = parseJson(body)
        if (!root.isArray || root.isEmpty) {
            throw TiingoNoHistoricalDataException("no EOD rows for $symbol on or before $onOrBefore")
        }
        val last = root.last()
        val date = LocalDate.parse(last.path("date").asText().substring(0, 10))
        return TiingoEodRow(
            date = date,
            close = last.decimalOrNull("close")
                ?: throw TiingoInvalidPayloadException("missing close in EOD row for $symbol"),
            open = last.decimalOrNull("open"),
            high = last.decimalOrNull("high"),
            low = last.decimalOrNull("low"),
            volume = last.longOrNull("volume"),
            rawPayload = body,
        )
    }

    private fun sendWithRetries(uri: URI, symbol: String): String {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= config.providerMaxRetries) {
            try {
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(config.providerTimeoutMs))
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                when (response.statusCode()) {
                    in 200..299 -> return response.body()
                    404 -> throw TiingoSymbolNotSupportedException("symbol not found: $symbol")
                    429 -> throw TiingoRateLimitedException("rate limited fetching $symbol")
                    in 500..599 -> throw TiingoUnavailableException(
                        "provider ${response.statusCode()} fetching $symbol",
                    )
                    else -> throw TiingoInvalidPayloadException(
                        "unexpected status ${response.statusCode()} fetching $symbol",
                    )
                }
            } catch (ex: TiingoSymbolNotSupportedException) {
                throw ex
            } catch (ex: HttpTimeoutException) {
                lastError = TiingoTimeoutException("timeout fetching $symbol", ex)
            } catch (ex: TiingoRateLimitedException) {
                lastError = ex
            } catch (ex: TiingoUnavailableException) {
                lastError = ex
            } catch (ex: TiingoInvalidPayloadException) {
                throw ex
            } catch (ex: Exception) {
                lastError = TiingoUnavailableException("transport error fetching $symbol", ex)
            }
            attempt += 1
            if (attempt <= config.providerMaxRetries) {
                sleepWithJitter(attempt)
            }
        }
        throw lastError ?: TiingoUnavailableException("exhausted retries fetching $symbol")
    }

    private fun sleepWithJitter(attempt: Int) {
        val baseMs = 100L * attempt
        val jitterMs = (0 until 50).random()
        Thread.sleep(baseMs + jitterMs)
    }

    private fun parseJson(body: String): JsonNode =
        try {
            mapper.readTree(body)
        } catch (ex: Exception) {
            throw TiingoInvalidPayloadException("could not parse provider payload", ex)
        }

    private fun JsonNode.decimalOrNull(field: String): BigDecimal? =
        path(field).takeIf { it.isNumber }?.let { BigDecimal(it.asText()) }

    private fun JsonNode.longOrNull(field: String): Long? =
        path(field).takeIf { it.isNumber }?.asLong()

    private fun JsonNode.instantOrNull(field: String): Instant? =
        path(field).takeIf { it.isTextual }?.let {
            try {
                Instant.parse(it.asText())
            } catch (ex: Exception) {
                null
            }
        }
}
