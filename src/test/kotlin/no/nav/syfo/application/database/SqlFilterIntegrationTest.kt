package no.nav.syfo.application.database

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import java.time.Instant
import java.util.UUID

class SqlFilterIntegrationTest :
    DescribeSpec({
        val database = TestDB.database

        beforeTest {
            TestDB.clearAllData()
        }

        fun insertBehov(entity: NarmestelederBehovEntity): NarmestelederBehovEntity {
            database.connection.use { connection ->
                connection.prepareStatement(
                    """
                INSERT INTO nl_behov (
                    id, orgnummer, hovedenhet_orgnummer, sykemeldt_fnr, narmeste_leder_fnr,
                    behov_reason, behov_status, avbrutt_narmesteleder_id, created
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, entity.id)
                    ps.setString(2, entity.orgnummer)
                    ps.setString(3, entity.hovedenhetOrgnummer)
                    ps.setString(4, entity.sykmeldtFnr)
                    ps.setString(5, entity.narmestelederFnr)
                    ps.setObject(6, entity.behovReason, java.sql.Types.OTHER)
                    ps.setObject(7, entity.behovStatus, java.sql.Types.OTHER)
                    ps.setObject(8, entity.avbruttNarmesteLederId)
                    ps.setTimestamp(9, java.sql.Timestamp.from(entity.created))
                    ps.executeUpdate()
                }
                connection.commit()
            }
            return entity
        }

        describe("SqlBuilder integration with PostgreSQL") {
            context("filterParam with EQUALS operator") {
                it("filters by single column value") {
                    // Arrange
                    val targetOrg = "123456789"
                    val entity1 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), orgnummer = targetOrg))
                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), orgnummer = "987654321"))

                    // Act
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            filterParam(SqlBuilder.Column.ORGNUMMER, targetOrg)
                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert
                    results shouldHaveSize 1
                    results.first() shouldBe entity1.id
                }
            }

            context("filterParam with IN operator") {
                it("filters by list of enum values using = IN (?, ?, ...)") {
                    // Arrange
                    val entity1 = insertBehov(
                        nlBehovEntity().copy(id = UUID.randomUUID(), behovStatus = BehovStatus.BEHOV_CREATED)
                    )
                    val entity2 = insertBehov(
                        nlBehovEntity().copy(
                            id = UUID.randomUUID(),
                            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                        )
                    )
                    insertBehov(
                        nlBehovEntity().copy(id = UUID.randomUUID(), behovStatus = BehovStatus.BEHOV_FULFILLED)
                    )

                    val statusesToFind = listOf(
                        BehovStatus.BEHOV_CREATED,
                        BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                    )

                    // Act
                    var filterString = ""
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            filterParam(SqlBuilder.Column.BEHOV_STATUS, statusesToFind, SqlBuilder.ComparisonOperator.IN)
                            filterString = buildWhereClause()
                            connection.prepareStatement("SELECT id FROM nl_behov $filterString")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert
                    filterString shouldContain "IN (?, ?)"
                    results shouldHaveSize 2
                    results shouldContainExactlyInAnyOrder listOf(entity1.id, entity2.id)
                }

                it("filters by list of string values") {
                    // Arrange
                    val org1 = "111111111"
                    val org2 = "222222222"
                    val org3 = "333333333"

                    val entity1 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), orgnummer = org1))
                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), orgnummer = org2))
                    val entity3 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), orgnummer = org3))

                    // Act
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            filterParam(SqlBuilder.Column.ORGNUMMER, listOf(org1, org3), SqlBuilder.ComparisonOperator.IN)
                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert
                    results shouldHaveSize 2
                    results shouldContainExactlyInAnyOrder listOf(entity1.id, entity3.id)
                }
            }

            context("filterParam with LESS_THAN operator") {
                it("filters by timestamp less than value") {
                    // Arrange
                    val now = Instant.now()
                    val oldTime = now.minusSeconds(3600) // 1 hour ago
                    val futureTime = now.plusSeconds(3600)

                    val oldEntity = insertBehov(
                        nlBehovEntity().copy(id = UUID.randomUUID(), created = oldTime)
                    )
                    insertBehov(
                        nlBehovEntity().copy(id = UUID.randomUUID(), created = futureTime)
                    )

                    // Act
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            filterParam(SqlBuilder.Column.CREATED, now, SqlBuilder.ComparisonOperator.LESS_THAN)
                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert
                    results shouldHaveSize 1
                    results.first() shouldBe oldEntity.id
                }
            }

            context("combined filters") {
                it("combines multiple filter conditions with AND") {
                    // Arrange
                    val targetOrg = "999888777"
                    val targetStatus = BehovStatus.BEHOV_CREATED

                    val match = insertBehov(
                        nlBehovEntity().copy(
                            id = UUID.randomUUID(),
                            orgnummer = targetOrg,
                            behovStatus = targetStatus
                        )
                    )
                    insertBehov(
                        nlBehovEntity().copy(
                            id = UUID.randomUUID(),
                            orgnummer = "000000000",
                            behovStatus = targetStatus
                        )
                    )
                    insertBehov(
                        nlBehovEntity().copy(
                            id = UUID.randomUUID(),
                            orgnummer = targetOrg,
                            behovStatus = BehovStatus.BEHOV_FULFILLED
                        )
                    )

                    // Act
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            filterParam(SqlBuilder.Column.ORGNUMMER, targetOrg)
                            filterParam(SqlBuilder.Column.BEHOV_STATUS, targetStatus)
                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert
                    results shouldHaveSize 1
                    results.first() shouldBe match.id
                }
            }

            context("ORDER BY, LIMIT, and OFFSET") {
                it("orders results and applies pagination") {
                    // Arrange
                    val time1 = Instant.now().minusSeconds(300)
                    val time2 = Instant.now().minusSeconds(200)
                    val time3 = Instant.now().minusSeconds(100)

                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), created = time1))
                    val entity2 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), created = time2))
                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), created = time3))

                    // Act - get second page (offset 1, limit 1), ordered by created ASC
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            orderBy = SqlBuilder.Column.CREATED
                            orderDirection = SqlBuilder.OrderDirection.ASC
                            limit = 1
                            offset = 1

                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert - should get the second oldest (entity2)
                    results shouldHaveSize 1
                    results.first() shouldBe entity2.id
                }

                it("orders results DESC") {
                    // Arrange
                    val time1 = Instant.now().minusSeconds(300)
                    val time2 = Instant.now().minusSeconds(200)
                    val time3 = Instant.now().minusSeconds(100)

                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), created = time1))
                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), created = time2))
                    val entity3 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID(), created = time3))

                    // Act - get first result ordered by created DESC (newest first)
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            orderBy = SqlBuilder.Column.CREATED
                            orderDirection = SqlBuilder.OrderDirection.DESC
                            limit = 1

                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert - should get the newest (entity3)
                    results shouldHaveSize 1
                    results.first() shouldBe entity3.id
                }
            }

            context("empty filter") {
                it("returns all rows when no filters applied") {
                    // Arrange
                    val entity1 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID()))
                    val entity2 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID()))
                    val entity3 = insertBehov(nlBehovEntity().copy(id = UUID.randomUUID()))

                    // Act
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            // No filters
                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert
                    results shouldHaveSize 3
                    results shouldContainExactlyInAnyOrder listOf(entity1.id, entity2.id, entity3.id)
                }
            }

            context("null value handling") {
                it("ignores null values in filterParam") {
                    // Arrange
                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID()))
                    insertBehov(nlBehovEntity().copy(id = UUID.randomUUID()))

                    // Act - null value should be ignored, returning all results
                    val results = database.connection.use { connection ->
                        SqlBuilder.filterBuilder {
                            filterParam(SqlBuilder.Column.ORGNUMMER, null)
                            connection.prepareStatement("SELECT id FROM nl_behov ${buildWhereClause()}")
                        }.use { ps ->
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(rs.getObject("id", UUID::class.java))
                                    }
                                }
                            }
                        }
                    }

                    // Assert - should return all rows since null filter is ignored
                    results shouldHaveSize 2
                }
            }
        }
    })
