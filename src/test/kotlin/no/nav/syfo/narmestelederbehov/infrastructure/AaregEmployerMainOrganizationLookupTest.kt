package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.Arbeidsforholdoversikt
import no.nav.syfo.integration.aareg.StubAaregClient
import no.nav.syfo.integration.aareg.aaregEmployment
import no.nav.syfo.integration.aareg.aaregOverview
import no.nav.syfo.integration.aareg.organizationIdent
import no.nav.syfo.narmestelederbehov.application.MainOrganizationResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmployerMainOrganizationLookupTest :
    FunSpec({
        val employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("910000001"))

        test("returns the reporting organization of the employment in the employee's organization") {
            val client = stubClient {
                overview(
                    employment("910000002", "920000002"),
                    employment("910000001", "920000001"),
                )
            }

            AaregEmployerMainOrganizationLookup(client).findMainOrganization(employee) shouldBe MainOrganizationResult.Found("920000001")
            client.requests shouldBe listOf(employee.personIdent.value)
        }

        test("returns EmploymentMissing without employment in the organization") {
            AaregEmployerMainOrganizationLookup(stubClient { overview(employment("910000002", "920000002")) })
                .findMainOrganization(employee) shouldBe MainOrganizationResult.EmploymentMissing
            AaregEmployerMainOrganizationLookup(stubClient { AaregArbeidsforholdOversikt() })
                .findMainOrganization(employee) shouldBe MainOrganizationResult.EmploymentMissing
        }

        test("returns MainOrganizationMissing when the employment has no reporting organization number") {
            AaregEmployerMainOrganizationLookup(stubClient { overview(employment("910000001", null)) })
                .findMainOrganization(employee) shouldBe MainOrganizationResult.MainOrganizationMissing
        }

        test("returns Unavailable and preserves the upstream failure") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val client = StubAaregClient(current = { UpstreamResult.Failure(failure) })

            AaregEmployerMainOrganizationLookup(client).findMainOrganization(employee) shouldBe MainOrganizationResult.Unavailable(failure)
        }
    })

private fun stubClient(result: () -> AaregArbeidsforholdOversikt) = StubAaregClient(current = { UpstreamResult.Success(result()) })

private fun overview(vararg employments: Arbeidsforholdoversikt) = aaregOverview(*employments)

private fun employment(organizationNumber: String, reportingOrganizationNumber: String?) = aaregEmployment(
    workplaceIdents = listOf(organizationIdent(organizationNumber)),
    reportingOrganizationNumber = reportingOrganizationNumber,
)
