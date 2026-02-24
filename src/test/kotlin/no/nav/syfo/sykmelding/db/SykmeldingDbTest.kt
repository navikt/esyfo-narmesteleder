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

        describe("transaction - upsertSykmeldingBatch") {
            it("should persist a single sykmelding entity and allow retrieval") {
                val sykmeldingId = UUID.randomUUID()
                val toInsert = entity(sykmeldingId = sykmeldingId)

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(listOf(toInsert))
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
                    batchUpsertSykmeldingerIfMoreRecentTom(entities)
                }

                ids.forEachIndexed { index, id ->
                    val retrieved = db.findBySykmeldingId(id)
                    retrieved shouldNotBe null
                    retrieved!!.fnr shouldBe "1234567891$index"
                    retrieved.orgnummer shouldBe "99999999$index"
                }
            }

            it("should update existing row when incoming tom is newer") {
                val fnr = "12345678901"
                val orgnummer = "999999999"
                val oldId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            entity(sykmeldingId = oldId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 1, 10))
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            entity(sykmeldingId = newId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 2, 10))
                        )
                    )
                }

                val row = db.findBySykmeldingId(newId)
                row shouldNotBe null
                row!!.tom shouldBe LocalDate.of(2025, 2, 10)

                db.findBySykmeldingId(oldId) shouldBe null
            }

            it("should not update existing row when incoming tom is older") {
                val fnr = "12345678901"
                val orgnummer = "999999999"
                val existingId = UUID.randomUUID()
                val incomingId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            entity(sykmeldingId = existingId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 3, 10))
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            entity(sykmeldingId = incomingId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 1, 10))
                        )
                    )
                }

                // Existing row should be unchanged
                val row = db.findBySykmeldingId(existingId)
                row shouldNotBe null
                row!!.tom shouldBe LocalDate.of(2025, 3, 10)

                // Incoming row should not exist
                db.findBySykmeldingId(incomingId) shouldBe null
            }

            it("should update existing row when incoming tom is equal") {
                val fnr = "12345678901"
                val orgnummer = "999999999"
                val existingId = UUID.randomUUID()
                val incomingId = UUID.randomUUID()
                val sameTom = LocalDate.of(2025, 2, 15)

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            entity(sykmeldingId = existingId, fnr = fnr, orgnummer = orgnummer, tom = sameTom, fom = LocalDate.of(2025, 1, 1))
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            entity(sykmeldingId = incomingId, fnr = fnr, orgnummer = orgnummer, tom = sameTom, fom = LocalDate.of(2025, 2, 1))
                        )
                    )
                }

                // Should have updated to the incoming entity
                val row = db.findBySykmeldingId(incomingId)
                row shouldNotBe null
                row!!.fom shouldBe LocalDate.of(2025, 2, 1)

                db.findBySykmeldingId(existingId) shouldBe null
            }

            it("should do nothing when batch is empty") {
                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(emptyList())
                }
            }
        }

        describe("transaction - revokeSykmeldingBatch") {
            it("should return 0 when no matching sykmeldingIds exist") {
                val unknownIds = listOf(UUID.randomUUID(), UUID.randomUUID())
                var result = 0
                db.transaction {
                    result = batchRevokeSykmelding(unknownIds, LocalDate.of(2025, 1, 15))
                }
                result shouldBe 0
            }

            it("should update single row with revokedDate and return 1") {
                val sykmeldingId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
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
                    affectedRows = batchRevokeSykmelding(listOf(sykmeldingId), revokedDate)
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
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(entity1, entity2, entity3)
                    )
                }

                val revokedDate = LocalDate.of(2025, 1, 12)
                var affectedRows = 0
                db.transaction {
                    affectedRows = batchRevokeSykmelding(listOf(entity1.sykmeldingId, entity2.sykmeldingId, entity3.sykmeldingId), revokedDate)
                }

                affectedRows shouldBe 3

                db.findBySykmeldingId(entity1.sykmeldingId)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(entity2.sykmeldingId)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(entity3.sykmeldingId)!!.revokedDate shouldBe revokedDate
            }

            it("should return 0 when batch is empty") {
                var result = 0
                db.transaction {
                    result = batchRevokeSykmelding(emptyList(), LocalDate.now())
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
                    batchUpsertSykmeldingerIfMoreRecentTom(
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
                    batchRevokeSykmelding(listOf(sykmeldingId), LocalDate.of(2025, 3, 20))
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
                        batchUpsertSykmeldingerIfMoreRecentTom(listOf(entity(sykmeldingId = sykmeldingId)))
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
                    batchUpsertSykmeldingerIfMoreRecentTom(
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
                        batchUpsertSykmeldingerIfMoreRecentTom(listOf(entity(sykmeldingId = newId)))
                        batchRevokeSykmelding(listOf(existingId), LocalDate.of(2025, 1, 15))
                    }
                }

                exception.message shouldContain "Transaction failed"

                db.findBySykmeldingId(newId) shouldBe null

                val existingRow = db.findBySykmeldingId(existingId)
                existingRow shouldNotBe null
                existingRow!!.revokedDate shouldBe null
            }

            it("should commit both upsert and revoke when transaction succeeds") {
                val revokeId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
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
                    batchUpsertSykmeldingerIfMoreRecentTom(listOf(entity(orgnummer = "111111111", sykmeldingId = newId)))
                    batchRevokeSykmelding(listOf(revokeId), LocalDate.of(2025, 1, 15))
                }

                val newSykmelding = db.findBySykmeldingId(newId)
                newSykmelding shouldNotBe null
                val revokedRow = db.findBySykmeldingId(revokeId)
                revokedRow shouldNotBe null
                revokedRow!!.revokedDate shouldBe LocalDate.of(2025, 1, 15)
            }
        }
    })
