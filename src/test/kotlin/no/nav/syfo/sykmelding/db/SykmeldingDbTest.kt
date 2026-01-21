package no.nav.syfo.sykmelding.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
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

        describe("insertSykmelding") {
            it("should persist a sykmelding entity and allow retrieval") {
                val sykmeldingId = UUID.randomUUID()
                val toInsert = entity(sykmeldingId = sykmeldingId)

                db.insertSykmelding(toInsert)

                val retrieved = db.findBySykmeldingId(sykmeldingId)
                retrieved shouldHaveSize 1
                val row = retrieved.first()

                row.id shouldNotBe null
                row.sykmeldingId shouldBe sykmeldingId
                row.fnr shouldBe toInsert.fnr
                row.orgnummer shouldBe toInsert.orgnummer
                row.fom shouldBe toInsert.fom
                row.tom shouldBe toInsert.tom
                row.syketilfelleStartDato shouldBe toInsert.syketilfelleStartDato
                row.revokedDate shouldBe null
            }

            it("should allow multiple rows with same sykmeldingId") {
                val sykmeldingId = UUID.randomUUID()

                db.insertSykmelding(entity(sykmeldingId = sykmeldingId, orgnummer = "111111111"))
                db.insertSykmelding(entity(sykmeldingId = sykmeldingId, orgnummer = "222222222"))

                val retrieved = db.findBySykmeldingId(sykmeldingId)
                retrieved shouldHaveSize 2
                retrieved.map { it.orgnummer }.toSet() shouldBe setOf("111111111", "222222222")
            }
        }

        describe("revokeSykmelding") {
            it("should return 0 when no matching sykmeldingId exists") {
                val unknownId = UUID.randomUUID()
                val result = db.revokeSykmelding(unknownId, LocalDate.of(2025, 1, 15))
                result shouldBe 0
            }

            it("should update only rows where tom <= revokedDate and return affected count") {
                val sykmeldingId = UUID.randomUUID()

                // tom = 2025-01-10 (should be updated when revokedDate = 2025-01-15)
                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "111111111",
                        tom = LocalDate.of(2025, 1, 10),
                    )
                )

                // tom = 2025-02-01 (should NOT be updated when revokedDate = 2025-01-15)
                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "222222222",
                        tom = LocalDate.of(2025, 2, 1),
                    )
                )

                val revokedDate = LocalDate.of(2025, 1, 15)
                val affectedRows = db.revokeSykmelding(sykmeldingId, revokedDate)

                affectedRows shouldBe 1

                val rows = db.findBySykmeldingId(sykmeldingId)
                rows shouldHaveSize 2

                val updated = rows.first { it.orgnummer == "111111111" }
                val notUpdated = rows.first { it.orgnummer == "222222222" }

                updated.revokedDate shouldBe revokedDate
                notUpdated.revokedDate shouldBe null
            }

            it("should update multiple rows if all match criteria") {
                val sykmeldingId = UUID.randomUUID()
                val revokedDate = LocalDate.of(2025, 1, 20)

                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "111111111",
                        tom = LocalDate.of(2025, 1, 10),
                    )
                )
                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "222222222",
                        tom = LocalDate.of(2025, 1, 15),
                    )
                )

                val affectedRows = db.revokeSykmelding(sykmeldingId, revokedDate)

                affectedRows shouldBe 2

                val rows = db.findBySykmeldingId(sykmeldingId)
                rows.all { it.revokedDate == revokedDate } shouldBe true
            }
        }

        describe("findBySykmeldingId") {
            it("should return empty list when no rows exist") {
                val result = db.findBySykmeldingId(UUID.randomUUID())
                result shouldHaveSize 0
            }
        }

        describe("mapping correctness") {
            it("should correctly map all fields from ResultSet") {
                val sykmeldingId = UUID.randomUUID()
                val fom = LocalDate.of(2025, 3, 1)
                val tom = LocalDate.of(2025, 3, 15)
                val syketilfelleStart = LocalDate.of(2025, 2, 28)

                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        fnr = "09876543210",
                        orgnummer = "123456789",
                        fom = fom,
                        tom = tom,
                        syketilfelleStartDato = syketilfelleStart,
                    )
                )

                // Revoke to test that field is also mapped
                db.revokeSykmelding(sykmeldingId, LocalDate.of(2025, 3, 20))

                val rows = db.findBySykmeldingId(sykmeldingId)
                rows shouldHaveSize 1

                val row = rows.first()
                row.id shouldNotBe null
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

        describe("rollback behavior") {
            it("should rollback and throw SykmeldingDbException when commit fails") {
                // Create a spy on the database to intercept the connection
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
                // First insert a row using to check spy setup
                dbWithSpy.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        tom = LocalDate.of(2025, 1, 10),
                    )
                )
                // Verify the row exists and has no revokedDate
                val beforeRows = dbWithSpy.findBySykmeldingId(sykmeldingId)
                beforeRows shouldHaveSize 1
                beforeRows.first().revokedDate shouldBe null

                shouldThrow = true
                // Attempt revoke - should fail and rollback
                val exception = shouldThrow<SykmeldingDbException> {
                    dbWithSpy.revokeSykmelding(sykmeldingId, LocalDate.of(2025, 1, 15))
                }

                exception.message shouldContain "Error revoking"
                exception.message shouldContain sykmeldingId.toString()
                exception.cause shouldNotBe null

                // Verify the data was NOT modified (rollback worked)
                val afterRows = dbWithSpy.findBySykmeldingId(sykmeldingId)
                afterRows shouldHaveSize 1
                afterRows.first().revokedDate shouldBe null
            }
        }
    })
