package no.nav.syfo.narmesteleder.api.v1

import createMockToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.FakesWrapper
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import prepareGetPersonResponse
import java.time.Instant
import java.util.UUID

class LinemanagerRequirementRESTHandlerTest :
    DescribeSpec({
        val fakeAaregClient = FakeAaregClient()
        val servicesWrapper = FakesWrapper(Dispatchers.Default)
        // Map<Personnummer, List<Pair<underenhet, hovedenhet>>>
        val defaultManagerFnr = fakeAaregClient.arbeidsForholdForIdent.keys.first()
        val defaultEmployeeFnr = fakeAaregClient.arbeidsForholdForIdent.keys.last()
        val arbeidsforholdEmployeeAareg = fakeAaregClient.arbeidsForholdForIdent[defaultEmployeeFnr]!!.first()
        val arbeidsforholdManagerAareg = fakeAaregClient.arbeidsForholdForIdent[defaultManagerFnr]!!.first()

        val defaultManager = Manager(
            nationalIdentificationNumber = PersonalIdentificationNumber(defaultManagerFnr),
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
            clearAllMocks(currentThreadOnly = true)
            servicesWrapper.fakeDbSpyk.clear()

            coEvery {
                servicesWrapper.pdlCacheMock.getPerson(any())
            } returns null
        }

        describe("put") {
            it("Should update status on NlBehov through NarmestelederService") {
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
                val context =
                    "operation=put thepath, principalType=${principal::class.simpleName}"

                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = defaultManager,
                    principal = principal,
                    context = context,
                )
                coVerify(exactly = 1) {
                    servicesWrapper.narmestelederServiceSpyk.updateNlBehov(
                        match<UUID> { it == fixtureEntity.id },
                        match<BehovStatus> { it == BehovStatus.BEHOV_FULFILLED }
                    )
                }
            }

            it("Should distribute new linemanager using NarmestelederKafkaService") {
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
                val context =
                    "operation=put thepath, principalType=${principal::class.simpleName}"

                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = defaultManager,
                    principal = principal,
                    context = context,
                )
                coVerify(exactly = 1) {
                    servicesWrapper.narmestelederKafkaServiceSpyk.sendNarmesteLederRelasjon(
                        match {
                            it.employeeIdentificationNumber.value == fixtureEntity.sykmeldtFnr &&
                                it.orgNumber.value == fixtureEntity.orgnummer
                        },
                        any(),
                        match { it == NlResponseSource.LPS }
                    )
                }
            }
        }

        describe("get") {
            it("Should preserve cancellation from organization access validation") {
                val requirementId = UUID.randomUUID()
                val orgNumber = OrganizationNumber("123456789")
                val principal = SystemPrincipal(
                    ident = "0192:${arbeidsforholdManagerAareg.first}",
                    token = createMockToken(
                        ident = "0192:${arbeidsforholdManagerAareg.first}",
                    ),
                    systemOwner = "0192:systemOwner",
                    systemUserId = "systemUserId",
                )
                coEvery {
                    servicesWrapper.narmestelederServiceSpyk.getLinemanagerRequirementReadById(requirementId)
                } returns LinemanagerRequirementRead(
                    id = requirementId,
                    employeeIdentificationNumber = PersonalIdentificationNumber(defaultEmployeeFnr),
                    orgNumber = orgNumber,
                    mainOrgNumber = OrganizationNumber("987654321"),
                    name = Name(firstName = "Test", lastName = "Person", middleName = null),
                    created = Instant.EPOCH,
                    updated = Instant.EPOCH,
                    status = LineManagerRequirementStatus.CREATED,
                    revokedBy = null,
                )

                coEvery {
                    servicesWrapper.validationServiceSpyk.validatePrincipalAccessToOrgnumber(any(), orgNumber)
                } throws CancellationException("Request cancelled")

                shouldThrow<CancellationException> {
                    servicesWrapper.lnReqRESTHandlerSpyk.handleGetLinemanagerRequirement(
                        requirementId = requirementId,
                        principal = principal,
                    )
                }
            }
        }
    })
