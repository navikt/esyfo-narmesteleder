package no.nav.syfo.narmesteleder.api.v1

import createMockToken
import faker
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import java.util.*
import kotlinx.coroutines.Dispatchers
import net.datafaker.providers.base.Name
import no.nav.syfo.FakesWrapper
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class LinemanagerRequirementRESTHandlerTest : FunSpec({
    val servicesWrapper = FakesWrapper(Dispatchers.Default)
    // Map<Personnummer, List<Pair<underenhet, hovedenhet>>>
    val defaultManagerFnr = FakeAaregClient.defaultArbeidsforhold.keys.first()
    val defaultEmployeeFnr = FakeAaregClient.defaultArbeidsforhold.keys.last()
    val arbeidsforholdEmployeeAareg = FakeAaregClient.defaultArbeidsforhold[defaultEmployeeFnr]!!.first()
    val arbeidsforholdManagerAareg = FakeAaregClient.defaultArbeidsforhold[defaultManagerFnr]!!.first()

    val defaultManager = Manager(
        nationalIdentificationNumber = defaultManagerFnr,
        mobile = "99999999",
        email = "mail@manager.no",
        lastName = "Jensen",
    )
    val defaultRequirement = NarmestelederBehovEntity(
        id = UUID.randomUUID(),
        orgnummer = arbeidsforholdEmployeeAareg.first,
        hovedenhetOrgnummer = arbeidsforholdEmployeeAareg.second,
        sykmeldtFnr = defaultEmployeeFnr,
        narmestelederFnr = "123456789",
        behovReason = BehovReason.DEAKTIVERT_LEDER,
        avbruttNarmesteLederId = UUID.randomUUID(),
    )

    beforeTest {
        clearAllMocks()
        servicesWrapper.fakeDbSpyk.clear()
    }

    fun prepareGetPersonResponse(name: Name) {
        coEvery {
            servicesWrapper.pdlServiceSpyk.getPersonFor(eq(defaultManager.nationalIdentificationNumber))
        } returns Person(
            name = Navn(
                fornavn = name.firstName(),
                mellomnavn = "",
                etternavn = defaultManager.lastName,
            ),
            nationalIdentificationNumber = defaultManager.nationalIdentificationNumber,
        )
    }

    test("Should update status on NlBehov through NarmestelederService") {
        prepareGetPersonResponse(faker.name())
        val handler = servicesWrapper.lnReqRESTHandlerSpyk
        val db = servicesWrapper.fakeDbSpyk
        val fixtureEntity = db.insertNlBehov(defaultRequirement)

        val principal = SystemPrincipal(
            ident = "0192:${arbeidsforholdManagerAareg.first}",
            token = createMockToken(
                ident = "0192:${arbeidsforholdManagerAareg.first}",
            ),
            systemOwner = "0192:systemOwner",
            systemUserId = "systemUserId",
        )

        handler.handleUpdatedRequirement(
            requirementId = fixtureEntity.id!!,
            manager = defaultManager,
            principal = principal,
        )
        coVerify(exactly = 1) {
            servicesWrapper.narmestelederServiceSpyk.updateNlBehov(
                match { it == fixtureEntity.id },
                match { it == BehovStatus.BEHOV_FULFILLED })
        }
    }

    test("Should distribute new linemanager using NarmestelederKafkaService") {
        prepareGetPersonResponse(faker.name())
        val handler = servicesWrapper.lnReqRESTHandlerSpyk
        val db = servicesWrapper.fakeDbSpyk
        val fixtureEntity = db.insertNlBehov(defaultRequirement)

        val principal = SystemPrincipal(
            ident = "0192:${arbeidsforholdManagerAareg.first}",
            token = createMockToken(
                ident = "0192:${arbeidsforholdManagerAareg.first}",
            ),
            systemOwner = "0192:systemOwner",
            systemUserId = "systemUserId",
        )

        handler.handleUpdatedRequirement(
            requirementId = fixtureEntity.id!!,
            manager = defaultManager,
            principal = principal,
        )
        coVerify(exactly = 1) {
            servicesWrapper.narmestelederKafkaServiceSpyk.sendNarmesteLederRelasjon(
                match {
                    it.employeeIdentificationNumber == fixtureEntity.sykmeldtFnr &&
                        it.orgNumber == fixtureEntity.orgnummer
                },
                any(),
                match { it == NlResponseSource.LPS }
            )
        }
    }
})
