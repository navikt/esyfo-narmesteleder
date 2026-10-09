package no.nav.syfo.narmestelederstatistikk.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederstatistikk.application.Narmestelederstatistikk
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PGobject
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

private object NarmestelederbehovSeedTable : Table("nl_behov") {
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykemeldt_fnr", 11)
    val behovStatus = registerColumn("behov_status", BehovStatusSeedColumnType())
}

private class BehovStatusSeedColumnType : ColumnType<BehovStatus>() {
    override fun sqlType(): String = "BEHOV_STATUS"

    override fun valueFromDB(value: Any): BehovStatus = BehovStatus.valueOf(value.toString())

    override fun notNullValueToDB(value: BehovStatus): Any = PGobject().apply {
        type = sqlType()
        this.value = value.name
    }
}

private fun insertBehov(
    employeeIdent: String,
    status: BehovStatus,
    organization: String = organizationNumber.value,
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederbehovSeedTable.insert {
            it[orgnummer] = organization
            it[sykmeldtFnr] = employeeIdent
            it[behovStatus] = status
        }
    }
}

private object NarmestelederSeedTable : Table("narmeste_leder") {
    val narmestelederId = javaUUID("narmeste_leder_id")
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykmeldt_fnr", 11)
    val narmestelederFnr = varchar("narmeste_leder_fnr", 11)
    val narmestelederTelefonnummer = varchar("narmeste_leder_telefonnummer", 255)
    val narmestelederEpost = varchar("narmeste_leder_epost", 255)
    val aktivFom = timestampWithTimeZone("aktiv_fom")
    val aktivTom = timestampWithTimeZone("aktiv_tom").nullable()
}

private fun insertRelation(
    employeeIdent: String,
    organization: String = organizationNumber.value,
    activeFrom: OffsetDateTime = now.minusDays(1),
    activeTo: OffsetDateTime? = null,
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederSeedTable.insert {
            it[narmestelederId] = UUID.randomUUID()
            it[orgnummer] = organization
            it[sykmeldtFnr] = employeeIdent
            it[narmestelederFnr] = "10987654321"
            it[narmestelederTelefonnummer] = "99999999"
            it[narmestelederEpost] = "manager@example.com"
            it[aktivFom] = activeFrom
            it[aktivTom] = activeTo
        }
    }
}

private object SendtSykmeldingSeedTable : Table("sendt_sykmelding") {
    val sykmeldingId = javaUUID("sykmelding_id")
    val orgnummer = varchar("orgnummer", 9)
    val syketilfelleStartDato = date("syketilfelle_startdato").nullable()
    val fnr = text("fnr")
    val fom = date("fom")
    val tom = date("tom")
    val revokedDate = date("revoked_date").nullable()
}

private fun insertSykmelding(
    employeeIdent: String,
    organization: String = organizationNumber.value,
    to: LocalDate = today.plusDays(1),
    revokedDate: LocalDate? = null,
) {
    transaction(TestDB.exposedDatabase) {
        SendtSykmeldingSeedTable.insert {
            it[sykmeldingId] = UUID.randomUUID()
            it[orgnummer] = organization
            it[syketilfelleStartDato] = to.minusDays(10)
            it[fnr] = employeeIdent
            it[fom] = to.minusDays(20)
            it[tom] = to
            it[SendtSykmeldingSeedTable.revokedDate] = revokedDate
        }
    }
}
