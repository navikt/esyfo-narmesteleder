package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.Ident
import no.nav.syfo.integration.aareg.IdentType
import no.nav.syfo.integration.aareg.StubAaregClient
import no.nav.syfo.integration.aareg.aaregEmployment
import no.nav.syfo.integration.aareg.aaregOverview
import no.nav.syfo.integration.aareg.organizationIdent
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmploymentLookupTest :
    FunSpec({
        val personIdent = PersonalIdentificationNumber("12345678901")
        val organizationNumber = OrganizationNumber("910000001")

        test("returns NONE without employment") {
            AaregEmploymentLookup(employmentClient { AaregArbeidsforholdOversikt() })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.None
        }

        test("returns NOT_IN_ORGANIZATION for employment elsewhere") {
            AaregEmploymentLookup(employmentClient { employmentOverview("910000002") })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.NotInOrganization
        }

        test("returns IN_ORGANIZATION and passes the person ident to the client") {
            val client = employmentClient { employmentOverview(organizationNumber.value) }
            AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.InOrganization
            client.requests shouldBe listOf(personIdent.value)
        }

        test("ignores employment entries without an organization number") {
            AaregEmploymentLookup(employmentClient { employmentOverview(null) })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.None
            AaregEmploymentLookup(employmentClient { employmentOverview(null, organizationNumber.value) })
                .findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.InOrganization
        }

        test("does not use the reporting organization's number as the workplace") {
            AaregEmploymentLookup(employmentClient { employmentOverview("910000002") })
                .findEmployment(personIdent, OrganizationNumber("910000003")) shouldBe EmploymentResult.NotInOrganization
        }

        test("returns unavailable and preserves the upstream failure") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val client = StubAaregClient(current = { UpstreamResult.Failure(failure) })
            AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.Unavailable(failure)
        }

        test("returns NONE when AAREG does not know the person ident") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 404, IllegalStateException())
            val client = StubAaregClient(current = { UpstreamResult.Failure(failure) })
            AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.None
        }

        test("returns unavailable for other AAREG client errors") {
            listOf(400, 403).forEach { status ->
                val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, status, IllegalStateException())
                val client = StubAaregClient(current = { UpstreamResult.Failure(failure) })
                AaregEmploymentLookup(client).findEmployment(personIdent, organizationNumber) shouldBe EmploymentResult.Unavailable(failure)
            }
        }
    })

private fun employmentClient(result: () -> AaregArbeidsforholdOversikt) = StubAaregClient(current = { UpstreamResult.Success(result()) })

private fun employmentOverview(vararg organizationNumbers: String?) = aaregOverview(
    *organizationNumbers.map { organizationNumber ->
        if (organizationNumber == null) {
            aaregEmployment(
                workplaceType = ArbeidsstedType.Person,
                workplaceIdents = listOf(Ident(IdentType.FOLKEREGISTERIDENT, "12345678901", true)),
            )
        } else {
            aaregEmployment(workplaceIdents = listOf(organizationIdent(organizationNumber)))
        }
    }.toTypedArray(),
)
