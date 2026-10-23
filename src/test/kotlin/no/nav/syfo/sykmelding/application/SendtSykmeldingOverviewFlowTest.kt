package no.nav.syfo.sykmelding.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.defaultSendtSykmeldingMessage
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreationResult
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerNarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederrelasjon.application.lookup.HasActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.search.NarmestelederrelasjonSearchQuery
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.insertRelation
import no.nav.syfo.sykmelding.infrastructure.ExposedSykmeldingDb
import no.nav.syfo.sykmelding.infrastructure.PostgresSendtSykmeldingNarmestelederBruddRepository
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SendtSykmeldingOverviewFlowTest :
    FunSpec({
        val employee = PersonalIdentificationNumber("12345678901")
        val organization = OrganizationNumber("123456789")
        val dialogCalls = mutableListOf<PersonalIdentificationNumber>()
        val handler = SendtSykmeldingHandler(
            createNarmestelederbehov = CreateNarmestelederbehovUseCase(
                settings = NarmestelederbehovCreationSettings(persistenceEnabled = true),
                repository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase),
                // Employer metadata and the current period make these external lookups unnecessary.
                activeSykmelding = { error("Unexpected sick leave lookup") },
                mainOrganization = { error("Unexpected main organization lookup") },
                personLookup = { EmployeePersonLookupResult.NotFound },
                dialog = { _, employee, _ ->
                    dialogCalls.add(employee.personIdent)
                    NarmestelederbehovDialogCreationResult.Created(UUID.randomUUID())
                },
                metrics = MicrometerNarmestelederbehovCreationMetrics(),
            ),
            sykmeldingService = SykmeldingService(ExposedSykmeldingDb(TestDB.exposedDatabase)),
            // Overview sykmeldinger have no leader answer, so no relation is revoked.
            narmestelederBruddService = NarmestelederBruddService(
                revokeNarmestelederrelasjon = { error("Unexpected revocation") },
                bruddRepository = PostgresSendtSykmeldingNarmestelederBruddRepository(TestDB.exposedDatabase),
            ),
            hasActiveNarmestelederrelasjon = HasActiveNarmestelederrelasjonUseCase(
                ExposedActiveNarmestelederrelasjonRepository(TestDB.exposedDatabase),
            ),
        )
        val openBehovRepository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase)
        val relationSearchRepository = ExposedNarmestelederrelasjonSearchRepository(TestDB.exposedDatabase)
        val activeSickLeaveQuery = NarmestelederrelasjonSearchQuery(
            orgNumber = organization,
            hasActiveSickLeave = true,
            pageSize = 50,
        )

        beforeTest {
            TestDB.clearAllData()
            dialogCalls.clear()
        }
        afterTest {
            TestDB.clearAllData()
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

private suspend fun SendtSykmeldingHandler.handleOverviewSykmelding(employee: PersonalIdentificationNumber, organization: OrganizationNumber) {
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

private fun insertOverviewRelation(
    employee: PersonalIdentificationNumber,
    organization: OrganizationNumber,
    activeTo: OffsetDateTime? = null,
) {
    insertRelation(
        employeeIdent = employee.value,
        organizationNumber = organization.value,
        activeFrom = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(1),
        activeTo = activeTo,
        email = "manager@example.com",
        mobile = "90000000",
    )
}
