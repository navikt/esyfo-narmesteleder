package no.nav.syfo.narmesteleder.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate

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
        describe("PersonTable batchInsertIgnoreExisting") {
            it("should insert new persons") {
                val rows = listOf(
                    PersonBatchInsertRow(
                        fnr = "12345678901",
                        status = "ACTIVE",
                        fornavn = "Ada",
                        mellomnavn = "Augusta",
                        etternavn = "Lovelace",
                        foedselsdato = LocalDate.now(),
                    ),
                    PersonBatchInsertRow(
                        fnr = "10987654321",
                        status = "INACTIVE",
                    ),
                )

                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(rows)
                }

                transaction(TestDB.exposedDatabase) {
                    val personsByFnr = PersonEntity.all()
                        .orderBy(PersonTable.fnr to SortOrder.ASC)
                        .associateBy { it.fnr }
                    val firstPerson = personsByFnr["12345678901"].shouldNotBeNull()
                    val secondPerson = personsByFnr["10987654321"].shouldNotBeNull()

                    insertedPersons shouldBe listOf(
                        InsertedPerson(
                            id = firstPerson.id.value,
                            fnr = "12345678901",
                            fornavn = "Ada",
                            mellomnavn = "Augusta",
                            etternavn = "Lovelace",
                            status = "ACTIVE",
                            foedselsdato = LocalDate.now(),
                        ),
                        InsertedPerson(
                            id = secondPerson.id.value,
                            fnr = "10987654321",
                            fornavn = null,
                            mellomnavn = null,
                            etternavn = null,
                            status = "INACTIVE",
                            foedselsdato = null
                        ),
                    )
                    personsByFnr.keys shouldBe setOf("12345678901", "10987654321")
                    firstPerson.fnr shouldBe "12345678901"
                    firstPerson.fornavn shouldBe "Ada"
                    firstPerson.mellomnavn shouldBe "Augusta"
                    firstPerson.etternavn shouldBe "Lovelace"
                    firstPerson.status shouldBe "ACTIVE"
                    secondPerson.fnr shouldBe "10987654321"
                    secondPerson.fornavn shouldBe null
                    secondPerson.mellomnavn shouldBe null
                    secondPerson.etternavn shouldBe null
                    secondPerson.status shouldBe "INACTIVE"
                }
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
                transaction(TestDB.exposedDatabase) {
                    val persistedPerson = PersonEntity.all().single()
                    initiallyInserted shouldBe listOf(
                        InsertedPerson(
                            id = persistedPerson.id.value,
                            fnr = "12345678901",
                            fornavn = null,
                            mellomnavn = null,
                            etternavn = null,
                            status = "ACTIVE",
                            foedselsdato = null,
                        ),
                    )
                    persistedPerson.status shouldBe "ACTIVE"
                }
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

                insertedPersons.map(InsertedPerson::fnr) shouldBe listOf("10987654321", "11111111111")
                transaction(TestDB.exposedDatabase) {
                    val persistedByFnr = PersonEntity.all()
                        .orderBy(PersonTable.fnr to SortOrder.ASC)
                        .associateBy { it.fnr }

                    insertedPersons.forEach { insertedPerson ->
                        insertedPerson.id shouldBe persistedByFnr.getValue(insertedPerson.fnr).id.value
                    }

                    val existingPerson = persistedByFnr["12345678901"].shouldNotBeNull()
                    existingPerson.status shouldBe "ACTIVE"
                    existingPerson.fornavn shouldBe "Ada"
                    existingPerson.mellomnavn shouldBe null
                    existingPerson.etternavn shouldBe "Lovelace"
                }
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

                val insertedPersonsByFnr = insertedPersons.associateBy { it.fnr }
                transaction(TestDB.exposedDatabase) {
                    val persons = PersonEntity.all()
                        .orderBy(PersonTable.fnr to SortOrder.ASC)
                        .toList()
                    val personsByFnr = persons.associateBy { it.fnr }

                    persons.size shouldBe 2
                    persons.count { it.fnr == "12345678901" } shouldBe 1
                    persons.count { it.fnr == "10987654321" } shouldBe 1
                    insertedPersons shouldBe listOf(
                        InsertedPerson(
                            id = personsByFnr.getValue("12345678901").id.value,
                            fnr = "12345678901",
                            status = "ACTIVE",
                            fornavn = "Ada",
                            mellomnavn = "Augusta",
                            etternavn = null,
                            foedselsdato = null,
                        ),
                        InsertedPerson(
                            id = personsByFnr.getValue("10987654321").id.value,
                            fnr = "10987654321",
                            status = "PENDING",
                            fornavn = null,
                            mellomnavn = null,
                            etternavn = null,
                            foedselsdato = null,
                        ),
                    )
                    insertedPersonsByFnr.keys shouldBe personsByFnr.keys
                    insertedPersons.forEach { insertedPerson ->
                        insertedPerson.id shouldBe personsByFnr.getValue(insertedPerson.fnr).id.value
                    }
                    personsByFnr["12345678901"]?.status shouldBe "ACTIVE"
                }
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
                transaction(TestDB.exposedDatabase) {
                    val person = PersonEntity.find { PersonTable.fnr eq "12345678901" }.singleOrNull().shouldNotBeNull()
                    person.status shouldBe "ACTIVE"
                    person.fornavn shouldBe "Ada"
                    person.mellomnavn shouldBe "Augusta"
                    person.etternavn shouldBe "Lovelace"
                }
            }

            it("should handle empty input safely") {
                val insertedPersons = transaction(TestDB.exposedDatabase) {
                    PersonTable.batchInsertIgnoreExisting(emptyList())
                }

                insertedPersons shouldBe emptyList()
                transaction(TestDB.exposedDatabase) {
                    PersonEntity.all().count() shouldBe 0
                }
            }
        }
    })
