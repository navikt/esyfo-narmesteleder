package no.nav.syfo.narmesteleder.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

class PersonTableBatchInsertTest :
    DescribeSpec({
        beforeTest {
            TestDB.clearPersonData()
        }

        describe("PersonTable.batchInsertIgnoreExisting") {
            it("should insert new persons") {
                val rows = listOf(
                    PersonBatchInsertRow(
                        fnr = "12345678901",
                        status = "ACTIVE",
                        fornavn = "Ada",
                        etternavn = "Lovelace",
                    ),
                    PersonBatchInsertRow(
                        fnr = "10987654321",
                        status = "INACTIVE",
                    ),
                )

                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(rows)
                }

                val personsByFnr = fetchPersons().associateBy { it.fnr }
                val firstPerson = personsByFnr["12345678901"].shouldNotBeNull()
                val secondPerson = personsByFnr["10987654321"].shouldNotBeNull()

                personsByFnr.keys shouldBe setOf("12345678901", "10987654321")
                firstPerson shouldBe PersistedPerson(
                    fnr = "12345678901",
                    fornavn = "Ada",
                    etternavn = "Lovelace",
                    status = "ACTIVE",
                    created = firstPerson.created,
                    updated = firstPerson.updated,
                )
                secondPerson shouldBe PersistedPerson(
                    fnr = "10987654321",
                    fornavn = null,
                    etternavn = null,
                    status = "INACTIVE",
                    created = secondPerson.created,
                    updated = secondPerson.updated,
                )
            }

            it("should ignore fnr that already exists in database") {
                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "ACTIVE",
                            ),
                        ),
                    )
                }

                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "INACTIVE",
                            ),
                        ),
                    )
                }

                val persistedPerson = fetchPersons().single()

                listOf(persistedPerson) shouldBe listOf(
                    PersistedPerson(
                        fnr = "12345678901",
                        fornavn = null,
                        etternavn = null,
                        status = "ACTIVE",
                        created = persistedPerson.created,
                        updated = persistedPerson.updated,
                    ),
                )
            }

            it("should ignore duplicate fnr values within the same batch") {
                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "ACTIVE",
                                fornavn = "Ada",
                            ),
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "INACTIVE",
                                fornavn = "Grace",
                            ),
                            PersonBatchInsertRow(
                                fnr = "10987654321",
                                status = "PENDING",
                            ),
                        ),
                    )
                }

                val persons = fetchPersons()
                persons.size shouldBe 2
                persons.count { it.fnr == "12345678901" } shouldBe 1
                persons.count { it.fnr == "10987654321" } shouldBe 1
                fetchPerson("12345678901")?.status shouldBe "ACTIVE"
            }

            it("should not overwrite existing row when conflict is ignored") {
                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "ACTIVE",
                                fornavn = "Ada",
                                etternavn = "Lovelace",
                            ),
                        ),
                    )
                }

                val original = fetchPerson("12345678901").shouldNotBeNull()

                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "INACTIVE",
                                fornavn = "Grace",
                                etternavn = "Hopper",
                            ),
                        ),
                    )
                }

                fetchPerson("12345678901") shouldBe original
            }

            it("should handle empty input safely") {
                transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(emptyList())
                }

                fetchPersons() shouldBe emptyList()
            }
        }
    })

private data class PersistedPerson(
    val fnr: String,
    val fornavn: String?,
    val etternavn: String?,
    val status: String,
    val created: Instant,
    val updated: Instant,
)

private fun fetchPersons(): List<PersistedPerson> = TestDB.database.connection.use { connection ->
    connection.prepareStatement(
        """
        SELECT fnr, fornavn, etternavn, status, created, updated
        FROM person
        ORDER BY fnr
        """.trimIndent(),
    ).use { preparedStatement ->
        preparedStatement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        PersistedPerson(
                            fnr = resultSet.getString("fnr"),
                            fornavn = resultSet.getString("fornavn"),
                            etternavn = resultSet.getString("etternavn"),
                            status = resultSet.getString("status"),
                            created = resultSet.getTimestamp("created").toInstant(),
                            updated = resultSet.getTimestamp("updated").toInstant(),
                        ),
                    )
                }
            }
        }
    }
}

private fun fetchPerson(fnr: String): PersistedPerson? = TestDB.database.connection.use { connection ->
    connection.prepareStatement(
        """
        SELECT fnr, fornavn, etternavn, status, created, updated
        FROM person
        WHERE fnr = ?
        """.trimIndent(),
    ).use { preparedStatement ->
        preparedStatement.setString(1, fnr)
        preparedStatement.executeQuery().use { resultSet ->
            if (resultSet.next()) {
                PersistedPerson(
                    fnr = resultSet.getString("fnr"),
                    fornavn = resultSet.getString("fornavn"),
                    etternavn = resultSet.getString("etternavn"),
                    status = resultSet.getString("status"),
                    created = resultSet.getTimestamp("created").toInstant(),
                    updated = resultSet.getTimestamp("updated").toInstant(),
                )
            } else {
                null
            }
        }
    }
}
