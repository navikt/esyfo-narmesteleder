package no.nav.syfo.narmesteleder.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PersonTableBatchInsertTest :
    DescribeSpec({
        beforeTest {
            TestDB.clearPersonData()
        }
        describe("PersonTable") {
            it("should not find issues with indexes in table") {
                transaction(TestDB.exposedDatabase) {
                    checkMappingConsistence(PersonTable, withLogs = true) shouldBe emptyList()
                }
            }
        }
        describe("PersonTable.batchInsertIgnoreExisting") {
            it("should insert new persons") {
                val rows = listOf(
                    PersonBatchInsertRow(
                        fnr = "12345678901",
                        status = "ACTIVE",
                        fornavn = "Ada",
                        mellomnavn = "Augusta",
                        etternavn = "Lovelace",
                    ),
                    PersonBatchInsertRow(
                        fnr = "10987654321",
                        status = "INACTIVE",
                    ),
                )

                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(rows)
                }

                val personsByFnr = fetchPersons().associateBy { it.fnr }
                val firstPerson = personsByFnr["12345678901"].shouldNotBeNull()
                val secondPerson = personsByFnr["10987654321"].shouldNotBeNull()

                insertedPersons shouldBe listOf(
                    InsertedPerson(
                        id = firstPerson.id,
                        fnr = "12345678901",
                        fornavn = "Ada",
                        mellomnavn = "Augusta",
                        etternavn = "Lovelace",
                        status = "ACTIVE",
                    ),
                    InsertedPerson(
                        id = secondPerson.id,
                        fnr = "10987654321",
                        fornavn = null,
                        mellomnavn = null,
                        etternavn = null,
                        status = "INACTIVE",
                    ),
                )
                personsByFnr.keys shouldBe setOf("12345678901", "10987654321")
                firstPerson shouldBe PersistedPerson(
                    id = firstPerson.id,
                    fnr = "12345678901",
                    fornavn = "Ada",
                    mellomnavn = "Augusta",
                    etternavn = "Lovelace",
                    status = "ACTIVE",
                    created = firstPerson.created,
                    updated = firstPerson.updated,
                )
                secondPerson shouldBe PersistedPerson(
                    id = secondPerson.id,
                    fnr = "10987654321",
                    fornavn = null,
                    mellomnavn = null,
                    etternavn = null,
                    status = "INACTIVE",
                    created = secondPerson.created,
                    updated = secondPerson.updated,
                )
            }

            it("should ignore fnr that already exists in database") {
                val initiallyInserted = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "ACTIVE",
                            ),
                        ),
                    )
                }

                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "INACTIVE",
                            ),
                        ),
                    )
                }

                insertedPersons shouldBe emptyList()
                val persistedPerson = fetchPersons().single()
                initiallyInserted shouldBe listOf(
                    InsertedPerson(
                        id = persistedPerson.id,
                        fnr = "12345678901",
                        fornavn = null,
                        mellomnavn = null,
                        etternavn = null,
                        status = "ACTIVE",
                    ),
                )
            }

            it("should return only newly inserted rows when batch contains both existing and new fnr values") {
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
                val existingPerson = fetchPerson("12345678901").shouldNotBeNull()

                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "INACTIVE",
                                fornavn = "Changed",
                            ),
                            PersonBatchInsertRow(
                                fnr = "10987654321",
                                status = "PENDING",
                            ),
                            PersonBatchInsertRow(
                                fnr = "11111111111",
                                status = "PENDING",
                                fornavn = "Grace",
                            ),
                        ),
                    )
                }

                val persistedByFnr = fetchPersons().associateBy { it.fnr }
                insertedPersons.map(InsertedPerson::fnr) shouldBe listOf("10987654321", "11111111111")
                insertedPersons.forEach { insertedPerson ->
                    insertedPerson.id shouldBe persistedByFnr.getValue(insertedPerson.fnr).id
                }
                fetchPerson("12345678901") shouldBe existingPerson
            }

            it("should ignore duplicate fnr values within the same batch") {
                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "ACTIVE",
                                fornavn = "Ada",
                                mellomnavn = "Augusta",
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
                val personsByFnr = persons.associateBy { it.fnr }
                val insertedPersonsByFnr = insertedPersons.associateBy { it.fnr }
                persons.size shouldBe 2
                persons.count { it.fnr == "12345678901" } shouldBe 1
                persons.count { it.fnr == "10987654321" } shouldBe 1
                insertedPersons shouldBe listOf(
                    InsertedPerson(
                        id = personsByFnr.getValue("12345678901").id,
                        fnr = "12345678901",
                        status = "ACTIVE",
                        fornavn = "Ada",
                        mellomnavn = "Augusta",
                        etternavn = null,
                    ),
                    InsertedPerson(
                        id = personsByFnr.getValue("10987654321").id,
                        fnr = "10987654321",
                        status = "PENDING",
                        fornavn = null,
                        mellomnavn = null,
                        etternavn = null,
                    ),
                )
                insertedPersonsByFnr.keys shouldBe personsByFnr.keys
                insertedPersons.forEach { insertedPerson ->
                    insertedPerson.id shouldBe personsByFnr.getValue(insertedPerson.fnr).id
                }
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
                                mellomnavn = "Augusta",
                                etternavn = "Lovelace",
                            ),
                        ),
                    )
                }

                val original = fetchPerson("12345678901").shouldNotBeNull()

                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "INACTIVE",
                                fornavn = "Grace",
                                mellomnavn = "Brewster",
                                etternavn = "Hopper",
                            ),
                        ),
                    )
                }

                insertedPersons shouldBe emptyList()
                fetchPerson("12345678901") shouldBe original
            }

            it("should handle empty input safely") {
                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(emptyList())
                }

                insertedPersons shouldBe emptyList()
                fetchPersons() shouldBe emptyList()
            }
        }
    })
