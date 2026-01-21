package no.nav.syfo.sykmelding.db

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
            it("allows multiple rows with the same sykmeldingId") {
                val id = UUID.randomUUID()
                db.insertSykmelding(entity(sykmeldingId = id, orgnummer = "111111111"))
                db.insertSykmelding(entity(sykmeldingId = id, orgnummer = "222222222"))

                db.findAll() shouldHaveSize 2
                db.findBySykmeldingId(id) shouldHaveSize 2
            }
        }

        describe("revokeSykmelding") {
            it("returns 0 if sykmeldingId is unknown") {
                db.revokeSykmelding(UUID.randomUUID(), LocalDate.of(2025, 1, 10)) shouldBe 0
            }

            it("only updates rows where tom <= revokedDate and returns affected row count") {
                val sykmeldingId = UUID.randomUUID()

                // Eligible (tom <= revokedDate)
                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "111111111",
                        tom = LocalDate.of(2025, 1, 5),
                    )
                )

                // Not eligible (tom after revokedDate)
                db.insertSykmelding(
                    entity(
                        sykmeldingId = sykmeldingId,
                        orgnummer = "222222222",
                        tom = LocalDate.of(2025, 2, 1),
                    )
                )

                val revokedDate = LocalDate.of(2025, 1, 10)
                db.revokeSykmelding(sykmeldingId, revokedDate) shouldBe 1

                val rows = db.findBySykmeldingId(sykmeldingId)
                rows shouldHaveSize 2

                val eligible = rows.first { it.orgnummer == "111111111" }
                val notEligible = rows.first { it.orgnummer == "222222222" }

                eligible.revokedDate shouldBe revokedDate
                notEligible.revokedDate shouldBe null
            }
        }
    })
