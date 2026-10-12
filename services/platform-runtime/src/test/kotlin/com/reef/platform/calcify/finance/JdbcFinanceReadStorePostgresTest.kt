package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource

/**
 * S3-A1 on a real Postgres: the read store returns exactly what the projector wrote, account filters restrict rows, and the read role
 * has SELECT only. Opt-in like the projector suite: absent the fixture the tests are reported skipped, and CI requires them executed.
 */
class JdbcFinanceReadStorePostgresTest {
    private val buyer = AccountId("buyer-account"); private val seller = AccountId("seller-account"); private val stranger = AccountId("stranger-account")
    private val usd = AssetId("USD"); private val aapl = AssetId("fixture-base-AAPL")
    private val ledger = FinanceLedger(FinanceConfig(setOf(buyer, seller, stranger), usd, aapl, AccountId("opening")))

    private fun dataSource(role: String): PGSimpleDataSource {
        val prefix = "CALCIFY_FINITE_POSTGRES_TEST_"
        val url = System.getenv(prefix + "JDBC_URL")
        assumeTrue(!url.isNullOrBlank(), "isolated finite Postgres test fixture not provisioned")
        val user = System.getenv(prefix + role + "_USER") ?: error("$role test user missing")
        val file = Path.of(System.getenv(prefix + role + "_PASSWORD_FILE") ?: error("private $role password file missing"))
        require(Files.getPosixFilePermissions(file).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "private test secret permissions" }
        return PGSimpleDataSource().apply { setUrl(url); this.user = user; password = Files.readString(file).trimEnd('\n', '\r') }
    }
    private fun digest() = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)
    private fun key(id: String) = ExecutionKey("p3-run", "p3-session", "AAPL", id)

    /** A small scripted run: buyer/seller trade e1 (settled), stranger/seller trade e2 (pending, one failed attempt). */
    private fun script(): List<FinanceInput> = listOf(
        FinanceInput.Fund(ActionKey("t", "f", "seller-shares"), Authority.OPERATOR, seller, aapl, 10),
        FinanceInput.Fund(ActionKey("t", "f", "buyer-cash"), Authority.OPERATOR, buyer, usd, 1_000),
        FinanceInput.Capture(ActionKey("t", "c", "e1"), key("e1"), buyer, seller, 3, 100),
        FinanceInput.Capture(ActionKey("t", "c", "e2"), key("e2"), stranger, seller, 2, 100),
        FinanceInput.Settle(ActionKey("t", "r", "e1-1"), Authority.OPERATOR, key("e1"), 1),
        FinanceInput.Settle(ActionKey("t", "r", "e2-1"), Authority.OPERATOR, key("e2"), 1),
    )

    private fun seed(binding: String): FinanceState {
        val projector = FinanceProjector(ledger, dataSource("PROJECTOR")); projector.open(binding, 1)
        var s = ledger.genesis(); val records = ArrayList<Pair<Decision, FinanceInput>>()
        for (i in script()) ledger.decide(s, i).also { s = it.state; it.decision?.let { d -> records += d to i } }
        projector.apply(binding, 1, records)
        return s
    }

    @Test fun readStoreReturnsExactlyTheProjectedRowsAndFiltersByAccount() {
        val binding = digest(); val state = seed(binding); val store = JdbcFinanceReadStore(dataSource("READER"))
        assertEquals(FinanceCheckpointRow(binding, 1, state.sequence, state.chain), store.checkpoint(binding))
        assertEquals(null, store.checkpoint(digest()))
        val decisions = store.decisions(binding, 0, 320)
        assertEquals((1L..state.sequence).toList(), decisions.map { it.sequence })
        assertEquals(listOf(4L, 5L), store.decisions(binding, 3, 2).map { it.sequence }, "afterSequence/limit paging")
        assertEquals(state.positions.filterValues { it != 0L }.size, store.positions(binding, null).size)
        assertEquals(setOf("buyer-account"), store.positions(binding, setOf("buyer-account")).map { it.accountId }.toSet())
        assertEquals(listOf("e1", "e2"), store.obligations(binding, null).map { it.executionId })
        assertEquals(listOf("e1"), store.obligations(binding, setOf("buyer-account")).map { it.executionId })
        assertEquals(listOf("e2"), store.obligations(binding, setOf("stranger-account")).map { it.executionId })
        assertEquals(listOf("e1", "e2"), store.obligations(binding, setOf("seller-account")).map { it.executionId }, "the counterparty sees both trades")
        assertEquals(listOf("e1", "e2"), store.attempts(binding, null).map { it.executionId })
        assertEquals(listOf("e2"), store.attempts(binding, setOf("stranger-account")).map { it.executionId })
        assertEquals(emptyList(), store.positions(binding, emptySet())); assertEquals(emptyList(), store.obligations(binding, emptySet())); assertEquals(emptyList(), store.attempts(binding, emptySet()))
        assertEquals(emptyList(), store.obligations(digest(), null), "another binding's rows are not visible")
    }

    @Test fun theReadRoleHasSelectOnlyOnTheProjection() {
        val binding = digest(); seed(binding)
        dataSource("READER").connection.use { c ->
            for (sql in listOf(
                "INSERT INTO boundary.calcify_finance_position(binding_digest, account_id, asset_id, amount) VALUES ('$binding', 'x', 'y', 1)",
                "UPDATE boundary.calcify_finance_checkpoint SET last_sequence = 0",
                "DELETE FROM boundary.calcify_finance_decision",
                "TRUNCATE boundary.calcify_finance_obligation",
            )) {
                val e = assertFailsWith<SQLException>(sql) { c.createStatement().use { it.execute(sql) } }
                assertTrue(e.message.orEmpty().contains("permission denied"), "unexpected failure for $sql: ${e.message}")
            }
            // And it cannot see the ingress or registrar tables at all.
            assertFailsWith<SQLException> { c.createStatement().use { it.executeQuery("SELECT 1 FROM boundary.calcify_finite_bindings").close() } }
        }
    }
}
