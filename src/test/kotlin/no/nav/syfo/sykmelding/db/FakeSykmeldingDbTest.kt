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
    })
