package no.nav.syfo.narmestelederstatistikk.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.infrastructure.NarmestelederbehovTable
import no.nav.syfo.narmestelederrelasjon.infrastructure.NarmestelederTable
import no.nav.syfo.narmestelederstatistikk.application.Narmestelederstatistikk
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedNarmestelederstatistikkRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederstatistikkRepository(TestDB.exposedDatabase, fixedClock)

        beforeTest {
            TestDB.clearAllData()
            TestDB.clearNarmestelederData()
            TestDB.clearSendtSykmeldingData()
        }

        test("counts unique employees in each legacy statistic category") {
            insertBehov("12345678910", BehovStatus.BEHOV_CREATED)
            insertBehov("12345678910", BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED)
            insertBehov("12345678911", BehovStatus.BEHOV_FULFILLED)
            insertBehov("12345678912", BehovStatus.BEHOV_CREATED, OTHER_ORGANIZATION)
            insertRelation("12345678920")
            insertRelation("12345678920")
            insertRelation("12345678921")
            insertRelation("12345678922", activeTo = now.minusDays(1))
            insertRelation("12345678923", activeFrom = now.plusDays(1))
            insertRelation("12345678924", organization = OTHER_ORGANIZATION)
            insertSykmelding("12345678920", to = today)
            insertSykmelding("12345678921", to = today.plusDays(1), revokedDate = today.minusDays(1))

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(1, 1, 1)
        }

        test("returns zero for an empty organization") {
            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(0, 0, 0)
        }

        test("counts one active behov alongside repeated history and duplicate relations") {
            insertBehov("12345678910", BehovStatus.BEHOV_CREATED)
            repeat(2) {
                insertBehov("12345678910", BehovStatus.BEHOV_FULFILLED)
                insertRelation("12345678920")
                insertRelation("12345678921")
            }
            insertSykmelding("12345678920", to = today)

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(1, 1, 1)
        }

        test("excludes other organizations and correlates sykmelding by employee and organization") {
            insertBehov("12345678910", BehovStatus.BEHOV_CREATED, OTHER_ORGANIZATION)
            insertRelation("12345678920", organization = OTHER_ORGANIZATION)
            insertSykmelding("12345678920", organization = OTHER_ORGANIZATION)
            insertRelation("12345678921")
            insertSykmelding("12345678921", organization = OTHER_ORGANIZATION)
            insertSykmelding("12345678922")

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(0, 0, 1)
        }

        test("excludes ended and future relations but includes a relation starting exactly now") {
            insertRelation("12345678920", activeTo = now.minusSeconds(1))
            insertRelation("12345678921", activeTo = now)
            insertRelation("12345678922", activeTo = now.plusDays(1))
            insertRelation("12345678923", activeFrom = now.plusSeconds(1))
            insertRelation("12345678924", activeFrom = now)

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(0, 0, 1)
        }

        test("includes sykmelding ending today and excludes one ending yesterday") {
            insertRelation("12345678920")
            insertRelation("12345678921")
            insertSykmelding("12345678920", to = today)
            insertSykmelding("12345678921", to = today.minusDays(1))

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(0, 1, 1)
        }

        test("includes revocation today or later but excludes revocation before today") {
            insertRelation("12345678920")
            insertRelation("12345678921")
            insertRelation("12345678922")
            insertSykmelding("12345678920", revokedDate = today)
            insertSykmelding("12345678921", revokedDate = today.minusDays(1))
            insertSykmelding("12345678922", revokedDate = today.plusDays(1))

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(0, 2, 1)
        }

        test("counts only the two open behov statuses") {
            BehovStatus.entries.forEachIndexed { index, status ->
                insertBehov("1234567890$index", status)
            }

            repository.countFor(organizationNumber) shouldBe Narmestelederstatistikk(2, 0, 0)
        }
    })

private val fixedClock = Clock.fixed(Instant.parse("2026-02-01T12:00:00Z"), ZoneOffset.UTC)
private val now = OffsetDateTime.now(fixedClock)
private val today = now.toLocalDate()
private val organizationNumber = OrganizationNumber("123456789")
private const val OTHER_ORGANIZATION = "987654321"

private fun insertBehov(
    employeeIdent: String,
    status: BehovStatus,
    organization: String = organizationNumber.value,
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederbehovTable.insert {
            it[id] = UUID.randomUUID()
            it[orgnummer] = organization
            it[sykmeldtFnr] = employeeIdent
            it[hovedenhetOrgnummer] = organization
            it[created] = now
            it[updated] = now
            it[behovReason] = "DEAKTIVERT_LEDER"
            it[behovStatus] = status
        }
    }
}

private fun insertRelation(
    employeeIdent: String,
    organization: String = organizationNumber.value,
    activeFrom: OffsetDateTime = now.minusDays(1),
    activeTo: OffsetDateTime? = null,
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederTable.insert {
            it[narmestelederId] = UUID.randomUUID()
            it[orgnummer] = organization
            it[sykmeldtFnr] = employeeIdent
            it[narmestelederFnr] = "10987654321"
            it[narmestelederTelefonnummer] = "99999999"
            it[narmestelederEpost] = "manager@example.com"
            it[arbeidsgiverForskutterer] = true
            it[aktivFom] = activeFrom
            it[aktivTom] = activeTo
        }
    }
}

private fun insertSykmelding(
    employeeIdent: String,
    organization: String = organizationNumber.value,
    to: LocalDate = today.plusDays(1),
    revokedDate: LocalDate? = null,
) {
    transaction(TestDB.exposedDatabase) {
        SendtSykmeldingTable.insert {
            it[sykmeldingId] = UUID.randomUUID()
            it[orgnummer] = organization
            it[syketilfelleStartDato] = to.minusDays(10)
            it[fnr] = employeeIdent
            it[fom] = to.minusDays(20)
            it[tom] = to
            it[SendtSykmeldingTable.revokedDate] = revokedDate
        }
    }
}
