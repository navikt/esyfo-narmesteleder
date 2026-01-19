package no.nav.syfo.narmesteleder.api.v1

import createMockToken
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.FakesWrapper
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import prepareGetPersonResponse
import java.util.*

class LinemanagerRequirementRESTHandlerTest :
    FunSpec({
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

        test("Should update status on NlBehov through NarmestelederService") {
            servicesWrapper.pdlServiceSpyk.prepareGetPersonResponse(defaultManager)
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
                    match<UUID> { it == fixtureEntity.id },
                    match<BehovStatus> { it == BehovStatus.BEHOV_FULFILLED }
                )
            }
        }

        test("Should distribute new linemanager using NarmestelederKafkaService") {
            servicesWrapper.pdlServiceSpyk.prepareGetPersonResponse(defaultManager)
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
