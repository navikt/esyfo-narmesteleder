package no.nav.syfo.application.database

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

class SqlFilterTest :
    DescribeSpec({
        describe("SqlFilter") {
            describe("build") {
                it("generates IN (?, ?) for list values and binds each item individually") {
                    val connection = mockk<Connection>()
                    val preparedStatement = mockk<PreparedStatement>(relaxed = true)

                    val statuses = listOf(TestStatus.A, TestStatus.B)
                    val id = UUID.randomUUID()

                    val capturedSql = slot<String>()

                    every { preparedStatement.connection } returns connection
                    every { connection.prepareStatement(capture(capturedSql)) } returns preparedStatement

                    SqlBuilder.filterBuilder {
                        filterParam(SqlBuilder.Column.ID, id)
                        filterParam(SqlBuilder.Column.BEHOV_STATUS, statuses, SqlBuilder.ComparisonOperator.IN)
                        filterParam(SqlBuilder.Column.BEHOV_REASON, TestStatus.A)

                        orderBy = SqlBuilder.Column.UPDATED
                        orderDirection = SqlBuilder.OrderDirection.DESC
                        limit = 10
                        offset = 20

                        connection.prepareStatement("SELECT * FROM t ${buildWhereClause()}")
                    }

                    capturedSql.isCaptured shouldBe true
                    val sql = capturedSql.captured
                    sql.contains("WHERE") shouldBe true
                    sql.contains("id = ?") shouldBe true
                    sql.contains("behov_status IN (?, ?)") shouldBe true
                    sql.contains("behov_reason = ?") shouldBe true
                    sql.contains("ORDER BY updated DESC") shouldBe true
                    sql.contains("LIMIT 10") shouldBe true
                    sql.contains("OFFSET 20") shouldBe true

                    // Verify parameter binding order: id (1), status A (2), status B (3), reason (4)
                    verify(exactly = 1) { preparedStatement.setObject(1, id) }
                    verify(exactly = 1) { preparedStatement.setObject(2, TestStatus.A, Types.OTHER) }
                    verify(exactly = 1) { preparedStatement.setObject(3, TestStatus.B, Types.OTHER) }
                    verify(exactly = 1) { preparedStatement.setObject(4, TestStatus.A, Types.OTHER) }
                }

                it("uses Column enum to prevent SQL injection - only predefined columns allowed") {
                    // This test documents that filterParam only accepts Column enum values,
                    // not arbitrary strings. The compiler enforces this at compile time.
                    // If someone tries: filterParam("malicious; DROP TABLE", value)
                    // it will not compile because String is not Column.

                    val connection = mockk<Connection>()
                    val preparedStatement = mockk<PreparedStatement>(relaxed = true)
                    val capturedSql = slot<String>()

                    every { preparedStatement.connection } returns connection
                    every { connection.prepareStatement(capture(capturedSql)) } returns preparedStatement

                    SqlBuilder.filterBuilder {
                        filterParam(SqlBuilder.Column.CREATED, "safe_value")
                        connection.prepareStatement("SELECT * FROM t ${buildWhereClause()}")
                    }

                    // The generated SQL only contains the predefined column name from the enum
                    capturedSql.captured.contains("created = ?") shouldBe true
                    // No way to inject arbitrary SQL via filterParam - compiler won't allow it
                }
            }
        }
    })

private enum class TestStatus {
    A,
    B,
}
