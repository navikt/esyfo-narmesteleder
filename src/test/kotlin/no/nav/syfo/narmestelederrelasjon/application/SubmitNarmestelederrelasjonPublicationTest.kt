package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponse
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class SubmitNarmestelederrelasjonPublicationTest :
    FunSpec({
        test("both caller types publish the unchanged Kafka payload and source") {
            val employee = PersonIdent("12345678901")
            val manager = PersonIdent("10987654321")
            val organization = OrganizationNumber("123456789")
            listOf(
                OrganizationAccessSubject.LpsSystemUser("system-id", organization) to NlResponseSource.LPS,
                OrganizationAccessSubject.PersonnelManager(PersonIdent("11223344556"), AccessToken("test-token")) to NlResponseSource.PERSONALLEDER,
            ).forEach { (subject, expectedSource) ->
                val producer = RecordingProducer()
                val establish = EstablishNarmestelederrelasjonUseCase(
                    ActiveSykmeldingLookup { _, _ -> true },
                    EmploymentLookup { _, _ -> EmploymentResult.IN_ORGANIZATION },
                    PersonLookup { ident ->
                        when (ident) {
                            employee -> PersonDetails(employee, name("Employee", "Middle", "Employee"))
                            manager -> PersonDetails(manager, name("Manager", "Other", "Manager"))
                            else -> null
                        }
                    },
                    NameValidationMetrics { },
                    KafkaPublishNarmestelederrelasjon(producer),
                )
                val submit = SubmitNarmestelederrelasjonUseCase(
                    OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
                    establish,
                )

                submit.execute(
                    SubmitNarmestelederrelasjonCommand(
                        employee,
                        "Employee",
                        organization,
                        ManagerContactInput(manager, "Manager", " manager@example.test ", "+47 90 00 00 00"),
                        subject,
                    ),
                ) shouldBe SubmitNarmestelederrelasjonResult.Established(
                    if (expectedSource == NlResponseSource.LPS) {
                        no.nav.syfo.narmestelederrelasjon.domain.RelationSource.LPS
                    } else {
                        no.nav.syfo.narmestelederrelasjon.domain.RelationSource.PERSONNEL_MANAGER
                    },
                )
                producer.response?.let { (response, source) ->
                    response.orgnummer shouldBe organization.value
                    response.utbetalesLonn shouldBe true
                    response.sykmeldt.fnr shouldBe employee.value
                    response.sykmeldt.navn shouldBe "Employee Middle Employee"
                    response.leder.fnr shouldBe manager.value
                    response.leder.fornavn shouldBe "Manager"
                    response.leder.etternavn shouldBe "Manager"
                    response.leder.mobil shouldBe "+4790000000"
                    response.leder.epost shouldBe "manager@example.test"
                    source shouldBe expectedSource
                } ?: error("No Kafka message published")
            }
        }
    })

private fun name(firstName: String, middleName: String, lastName: String) = PersonNameDetails(firstName, lastName, middleName, listOf(RegisteredName(lastName)))

private class RecordingProducer : SykmeldingNarmestelederProducer {
    var response: Pair<NlResponse, NlResponseSource>? = null
    override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) {
        response = sykmeldingNL to source
    }
    override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) = error("Unexpected revocation")
}
