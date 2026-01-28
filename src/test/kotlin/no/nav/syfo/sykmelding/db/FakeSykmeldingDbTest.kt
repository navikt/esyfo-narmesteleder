package no.nav.syfo.sykmelding.db

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FakeSykmeldingDbTest :
    DescribeSpec({

        val db = FakeSykmeldingDb()

        beforeEach {
            db.clear()
        }

        fun entity(
            sykmeldingId: UUID = UUID.randomUUID(),
            revokedDate: LocalDate? = null,
            fom: LocalDate = LocalDate.of(2025, 1, 1),
            tom: LocalDate = LocalDate.of(2025, 1, 5),
            orgnummer: String = "999999999",
        ) = SendtSykmeldingEntity(
            sykmeldingId = sykmeldingId,
            fnr = "12345678910",
            orgnummer = orgnummer,
            fom = fom,
            tom = tom,
            revokedDate = revokedDate,
            syketilfelleStartDato = fom,
            created = Instant.parse("2025-01-01T00:00:00Z"),
            updated = Instant.parse("2025-01-02T00:00:00Z"),
        )

        describe("insertSykmelding") {
            it("should update existing entry when inserting with same sykmeldingId") {
                val id = UUID.randomUUID()
                db.insertOrUpdateSykmelding(entity(sykmeldingId = id, orgnummer = "111111111"))
                db.insertOrUpdateSykmelding(entity(sykmeldingId = id, orgnummer = "111111111"))

                db.findAll() shouldHaveSize 1
                db.findBySykmeldingId(id) shouldNotBe null
            }
        }

        describe("revokeSykmelding") {
            it("returns 0 if sykmeldingId is unknown") {
                db.revokeSykmelding(UUID.randomUUID(), LocalDate.of(2025, 1, 10)) shouldBe 0
            }

            it("updates row where tom <= revokedDate and returns 1") {
                val sykmeldingId = UUID.randomUUID()

                // Eligible (tom <= revokedDate)
                db.insertOrUpdateSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "111111111",
                        tom = LocalDate.of(2025, 1, 5),
                    )
                )

                val revokedDate = LocalDate.of(2025, 1, 10)
                db.revokeSykmelding(sykmeldingId, revokedDate) shouldBe 1

                val row = db.findBySykmeldingId(sykmeldingId)
                row shouldNotBe null
                row!!.revokedDate shouldBe revokedDate
            }

            it("does not update row where tom > revokedDate") {
                val sykmeldingId = UUID.randomUUID()

                db.insertOrUpdateSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "111111111",
                        tom = LocalDate.of(2025, 2, 1),
                    )
                )

                val revokedDate = LocalDate.of(2025, 1, 10)
                db.revokeSykmelding(sykmeldingId, revokedDate) shouldBe 0

                val row = db.findBySykmeldingId(sykmeldingId)
                row shouldNotBe null
                row!!.revokedDate shouldBe null
            }
        }
    })
