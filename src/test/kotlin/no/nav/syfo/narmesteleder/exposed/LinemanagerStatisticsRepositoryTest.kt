package no.nav.syfo.narmesteleder.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LinemanagerStatisticsRepositoryTest :
    DescribeSpec({
        val fixedInstant = Instant.parse("2026-02-01T12:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val now = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
        val today = now.toLocalDate()
        val orgNumber = OrganizationNumber("123456789")
        val repository = LinemanagerStatisticsRepository(TestDB.exposedDatabase, fixedClock)
        val behovDb = NarmestelederDb(TestDB.database, Dispatchers.IO)

        beforeTest {
            TestDB.clearAllData()
            TestDB.clearNarmestelederData()
            TestDB.clearSendtSykmeldingData()
        }

        suspend fun insertBehov(
            employeeFnr: String,
            status: BehovStatus,
            orgnummer: String = orgNumber.value,
        ) {
            behovDb.insertNlBehov(
                nlBehovEntity().copy(
                    orgnummer = orgnummer,
                    hovedenhetOrgnummer = orgnummer,
                    sykmeldtFnr = employeeFnr,
                    behovStatus = status,
                ),
            )
        }

        fun insertRelation(
            employeeFnr: String,
            orgnummer: String = orgNumber.value,
            aktivFom: OffsetDateTime = now.minusDays(1),
            aktivTom: OffsetDateTime? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                NarmestelederEntity.new {
                    narmesteLederId = UUID.randomUUID()
                    this.orgnummer = orgnummer
                    sykmeldtFnr = employeeFnr
                    narmestelederFnr = "10987654321"
                    narmestelederTelefonnummer = "99999999"
                    narmestelederEpost = "leder@example.com"
                    arbeidsgiverForskutterer = true
                    this.aktivFom = aktivFom
                    this.aktivTom = aktivTom
                }
            }
        }

        fun insertSykmelding(
            employeeFnr: String,
            orgnummer: String = orgNumber.value,
            tom: LocalDate,
            revokedDate: LocalDate? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingTable.insert {
                    it[sykmeldingId] = UUID.randomUUID()
                    it[SendtSykmeldingTable.orgnummer] = orgnummer
                    it[syketilfelleStartDato] = tom.minusDays(10)
                    it[fnr] = employeeFnr
                    it[fom] = tom.minusDays(20)
                    it[SendtSykmeldingTable.tom] = tom
                    it[SendtSykmeldingTable.revokedDate] = revokedDate
                }
            }
        }

        it("counts unique employees in each statistic category") {
            insertBehov("12345678910", BehovStatus.BEHOV_CREATED)
            insertBehov("12345678910", BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
            insertBehov("12345678911", BehovStatus.BEHOV_FULFILLED)
            insertBehov("12345678912", BehovStatus.BEHOV_CREATED, "987654321")

            insertRelation("12345678920")
            insertRelation("12345678920")
            insertRelation("12345678921")
            insertRelation("12345678922", aktivTom = now.minusDays(1))
            insertRelation("12345678923", aktivFom = now.plusDays(1))
            insertRelation("12345678924", orgnummer = "987654321")

            insertSykmelding("12345678920", tom = today)
            insertSykmelding("12345678921", tom = today.plusDays(1), revokedDate = today.minusDays(1))

            repository.getStatistics(orgNumber) shouldBe LinemanagerStatistics(
                employeesOnSickLeaveWithoutLinemanager = 1,
                employeesOnSickLeaveWithLinemanager = 1,
                employeesNotOnSickLeaveWithLinemanager = 1,
            )
        }
    })
