package no.nav.syfo.sykmelding.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.spyk
import net.datafaker.Faker
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
            fnr: String = Faker().numerify("###########"),
            orgnummer: String = Faker().numerify("#########"),
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
                    insertSykmeldingBatch(listOf(toInsert))
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
                    insertSykmeldingBatch(entities)
                }

                ids.forEachIndexed { index, id ->
                    val retrieved = db.findBySykmeldingId(id)
                    retrieved shouldNotBe null
                    retrieved!!.fnr shouldBe "1234567891$index"
                    retrieved.orgnummer shouldBe "99999999$index"
                }
            }

            it("should do nothing when batch is empty") {
                db.transaction {
                    insertSykmeldingBatch(emptyList())
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
                    insertSykmeldingBatch(
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
                val entity1 = entity(sykmeldingId = UUID.randomUUID(), tom = LocalDate.of(2025, 1, 5))
                val entity2 = entity(sykmeldingId = UUID.randomUUID(), tom = LocalDate.of(2025, 1, 10), orgnummer = "111111111")
                val entity3 = entity(sykmeldingId = UUID.randomUUID(), tom = LocalDate.of(2025, 1, 15), orgnummer = "222222222")

                db.transaction {
                    insertSykmeldingBatch(
                        listOf(entity1, entity2, entity3)
                    )
                }

                val revokedDate = LocalDate.of(2025, 1, 12)
                var affectedRows = 0
                db.transaction {
                    affectedRows = revokeSykmeldingBatch(listOf(entity1.sykmeldingId, entity2.sykmeldingId, entity3.sykmeldingId), revokedDate)
                }

                affectedRows shouldBe 3

                db.findBySykmeldingId(entity1.sykmeldingId)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(entity2.sykmeldingId)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(entity3.sykmeldingId)!!.revokedDate shouldBe revokedDate
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

        describe("mapping correctness") {
            it("should correctly map all fields from ResultSet") {
                val sykmeldingId = UUID.randomUUID()
                val fom = LocalDate.of(2025, 3, 1)
                val tom = LocalDate.of(2025, 3, 15)
                val syketilfelleStart = LocalDate.of(2025, 2, 28)

                db.transaction {
                    insertSykmeldingBatch(
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
                        insertSykmeldingBatch(listOf(entity(sykmeldingId = sykmeldingId)))
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
                    insertSykmeldingBatch(
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
                        insertSykmeldingBatch(listOf(entity(sykmeldingId = newId)))
                        revokeSykmeldingBatch(listOf(existingId), LocalDate.of(2025, 1, 15))
                    }
                }

                exception.message shouldContain "Transaction failed"

                db.findBySykmeldingId(newId) shouldBe null

                val existingRow = db.findBySykmeldingId(existingId)
                existingRow shouldNotBe null
                existingRow!!.revokedDate shouldBe null
            }

            it("should commit both insert, delete and revoke when transaction succeeds") {
                val insertedEntity = entity(
                    tom = LocalDate.of(2025, 1, 10),
                    orgnummer = "111111111",
                )
                val revokeId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    insertSykmeldingBatch(
                        listOf(
                            entity(
                                sykmeldingId = revokeId,
                                orgnummer = "222222222",
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                db.transaction {
                    deleteAllByFnrAndOrgnr(listOf(insertedEntity.fnrToOrgnummerPair()))
                    insertSykmeldingBatch(listOf(entity(orgnummer = "111111111", fnr = insertedEntity.fnr, sykmeldingId = newId)))
                    revokeSykmeldingBatch(listOf(revokeId), LocalDate.of(2025, 1, 15))
                }

                val existingSykmelding = db.findBySykmeldingId(insertedEntity.sykmeldingId)
                existingSykmelding shouldBe null
                val newSykmelding = db.findBySykmeldingId(newId)
                newSykmelding shouldNotBe null
                val revokedRow = db.findBySykmeldingId(revokeId)
                revokedRow shouldNotBe null
                revokedRow!!.revokedDate shouldBe LocalDate.of(2025, 1, 15)
            }
        }

        describe("transaction - deleteAll") {
            it("should do nothing when deleting empty list") {
                db.transaction {
                    deleteAllByFnrAndOrgnr(emptyList())
                }
            }

            it("should delete a single sykmelding by fnr and orgnr") {
                val entity = entity()

                db.transaction {
                    insertSykmeldingBatch(listOf(entity))
                }

                db.findBySykmeldingId(entity.sykmeldingId) shouldNotBe null

                db.transaction {
                    deleteAllByFnrAndOrgnr(listOf(entity.fnrToOrgnummerPair()))
                }

                db.findBySykmeldingId(entity.sykmeldingId) shouldBe null
            }

            it("should delete multiple sykmeldinger in one batch") {
                val entity1 = entity()
                val entity2 = entity()
                val entity3 = entity()

                db.transaction {
                    insertSykmeldingBatch(
                        listOf(
                            entity1,
                            entity2,
                            entity3
                        )
                    )
                }

                db.findBySykmeldingId(entity1.sykmeldingId) shouldNotBe null
                db.findBySykmeldingId(entity2.sykmeldingId) shouldNotBe null
                db.findBySykmeldingId(entity3.sykmeldingId) shouldNotBe null

                db.transaction {
                    deleteAllByFnrAndOrgnr(
                        listOf(entity1, entity2, entity3)
                            .map(SendtSykmeldingEntity::fnrToOrgnummerPair)
                    )
                }

                db.findBySykmeldingId(entity1.sykmeldingId) shouldBe null
                db.findBySykmeldingId(entity2.sykmeldingId) shouldBe null
                db.findBySykmeldingId(entity3.sykmeldingId) shouldBe null
            }

            it("should only delete specified sykmeldinger, leaving others intact") {
                val toDelete = entity()
                val toKeep = entity(
                    fnr = "22222222222",
                )

                db.transaction {
                    insertSykmeldingBatch(
                        listOf(
                            toDelete,
                            toKeep
                        )
                    )
                }

                db.transaction {
                    deleteAllByFnrAndOrgnr(listOf(toDelete.fnrToOrgnummerPair()))
                }

                db.findBySykmeldingId(toDelete.sykmeldingId) shouldBe null
                db.findBySykmeldingId(toKeep.sykmeldingId) shouldNotBe null
                db.findBySykmeldingId(toKeep.sykmeldingId)!!.fnr shouldBe "22222222222"
            }

            it("should not throw when deleting non-existent ids") {
                val unknownEntity = entity(sykmeldingId = UUID.randomUUID())

                db.transaction {
                    deleteAllByFnrAndOrgnr(listOf(unknownEntity.fnrToOrgnummerPair()))
                }
            }

            it("should delete and insert in same transaction") {
                val existing = entity(fnr = "22222222222")
                val new = entity(fnr = "22222222222")

                db.transaction {
                    insertSykmeldingBatch(
                        listOf(existing)
                    )
                }

                db.transaction {
                    deleteAllByFnrAndOrgnr(listOf(existing.fnrToOrgnummerPair()))
                    insertSykmeldingBatch(
                        listOf(new)
                    )
                }

                db.findBySykmeldingId(existing.sykmeldingId) shouldBe null
                db.findBySykmeldingId(new.sykmeldingId) shouldNotBe null
                db.findBySykmeldingId(new.sykmeldingId)!!.fnr shouldBe "22222222222"
            }

            it("should rollback delete when transaction fails") {
                val spyDb = spyk(testDb)
                val dbWithSpy = SykmeldingDb(spyDb)

                val existing = entity()

                db.transaction {
                    insertSykmeldingBatch(
                        listOf(existing)
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
                        deleteAllByFnrAndOrgnr(listOf(existing.fnrToOrgnummerPair()))
                    }
                }

                exception.message shouldContain "Transaction failed"

                db.findBySykmeldingId(existing.sykmeldingId) shouldNotBe null
            }
        }
    })
