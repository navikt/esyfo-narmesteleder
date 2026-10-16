package no.nav.syfo

import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedOpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerNarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederrelasjon.application.HasActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonSearchQuery
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.NarmestelederTable
import no.nav.syfo.sykmelding.db.PostgresSykmeldingDb
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.kafka.SykmeldingRecord
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.sykmelding.service.NarmestelederBruddService
import no.nav.syfo.sykmelding.service.SykmeldingService
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SendtSykmeldingOverviewFlowTest :
    FunSpec({
        val employee = PersonIdent("12345678901")
        val organization = OrganizationNumber("123456789")
        val dialogCalls = mutableListOf<PersonIdent>()
        val handler = SendtSykmeldingHandler(
            createNarmestelederbehov = CreateNarmestelederbehovUseCase(
                settings = NarmestelederbehovCreationSettings(persistenceEnabled = true),
                repository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase),
                // Employer metadata and the current period make these external lookups unnecessary.
                activeSykmelding = { error("Unexpected sick leave lookup") },
                mainOrganization = { error("Unexpected main organization lookup") },
                dialog = { _, behov -> dialogCalls.add(behov.employee.personIdent) },
                metrics = MicrometerNarmestelederbehovCreationMetrics(),
            ),
            sykmeldingService = SykmeldingService(PostgresSykmeldingDb(TestDB.database)),
            narmestelederBruddService = mockk<NarmestelederBruddService>(),
            hasActiveNarmestelederrelasjon = HasActiveNarmestelederrelasjonUseCase(
                ExposedActiveNarmestelederrelasjonRepository(TestDB.exposedDatabase),
            ),
        )
        val openBehovRepository = ExposedOpenNarmestelederbehovRepository(TestDB.exposedDatabase)
        val relationSearchRepository = ExposedNarmestelederrelasjonSearchRepository(TestDB.exposedDatabase)
        val activeSickLeaveQuery = NarmestelederrelasjonSearchQuery(
            orgNumber = organization,
            hasActiveSickLeave = true,
            pageSize = 50,
        )

        beforeTest {
            clearOverviewFlowData()
            dialogCalls.clear()
        }
        afterTest {
            clearOverviewFlowData()
        }

        test("sendt sykmelding without a leader answer shows an employee with an active relation only in Aktiv sykmelding") {
            insertOverviewRelation(employee, organization)

            handler.handleOverviewSykmelding(employee, organization)

            relationSearchRepository.search(activeSickLeaveQuery)
                .map { it.linemanager.employee.nationalIdentificationNumber } shouldBe listOf(employee)
            openBehovRepository.findOpen(organization, Instant.now().minusSeconds(3600), 50) shouldBe emptyList()
            openBehovRepository.countOpen(organization, Instant.now().minusSeconds(3600)) shouldBe 0L
            dialogCalls shouldBe emptyList()
        }

        test("sendt sykmelding without an active relation shows the employee in Mangler nærmeste leder") {
            handler.handleOverviewSykmelding(employee, organization)

            openBehovRepository.findOpen(organization, Instant.now().minusSeconds(3600), 50)
                .map { it.employeeIdent } shouldBe listOf(employee)
            relationSearchRepository.search(activeSickLeaveQuery) shouldBe emptyList()
            dialogCalls shouldBe listOf(employee)
        }

        test("an active relation in another organization does not prevent creating behov") {
            insertOverviewRelation(employee, OrganizationNumber("111222333"))

            handler.handleOverviewSykmelding(employee, organization)

            openBehovRepository.findOpen(organization, Instant.now().minusSeconds(3600), 50)
                .map { it.employeeIdent } shouldBe listOf(employee)
            relationSearchRepository.search(activeSickLeaveQuery) shouldBe emptyList()
            dialogCalls shouldBe listOf(employee)
        }

        test("a revoked relation in the same organization does not prevent creating behov") {
            insertOverviewRelation(employee, organization, activeTo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))

            handler.handleOverviewSykmelding(employee, organization)

            openBehovRepository.findOpen(organization, Instant.now().minusSeconds(3600), 50)
                .map { it.employeeIdent } shouldBe listOf(employee)
            relationSearchRepository.search(activeSickLeaveQuery) shouldBe emptyList()
            dialogCalls shouldBe listOf(employee)
        }
    })

private suspend fun SendtSykmeldingHandler.handleOverviewSykmelding(employee: PersonIdent, organization: OrganizationNumber) {
    // Producers omit riktigNarmesteLeder when employment has ended, while the relation can remain active.
    val message = defaultSendtSykmeldingMessage(
        sykmeldingId = UUID.randomUUID().toString(),
        fnr = employee.value,
        orgnummer = organization.value,
        juridiskOrgnummer = "987654321",
        sykmeldingsperioder = listOf(
            SykmeldingsperiodeAGDTO(LocalDate.now().minusDays(5), LocalDate.now().plusDays(5)),
        ),
        riktigNarmesteLeder = null,
    )
    handleSykmeldingBatch(listOf(SykmeldingRecord(1, UUID.fromString(message.event.sykmeldingId), message)))
    handleNarmestelederbehov(message)
}

private fun clearOverviewFlowData() {
    TestDB.clearAllData()
    TestDB.clearNarmestelederData()
    TestDB.clearSendtSykmeldingData()
}

private fun insertOverviewRelation(
    employee: PersonIdent,
    organization: OrganizationNumber,
    activeTo: OffsetDateTime? = null,
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederTable.insert {
            it[narmestelederId] = UUID.randomUUID()
            it[orgnummer] = organization.value
            it[sykmeldtFnr] = employee.value
            it[narmestelederFnr] = "10987654321"
            it[narmestelederTelefonnummer] = "90000000"
            it[narmestelederEpost] = "manager@example.com"
            it[aktivFom] = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(1)
            it[aktivTom] = activeTo
        }
    }
}
