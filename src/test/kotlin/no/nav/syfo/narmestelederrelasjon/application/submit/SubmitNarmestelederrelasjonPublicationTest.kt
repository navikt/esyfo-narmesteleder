package no.nav.syfo.narmestelederrelasjon.application.submit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponse
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class SubmitNarmestelederrelasjonPublicationTest :
    FunSpec({
        val employee = PersonalIdentificationNumber("12345678901")
        val manager = PersonalIdentificationNumber("10987654321")
        val organization = OrganizationNumber("123456789")
        listOf(
            OrganizationAccessSubject.LpsSystemUser("system-id", organization) to NlResponseSource.LPS,
            OrganizationAccessSubject.PersonnelManager(PersonalIdentificationNumber("11223344556"), AccessToken("test-token")) to NlResponseSource.PERSONALLEDER,
        ).forEach { (subject, expectedSource) ->
            test("a ${subject::class.simpleName} caller publishes the unchanged Kafka payload and source $expectedSource") {
                val producer = RecordingProducer()
                val establish = EstablishNarmestelederrelasjonUseCase(
                    { _, _ -> true },
                    { _, _ -> EmploymentResult.InOrganization },
                    { ident ->
                        when (ident) {
                            employee -> PersonLookupResult.Found(PersonDetails(employee, name("Employee", "Middle", "Employee")))
                            manager -> PersonLookupResult.Found(PersonDetails(manager, name("Manager", "Other", "Manager")))
                            else -> PersonLookupResult.NotFound
                        }
                    },
                    { },
                    KafkaPublishNarmestelederrelasjon(producer),
                )
                val submit = SubmitNarmestelederrelasjonUseCase(
                    { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
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
