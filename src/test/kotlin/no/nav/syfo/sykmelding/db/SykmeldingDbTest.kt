package no.nav.syfo.sykmelding.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.spyk
import no.nav.syfo.TestDB
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SykmeldingDbTest :
    DescribeSpec({

        val testDb = TestDB.database
        val db = SykmeldingDb(testDb)

        beforeTest {
            TestDB.clearSendtSykmeldingData()
        }

        fun entity(
            sykmeldingId: UUID = UUID.randomUUID(),
            fnr: String = "12345678910",
            orgnummer: String = "999999999",
            fom: LocalDate = LocalDate.of(2025, 1, 1),
            tom: LocalDate = LocalDate.of(2025, 1, 10),
            syketilfelleStartDato: LocalDate? = LocalDate.of(2025, 1, 1),
            revokedDate: LocalDate? = null,
        ) = SendtSykmeldingEntity(
            sykmeldingId = sykmeldingId,
            fnr = fnr,
            orgnummer = orgnummer,
            fom = fom,
            tom = tom,
            revokedDate = revokedDate,
            syketilfelleStartDato = syketilfelleStartDato,
            created = Instant.now(),
            updated = Instant.now(),
        )

        describe("transaction - insertOrUpdateSykmeldingBatch") {
            it("should persist a single sykmelding entity and allow retrieval") {
                val sykmeldingId = UUID.randomUUID()
                val toInsert = entity(sykmeldingId = sykmeldingId)

                db.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(toInsert))
                }

                val retrieved = db.findBySykmeldingId(sykmeldingId)
                retrieved shouldNotBe null
                val row = retrieved!!

                row.id shouldNotBe null
                row.sykmeldingId shouldBe sykmeldingId
                row.fnr shouldBe toInsert.fnr
                row.orgnummer shouldBe toInsert.orgnummer
                row.fom shouldBe toInsert.fom
                row.tom shouldBe toInsert.tom
                row.syketilfelleStartDato shouldBe toInsert.syketilfelleStartDato
                row.revokedDate shouldBe null
            }

            it("should persist multiple sykmelding entities in one batch") {
                val ids = (1..5).map { UUID.randomUUID() }
                val entities = ids.mapIndexed { index, id ->
                    entity(
                        sykmeldingId = id,
                        fnr = "1234567891$index",
                        orgnummer = "99999999$index"
                    )
                }

                db.transaction {
                    insertOrUpdateSykmeldingBatch(entities)
                }

                ids.forEachIndexed { index, id ->
                    val retrieved = db.findBySykmeldingId(id)
                    retrieved shouldNotBe null
                    retrieved!!.fnr shouldBe "1234567891$index"
                    retrieved.orgnummer shouldBe "99999999$index"
                }
            }

            it("should update existing entry when inserting with same sykmeldingId") {
                val sykmeldingId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = sykmeldingId,
                                orgnummer = "111111111",
                                fnr = "12345678901"
                            )
                        )
                    )
                }
                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = sykmeldingId,
                                orgnummer = "111111111",
                                fnr = "98765432109"
                            )
                        )
                    )
                }

                val retrieved = db.findBySykmeldingId(sykmeldingId)
                retrieved shouldNotBe null
                retrieved!!.fnr shouldBe "98765432109"
            }

            it("should handle mixed inserts and updates in one batch") {
                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = existingId,
                                fnr = "11111111111"
                            )
                        )
                    )
                }

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(sykmeldingId = existingId, fnr = "22222222222"),
                            entity(sykmeldingId = newId, fnr = "33333333333")
                        )
                    )
                }

                val existingRow = db.findBySykmeldingId(existingId)
                existingRow shouldNotBe null
                existingRow!!.fnr shouldBe "22222222222"

                val newRow = db.findBySykmeldingId(newId)
                newRow shouldNotBe null
                newRow!!.fnr shouldBe "33333333333"
            }

            it("should do nothing when batch is empty") {
                db.transaction {
                    insertOrUpdateSykmeldingBatch(emptyList())
                }
            }
        }

        describe("transaction - revokeSykmeldingBatch") {
            it("should return 0 when no matching sykmeldingIds exist") {
                val unknownIds = listOf(UUID.randomUUID(), UUID.randomUUID())
                var result = 0
                db.transaction {
                    result = revokeSykmeldingBatch(unknownIds, LocalDate.of(2025, 1, 15))
                }
                result shouldBe 0
            }

            it("should update single row with revokedDate and return 1") {
                val sykmeldingId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = sykmeldingId,
                                orgnummer = "111111111",
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                val revokedDate = LocalDate.of(2025, 1, 15)
                var affectedRows = 0
                db.transaction {
                    affectedRows = revokeSykmeldingBatch(listOf(sykmeldingId), revokedDate)
                }

                affectedRows shouldBe 1

                val row = db.findBySykmeldingId(sykmeldingId)
                row shouldNotBe null
                row!!.revokedDate shouldBe revokedDate
            }

            it("should revoke multiple sykmeldinger in one batch") {
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(sykmeldingId = id1, tom = LocalDate.of(2025, 1, 5)),
                            entity(sykmeldingId = id2, tom = LocalDate.of(2025, 1, 10)),
                            entity(sykmeldingId = id3, tom = LocalDate.of(2025, 1, 15))
                        )
                    )
                }

                val revokedDate = LocalDate.of(2025, 1, 12)
                var affectedRows = 0
                db.transaction {
                    affectedRows = revokeSykmeldingBatch(listOf(id1, id2, id3), revokedDate)
                }

                affectedRows shouldBe 3

                db.findBySykmeldingId(id1)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(id2)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(id3)!!.revokedDate shouldBe revokedDate
            }

            it("should return 0 when batch is empty") {
                var result = 0
                db.transaction {
                    result = revokeSykmeldingBatch(emptyList(), LocalDate.now())
                }
                result shouldBe 0
            }
        }

        describe("findBySykmeldingId") {
            it("should return null when no row exists") {
                val result = db.findBySykmeldingId(UUID.randomUUID())
                result shouldBe null
            }
        }

        describe("findSykmeldingIdsByFnrAndOrgnr") {
            it("should return empty list when no matches exist") {
                val result = db.findSykmeldingIdsByFnrAndOrgnr(listOf(Pair("00000000000", "000000000")))
                result shouldBe emptyList()
            }

            it("Should find matching sykmeldingIds for given fnr and orgnr") {
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(sykmeldingId = id1, fnr = "12345678910", orgnummer = "999999999"),
                            entity(sykmeldingId = id2, fnr = "12345678910", orgnummer = "999999999")
                        )
                    )
                }

                val result = db.findSykmeldingIdsByFnrAndOrgnr(listOf(Pair("12345678910", "999999999")))
                result shouldContain id1
                result shouldContain id2
            }
        }

        describe("mapping correctness") {
            it("should correctly map all fields from ResultSet") {
                val sykmeldingId = UUID.randomUUID()
                val fom = LocalDate.of(2025, 3, 1)
                val tom = LocalDate.of(2025, 3, 15)
                val syketilfelleStart = LocalDate.of(2025, 2, 28)

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = sykmeldingId,
                                fnr = "09876543210",
                                orgnummer = "123456789",
                                fom = fom,
                                tom = tom,
                                syketilfelleStartDato = syketilfelleStart,
                            )
                        )
                    )
                }

                // Revoke to test that field is also mapped
                db.transaction {
                    revokeSykmeldingBatch(listOf(sykmeldingId), LocalDate.of(2025, 3, 20))
                }

                val row = db.findBySykmeldingId(sykmeldingId)
                row shouldNotBe null

                row!!.id shouldNotBe null
                row.sykmeldingId shouldBe sykmeldingId
                row.fnr shouldBe "09876543210"
                row.orgnummer shouldBe "123456789"
                row.fom shouldBe fom
                row.tom shouldBe tom
                row.syketilfelleStartDato shouldBe syketilfelleStart
                row.revokedDate shouldBe LocalDate.of(2025, 3, 20)
                row.created shouldNotBe null
                row.updated shouldNotBe null
            }
        }

        describe("transaction rollback behavior") {
            it("should rollback entire transaction when an error occurs") {
                val spyDb = spyk(testDb)
                val dbWithSpy = SykmeldingDb(spyDb)

                val sykmeldingId = UUID.randomUUID()

                var shouldThrow = false
                every { spyDb.connection } answers {
                    val realConnection = testDb.connection
                    spyk(realConnection) {
                        every { commit() } answers {
                            if (shouldThrow) {
                                shouldThrow = false
                                throw RuntimeException("Simulated commit failure")
                            } else {
                                realConnection.commit()
                            }
                        }
                    }
                }

                shouldThrow = true
                val exception = shouldThrow<SykmeldingDbException> {
                    dbWithSpy.transaction {
                        insertOrUpdateSykmeldingBatch(listOf(entity(sykmeldingId = sykmeldingId)))
                    }
                }

                exception.message shouldContain "Transaction failed"
                exception.cause shouldNotBe null

                // Verify the data was NOT inserted (rollback worked)
                val afterRow = db.findBySykmeldingId(sykmeldingId)
                afterRow shouldBe null
            }

            it("should rollback both insert and revoke when transaction fails") {
                val spyDb = spyk(testDb)
                val dbWithSpy = SykmeldingDb(spyDb)

                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                // First insert a row that we'll try to revoke
                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = existingId,
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                var shouldThrow = false
                every { spyDb.connection } answers {
                    val realConnection = testDb.connection
                    spyk(realConnection) {
                        every { commit() } answers {
                            if (shouldThrow) {
                                shouldThrow = false
                                throw RuntimeException("Simulated commit failure")
                            } else {
                                realConnection.commit()
                            }
                        }
                    }
                }

                shouldThrow = true
                val exception = shouldThrow<SykmeldingDbException> {
                    dbWithSpy.transaction {
                        insertOrUpdateSykmeldingBatch(listOf(entity(sykmeldingId = newId)))
                        revokeSykmeldingBatch(listOf(existingId), LocalDate.of(2025, 1, 15))
                    }
                }

                exception.message shouldContain "Transaction failed"

                db.findBySykmeldingId(newId) shouldBe null

                val existingRow = db.findBySykmeldingId(existingId)
                existingRow shouldNotBe null
                existingRow!!.revokedDate shouldBe null
            }

            it("should commit both insert and revoke when transaction succeeds") {
                val insertId = UUID.randomUUID()
                val revokeId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = revokeId,
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                db.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(entity(sykmeldingId = insertId)))
                    revokeSykmeldingBatch(listOf(revokeId), LocalDate.of(2025, 1, 15))
                }

                val insertedRow = db.findBySykmeldingId(insertId)
                insertedRow shouldNotBe null

                val revokedRow = db.findBySykmeldingId(revokeId)
                revokedRow shouldNotBe null
                revokedRow!!.revokedDate shouldBe LocalDate.of(2025, 1, 15)
            }
        }

        describe("transaction - deleteAll") {
            it("should do nothing when deleting empty list") {
                db.transaction {
                    deleteAll(emptyList())
                }
            }

            it("should delete a single sykmelding by id") {
                val sykmeldingId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(listOf(entity(sykmeldingId = sykmeldingId)))
                }

                db.findBySykmeldingId(sykmeldingId) shouldNotBe null

                db.transaction {
                    deleteAll(listOf(sykmeldingId))
                }

                db.findBySykmeldingId(sykmeldingId) shouldBe null
            }

            it("should delete multiple sykmeldinger in one batch") {
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(sykmeldingId = id1),
                            entity(sykmeldingId = id2),
                            entity(sykmeldingId = id3)
                        )
                    )
                }

                db.findBySykmeldingId(id1) shouldNotBe null
                db.findBySykmeldingId(id2) shouldNotBe null
                db.findBySykmeldingId(id3) shouldNotBe null

                db.transaction {
                    deleteAll(listOf(id1, id2, id3))
                }

                db.findBySykmeldingId(id1) shouldBe null
                db.findBySykmeldingId(id2) shouldBe null
                db.findBySykmeldingId(id3) shouldBe null
            }

            it("should only delete specified sykmeldinger, leaving others intact") {
                val toDeleteId = UUID.randomUUID()
                val toKeepId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(
                            entity(sykmeldingId = toDeleteId, fnr = "11111111111"),
                            entity(sykmeldingId = toKeepId, fnr = "22222222222")
                        )
                    )
                }

                db.transaction {
                    deleteAll(listOf(toDeleteId))
                }

                db.findBySykmeldingId(toDeleteId) shouldBe null
                db.findBySykmeldingId(toKeepId) shouldNotBe null
                db.findBySykmeldingId(toKeepId)!!.fnr shouldBe "22222222222"
            }

            it("should not throw when deleting non-existent ids") {
                val unknownId = UUID.randomUUID()

                db.transaction {
                    deleteAll(listOf(unknownId))
                }
            }

            it("should delete and insert in same transaction") {
                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(entity(sykmeldingId = existingId, fnr = "11111111111"))
                    )
                }

                db.transaction {
                    deleteAll(listOf(existingId))
                    insertOrUpdateSykmeldingBatch(
                        listOf(entity(sykmeldingId = newId, fnr = "22222222222"))
                    )
                }

                db.findBySykmeldingId(existingId) shouldBe null
                db.findBySykmeldingId(newId) shouldNotBe null
                db.findBySykmeldingId(newId)!!.fnr shouldBe "22222222222"
            }

            it("should rollback delete when transaction fails") {
                val spyDb = spyk(testDb)
                val dbWithSpy = SykmeldingDb(spyDb)

                val existingId = UUID.randomUUID()

                db.transaction {
                    insertOrUpdateSykmeldingBatch(
                        listOf(entity(sykmeldingId = existingId))
                    )
                }

                var shouldThrow = false
                every { spyDb.connection } answers {
                    val realConnection = testDb.connection
                    spyk(realConnection) {
                        every { commit() } answers {
                            if (shouldThrow) {
                                shouldThrow = false
                                throw RuntimeException("Simulated commit failure")
                            } else {
                                realConnection.commit()
                            }
                        }
                    }
                }

                shouldThrow = true
                val exception = shouldThrow<SykmeldingDbException> {
                    dbWithSpy.transaction {
                        deleteAll(listOf(existingId))
                    }
                }

                exception.message shouldContain "Transaction failed"

                db.findBySykmeldingId(existingId) shouldNotBe null
            }
        }
    })
