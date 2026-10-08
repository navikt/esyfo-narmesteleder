package no.nav.syfo.narmestelederrelasjon.infrastructure

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
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmploymentLookupTest :
    FunSpec({
        val personIdent = PersonIdent("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        test("returns NONE without employment") {
            AaregEmploymentLookup(StubEmploymentClient { AaregArbeidsforholdOversikt() })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.None
        }

        test("returns NOT_IN_ORGANIZATION for employment elsewhere") {
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview("910000002") })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.NotInOrganization
        }

        test("returns IN_ORGANIZATION and passes the person ident to the client") {
            val client = StubEmploymentClient { employmentOverview(organizationNumber.value) }
            AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.InOrganization
            client.requests shouldBe listOf(personIdent.value)
        }

        test("ignores employment entries without an organization number") {
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview(null) })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.None
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview(null, organizationNumber.value) })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.InOrganization
        }

        test("does not use the reporting organization's number as the workplace") {
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview("910000002") })
                .findEmployment(personIdent, OrganizationNumber("910000003")) shouldBe EmploymentResult.NotInOrganization
        }

        test("returns unavailable and preserves the upstream failure") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val client = object : AaregClient {
                override suspend fun getArbeidsforhold(personIdent: String) = UpstreamResult.Failure(failure)

                override suspend fun getArbeidsforholdHistorikk(personIdent: String) = error("History lookup is not used by this adapter")
            }
            AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.Unavailable(failure)
        }
    })

private class StubEmploymentClient(private val result: () -> AaregArbeidsforholdOversikt) : AaregClient {
    val requests = mutableListOf<String>()
    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = error("History lookup is not used by this adapter")

    override suspend fun getArbeidsforhold(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> {
        requests += personIdent
        return UpstreamResult.Success(result())
    }
}

private fun employmentOverview(vararg organizationNumbers: String?) = AaregArbeidsforholdOversikt(
    organizationNumbers.map { organizationNumber ->
        Arbeidsforholdoversikt(
            arbeidssted = Arbeidssted(
                type = if (organizationNumber == null) ArbeidsstedType.Person else ArbeidsstedType.Underenhet,
                identer = listOf(
                    Ident(
                        type = if (organizationNumber == null) IdentType.FOLKEREGISTERIDENT else IdentType.ORGANISASJONSNUMMER,
                        ident = organizationNumber ?: "12345678901",
                        gjeldende = true,
                    ),
                ),
            ),
            opplysningspliktig = Opplysningspliktig(
                OpplysningspliktigType.Hovedenhet,
                listOf(Ident(IdentType.ORGANISASJONSNUMMER, "910000003", true)),
            ),
        )
    },
)
