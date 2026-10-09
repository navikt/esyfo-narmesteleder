package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.Arbeidsforholdoversikt
import no.nav.syfo.integration.aareg.Arbeidssted
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.Ident
import no.nav.syfo.integration.aareg.IdentType
import no.nav.syfo.integration.aareg.Opplysningspliktig
import no.nav.syfo.integration.aareg.OpplysningspliktigType
import no.nav.syfo.narmestelederbehov.application.MainOrganizationResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmployerMainOrganizationLookupTest :
    FunSpec({
        val employee = Employee(PersonIdent("12345678901"), OrganizationNumber("910000001"))

        test("returns the reporting organization of the employment in the employee's organization") {
            val client = StubClient {
                overview(
                    employment("910000002", "920000002"),
                    employment("910000001", "920000001"),
                )
            }

            AaregEmployerMainOrganizationLookup(client).findMainOrganization(employee) shouldBe MainOrganizationResult.Found("920000001")
            client.requests shouldBe listOf(employee.personIdent.value)
        }

        test("returns EmploymentMissing without employment in the organization") {
            AaregEmployerMainOrganizationLookup(StubClient { overview(employment("910000002", "920000002")) })
                .findMainOrganization(employee) shouldBe MainOrganizationResult.EmploymentMissing
            AaregEmployerMainOrganizationLookup(StubClient { AaregArbeidsforholdOversikt() })
                .findMainOrganization(employee) shouldBe MainOrganizationResult.EmploymentMissing
        }

        test("returns MainOrganizationMissing when the employment has no reporting organization number") {
            AaregEmployerMainOrganizationLookup(StubClient { overview(employment("910000001", null)) })
                .findMainOrganization(employee) shouldBe MainOrganizationResult.MainOrganizationMissing
        }

        test("returns Unavailable and preserves the upstream failure") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val client = object : AaregClient {
                override suspend fun getArbeidsforhold(personIdent: String) = UpstreamResult.Failure(failure)

                override suspend fun getArbeidsforholdHistorikk(personIdent: String) = error("History lookup is not used by this adapter")
            }

            AaregEmployerMainOrganizationLookup(client).findMainOrganization(employee) shouldBe MainOrganizationResult.Unavailable(failure)
        }
    })

private class StubClient(private val result: () -> AaregArbeidsforholdOversikt) : AaregClient {
    val requests = mutableListOf<String>()

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = error("History lookup is not used by this adapter")

    override suspend fun getArbeidsforhold(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> {
        requests += personIdent
        return UpstreamResult.Success(result())
    }
}

private fun overview(vararg employments: Arbeidsforholdoversikt) = AaregArbeidsforholdOversikt(employments.toList())

private fun employment(organizationNumber: String, reportingOrganizationNumber: String?) = Arbeidsforholdoversikt(
    arbeidssted = Arbeidssted(
        type = ArbeidsstedType.Underenhet,
        identer = listOf(Ident(IdentType.ORGANISASJONSNUMMER, organizationNumber, true)),
    ),
    opplysningspliktig = Opplysningspliktig(
        OpplysningspliktigType.Hovedenhet,
        reportingOrganizationNumber?.let { listOf(Ident(IdentType.ORGANISASJONSNUMMER, it, true)) } ?: emptyList(),
    ),
)
