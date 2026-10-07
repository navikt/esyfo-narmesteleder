package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.AaregClientException
import no.nav.syfo.integration.aareg.Arbeidsforholdoversikt
import no.nav.syfo.integration.aareg.Arbeidssted
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.Ident
import no.nav.syfo.integration.aareg.IdentType
import no.nav.syfo.integration.aareg.Opplysningspliktig
import no.nav.syfo.integration.aareg.OpplysningspliktigType
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult

class AaregEmploymentLookupTest :
    FunSpec({
        val personIdent = PersonIdent("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        test("returns NONE without employment") {
            AaregEmploymentLookup(StubEmploymentClient { AaregArbeidsforholdOversikt() })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.NONE
        }

        test("returns NOT_IN_ORGANIZATION for employment elsewhere") {
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview("910000002") })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.NOT_IN_ORGANIZATION
        }

        test("returns IN_ORGANIZATION and passes the person ident to the client") {
            val client = StubEmploymentClient { employmentOverview(organizationNumber.value) }
            AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.IN_ORGANIZATION
            client.requests shouldBe listOf(personIdent.value)
        }

        test("ignores employment entries without an organization number") {
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview(null) })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.NONE
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview(null, organizationNumber.value) })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.IN_ORGANIZATION
        }

        test("does not use the reporting organization's number as the workplace") {
            AaregEmploymentLookup(StubEmploymentClient { employmentOverview("910000002") })
                .findEmployment(personIdent, OrganizationNumber("910000003")) shouldBe EmploymentResult.NOT_IN_ORGANIZATION
        }

        test("preserves the upstream exception mapping") {
            val upstreamFailure = AaregClientException("Aareg unavailable", IllegalStateException("Unavailable"))
            val lookup = AaregEmploymentLookup(StubEmploymentClient { throw upstreamFailure })
            val failure = shouldThrow<ApiErrorException.InternalServerErrorException> {
                lookup.findEmployment(personIdent, organizationNumber)
            }
            failure.message shouldBe "Could not fetch employment status fra Aareg"
            failure.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
            failure.cause shouldBe upstreamFailure
        }
    })

private class StubEmploymentClient(private val result: () -> AaregArbeidsforholdOversikt) : AaregClient {
    val requests = mutableListOf<String>()
    override suspend fun getArbeidsforhold(personIdent: String): AaregArbeidsforholdOversikt {
        requests += personIdent
        return result()
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
