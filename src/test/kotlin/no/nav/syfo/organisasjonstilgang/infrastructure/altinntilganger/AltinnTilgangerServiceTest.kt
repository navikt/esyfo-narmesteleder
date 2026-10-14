package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganization
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsResult
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class AltinnTilgangerServiceTest :
    FunSpec({
        val altinnTilgangerClient = StubAltinnTilgangerClient()
        val altinnTilgangerService = AltinnTilgangerService(altinnTilgangerClient)

        beforeTest {
            altinnTilgangerClient.result = UpstreamResult.Success(altinnTilgangerResponse())
        }

        context("find") {
            val userPrincipal = UserPrincipal("12345678910", "token")
            val subject = OrganizationAccessSubject.PersonnelManager(PersonIdent(userPrincipal.ident), AccessToken(userPrincipal.token))

            test("should return Success null when the organization is absent") {
                altinnTilgangerService.getAltinnTilgangForOrgnr(userPrincipal, "999999999") shouldBe UpstreamResult.Success(null)
                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
            }

            test("should return unavailable when the upstream request fails") {
                val failure = UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.REQUEST, null, IllegalStateException("Synthetic failure"))
                altinnTilgangerClient.result = UpstreamResult.Failure(failure)

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Unavailable(failure)
                altinnTilgangerService.getAltinnTilgangForOrgnr(userPrincipal, "999999999") shouldBe UpstreamResult.Failure(failure)
            }

            test("should pass the personnel manager identity and access token to the client") {
                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
                altinnTilgangerClient.principal shouldBe userPrincipal
            }

            test("should propagate the client isError failure to both callers") {
                val failure = UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("is_error")))
                altinnTilgangerClient.result = UpstreamResult.Failure(failure)

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Unavailable(failure)
                altinnTilgangerService.getAltinnTilgangForOrgnr(userPrincipal, "999999999") shouldBe UpstreamResult.Failure(failure)
            }

            test("should keep parent as context when only child has narmesteleder access to document OR semantics") {
                val childWithAccess = altinnTilgang(
                    orgnr = "222222222",
                    altinn3Tilganger = setOf(OPPGI_NARMESTELEDER_RESOURCE),
                )
                val parentWithoutAccess = altinnTilgang(
                    orgnr = "111111111",
                    underenheter = listOf(childWithAccess),
                )

                altinnTilgangerClient.result = UpstreamResult.Success(altinnTilgangerResponse(parentWithoutAccess))
                altinnTilgangerService.getAltinnTilgangForOrgnr(userPrincipal, "222222222") shouldBe UpstreamResult.Success(childWithAccess)

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(
                    listOf(
                        AccessibleOrganization(
                            organizationNumber = "111111111",
                            name = "Org 111111111",
                            subOrganizations = listOf(
                                AccessibleOrganization(
                                    organizationNumber = "222222222",
                                    name = "Org 222222222",
                                    subOrganizations = emptyList(),
                                ),
                            ),
                        ),
                    ),
                )
            }

            test("should keep parent with access while filtering out child without access") {
                val childWithoutAccess = altinnTilgang(orgnr = "555555555")
                val parentWithAccess = altinnTilgang(
                    orgnr = "444444444",
                    altinn3Tilganger = setOf(OPPGI_NARMESTELEDER_RESOURCE),
                    underenheter = listOf(childWithoutAccess),
                )

                altinnTilgangerClient.result = UpstreamResult.Success(altinnTilgangerResponse(parentWithAccess))

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(
                    listOf(
                        AccessibleOrganization(
                            organizationNumber = "444444444",
                            name = "Org 444444444",
                            subOrganizations = emptyList(),
                        ),
                    ),
                )
            }

            test("should return empty list when neither parent nor children have narmesteleder access") {
                val childWithoutAccess = altinnTilgang(orgnr = "777777777")
                val parentWithoutAccess = altinnTilgang(
                    orgnr = "666666666",
                    underenheter = listOf(childWithoutAccess),
                )

                altinnTilgangerClient.result = UpstreamResult.Success(altinnTilgangerResponse(parentWithoutAccess))

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
            }
        }
    })

private fun altinnTilgang(
    orgnr: String,
    altinn3Tilganger: Set<String> = emptySet(),
    underenheter: List<AltinnTilgang> = emptyList(),
    navn: String = "Org $orgnr",
) = AltinnTilgang(
    orgnr = orgnr,
    altinn3Tilganger = altinn3Tilganger,
    underenheter = underenheter,
    navn = navn,
    organisasjonsform = "BEDR",
)

private fun altinnTilgangerResponse(vararg hierarki: AltinnTilgang) = AltinnTilgangerResponse(
    isError = false,
    hierarki = hierarki.toList(),
    orgNrTilTilganger = emptyMap(),
    tilgangTilOrgNr = emptyMap(),
)

private class StubAltinnTilgangerClient : AltinnTilgangerClient {
    var result: UpstreamResult<AltinnTilgangerResponse> = UpstreamResult.Success(altinnTilgangerResponse())
    var principal: UserPrincipal? = null
    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): UpstreamResult<AltinnTilgangerResponse> {
        principal = bruker
        return result
    }
}
