package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

private val employeeIdent = PersonIdent("12345678901")
private val organizationNumber = OrganizationNumber("123456789")
private val manager = ManagerContactInput(PersonIdent("10987654321"), "Manager", " manager@example.test ", "+47 90 00 00 00")
private val systemUser = OrganizationAccessSubject.LpsSystemUser("system-id", organizationNumber)
private val personnelManager = OrganizationAccessSubject.PersonnelManager(PersonIdent("11223344556"), AccessToken("test-token"))

class SubmitNarmestelederrelasjonUseCaseTest :
    FunSpec({
        test("normalizes before access and passes the submitted employee last name and source to establish") {
            listOf(systemUser to RelationSource.LPS, personnelManager to RelationSource.PERSONNEL_MANAGER).forEach { (subject, source) ->
                val effects = mutableListOf<String>()
                var established: EstablishNarmestelederrelasjonCommand? = null
                val useCase = SubmitNarmestelederrelasjonUseCase(
                    OrganizationAccess { accessSubject, organization ->
                        effects += "access"
                        accessSubject shouldBe subject
                        organization shouldBe organizationNumber
                        OrganizationAccessResult.Granted(organizationName = null)
                    },
                    EstablishNarmestelederrelasjon {
                        effects += "establish"
                        established = it
                        EstablishNarmestelederrelasjonResult.Published(LastNameMatch.Exact(false))
                    },
                )
                useCase.execute(command(subject)) shouldBe SubmitNarmestelederrelasjonResult.Established(source)
                effects shouldBe listOf("access", "establish")
                established?.manager?.mobile?.value shouldBe "+4790000000"
                established?.manager?.email?.value shouldBe "manager@example.test"
                established?.employeeLastName shouldBe "Employee"
                established?.source shouldBe source
            }
        }

        test("maps upstream unavailable to its own result without wrapping it as an establish rejection") {
            val failure = UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IllegalStateException())
            val useCase = SubmitNarmestelederrelasjonUseCase(
                OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
                EstablishNarmestelederrelasjon { EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure) },
            )
            useCase.execute(command(systemUser)) shouldBe SubmitNarmestelederrelasjonResult.UpstreamUnavailable(failure)
        }

        test("invalid contact stops before access or establishment") {
            val effects = mutableListOf<String>()
            val useCase = recordingUseCase(effects)
            val result = useCase.execute(command(systemUser).copy(manager = manager.copy(mobile = "invalid")))
            (result is SubmitNarmestelederrelasjonResult.InvalidManagerContactDetails) shouldBe true
            effects shouldBe emptyList()
        }

        test("access denial stops before establishment") {
            val effects = mutableListOf<String>()
            val useCase = recordingUseCase(effects, OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED))
            useCase.execute(command(systemUser)) shouldBe
                SubmitNarmestelederrelasjonResult.AccessDenied(DenialReason.SYSTEM_USER_REJECTED, organizationNumber)
            effects shouldBe listOf("access")
        }
    })

private fun command(subject: OrganizationAccessSubject) = SubmitNarmestelederrelasjonCommand(employeeIdent, "Employee", organizationNumber, manager, subject)

private fun recordingUseCase(
    effects: MutableList<String>,
    accessResult: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
) = SubmitNarmestelederrelasjonUseCase(
    OrganizationAccess { _, _ ->
        effects += "access"
        accessResult
    },
    EstablishNarmestelederrelasjon {
        effects += "establish"
        EstablishNarmestelederrelasjonResult.Published(LastNameMatch.Exact(false))
    },
)
