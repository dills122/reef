package com.reef.stockdata

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JdkTiingoHttpClientTest {
    @Test
    fun retriesTransientProviderFailureAndReturnsRecoveredQuote() {
        val requests = AtomicInteger()
        val server = providerServer { exchange ->
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 503, "")
            } else {
                respond(exchange, 200, """[{"ticker":"AAPL","tngoLast":202.50}]""")
            }
        }
        try {
            val quote = client(server, providerMaxRetries = 1).getIexCurrent("AAPL")

            assertEquals(2, requests.get())
            assertEquals(BigDecimal("202.5"), quote.tngoLast)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesCurrentAndHistoricalProviderPayloads() {
        val server = providerServer { exchange ->
            when {
                exchange.requestURI.path.startsWith("/iex/") -> respond(
                    exchange,
                    200,
                    """[{"ticker":"AAPL","tngoLast":201.25,"last":201.20,"prevClose":199.50,"open":200.00,"high":202.00,"low":198.75,"volume":123456,"quoteTimestamp":"2026-07-08T15:00:00Z"}]"""
                )
                exchange.requestURI.path.startsWith("/tiingo/daily/") -> respond(
                    exchange,
                    200,
                    """[{"date":"2026-07-07T00:00:00Z","close":198.50},{"date":"2026-07-08T00:00:00Z","close":201.00,"open":200.00,"high":202.00,"low":199.00,"volume":654321}]"""
                )
                else -> respond(exchange, 404, "")
            }
        }
        try {
            val client = client(server)

            val current = client.getIexCurrent("AAPL")
            val historical = client.getEodOnOrBefore("AAPL", LocalDate.parse("2026-07-08"))

            assertEquals("AAPL", current.ticker)
            assertEquals(BigDecimal("201.25"), current.tngoLast)
            assertEquals(123456L, current.volume)
            assertEquals("2026-07-08T15:00:00Z", current.quoteTimestamp.toString())
            assertEquals(LocalDate.parse("2026-07-08"), historical.date)
            assertEquals(BigDecimal("201.0"), historical.close)
            assertEquals(BigDecimal("200.0"), historical.open)
            assertEquals(654321L, historical.volume)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun mapsProviderStatusesAndMalformedPayloadsToTypedFailures() {
        val server = providerServer { exchange ->
            val path = exchange.requestURI.path
            val symbol = if (path.startsWith("/tiingo/daily/")) {
                path.substringAfter("/tiingo/daily/").substringBefore('/')
            } else {
                path.substringAfterLast('/')
            }
            when (symbol) {
                "MISSING" -> respond(exchange, 404, "")
                "RATE" -> respond(exchange, 429, "")
                "DOWN" -> respond(exchange, 503, "")
                "BAD" -> respond(exchange, 418, "")
                "INVALID" -> respond(exchange, 200, "{")
                "EMPTY" -> respond(exchange, 200, "[]")
                "NOCLOSE" -> respond(exchange, 200, """[{"date":"2026-07-08T00:00:00Z"}]""")
                else -> respond(exchange, 200, "{}")
            }
        }
        try {
            val client = client(server)

            assertFailsWith<TiingoSymbolNotSupportedException> { client.getIexCurrent("MISSING") }
            assertFailsWith<TiingoRateLimitedException> { client.getIexCurrent("RATE") }
            assertFailsWith<TiingoUnavailableException> { client.getIexCurrent("DOWN") }
            assertFailsWith<TiingoInvalidPayloadException> { client.getIexCurrent("BAD") }
            assertFailsWith<TiingoInvalidPayloadException> { client.getIexCurrent("INVALID") }
            assertFailsWith<TiingoNoHistoricalDataException> {
                client.getEodOnOrBefore("EMPTY", LocalDate.parse("2026-07-08"))
            }
            assertFailsWith<TiingoInvalidPayloadException> {
                client.getEodOnOrBefore("NOCLOSE", LocalDate.parse("2026-07-08"))
            }

            val sparse = client.getIexCurrent("SPARSE")
            assertEquals("SPARSE", sparse.ticker)
            assertNull(sparse.tngoLast)
            assertNull(sparse.quoteTimestamp)
        } finally {
            server.stop(0)
        }
    }

    private fun client(server: HttpServer, providerMaxRetries: Int = 0): JdkTiingoHttpClient = JdkTiingoHttpClient(
        TiingoConfig(
            apiToken = "test token",
            baseUrl = "http://127.0.0.1:${server.address.port}",
            providerTimeoutMs = 2_000,
            providerMaxRetries = providerMaxRetries
        )
    )

    private fun providerServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
