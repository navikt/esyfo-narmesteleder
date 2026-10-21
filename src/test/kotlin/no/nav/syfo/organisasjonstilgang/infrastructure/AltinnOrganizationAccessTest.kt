package no.nav.syfo.organisasjonstilgang.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregCache
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.ereg.Organisasjon
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AuthorizationDecision
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.User
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult

class AltinnOrganizationAccessTest :
    FunSpec({
        context("personnel manager") {
            listOf(
                UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("is_error"))),
                UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException()),
                UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IllegalStateException()),
            ).forEach { upstreamFailure ->
                test("returns Unavailable without granting or denying access for ${upstreamFailure.upstream} ${upstreamFailure.stage} ${upstreamFailure.status}") {
                    val fixture = AccessFixture()
                    fixture.altinn.setFailure(upstreamFailure)
                    val result = fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG))
                    result shouldBe OrganizationAccessResult.Unavailable(upstreamFailure)
                    result.shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs upstreamFailure
                    fixture.pdp.requestedOrganizations shouldContainExactly emptyList()
                }
            }
            test("is granted with the narmesteleder resource for the organization") {
                val fixture = AccessFixture()
                fixture.altinn.addAccess(PERSON_IDENT, REQUESTED_ORG)
                val accessPolicy = fixture.altinn.accessPolicy.single()
                fixture.altinn.accessPolicy[0] = accessPolicy.copy(
                    altinnTilgangerResponse = accessPolicy.altinnTilgangerResponse.copy(
                        hierarki = accessPolicy.altinnTilgangerResponse.hierarki.map { it.copy(navn = "  Test Org  ") },
                    ),
                )

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted(organizationName = "Test Org")
            }

            test("is denied missing organization access without Altinn access to the organization") {
                val fixture = AccessFixture()

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)
            }

            test("is denied missing resource access without the narmesteleder resource") {
                val fixture = AccessFixture()
                fixture.altinn.addAccess(PERSON_IDENT, REQUESTED_ORG, altinn3tilgang = "some-other-resource")

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
            }
        }

        context("LPS system user") {
            listOf(
                UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 400, errors = listOf(UpstreamError("invalid_grant"))),
                UpstreamFailure(UpstreamName("altinn-authentication"), UpstreamFailureStage.TOKEN_EXCHANGE, 200, IllegalArgumentException()),
                UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException()),
                UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("indeterminate", detail = "unknown"))),
            ).forEach { failure ->
                listOf(REQUESTED_ORG, SYSTEM_USER_ORG).forEach { failingOrganization ->
                    test("${failure.upstream} ${failure.status} on $failingOrganization stops at the original failure") {
                        val fixture = AccessFixture()
                        fixture.pdp.failures[failingOrganization] = failure
                        fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)
                        fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                            .shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs failure
                        fixture.eregRequests shouldBe if (failingOrganization == REQUESTED_ORG) 0 else 1
                        fixture.pdp.requestedOrganizations shouldBe if (failingOrganization == REQUESTED_ORG) {
                            listOf(setOf(REQUESTED_ORG))
                        } else {
                            listOf(setOf(REQUESTED_ORG), setOf(SYSTEM_USER_ORG))
                        }
                    }
                }
            }

            test("missing organization preserves the bad request contract") {
                val fixture = AccessFixture()
                val failure = shouldThrow<ApiErrorException.BadRequestException> {
                    fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                }
                failure.message shouldBe "Unable to look up the organization"
                failure.type shouldBe ErrorType.ORGANIZATION_NOT_FOUND
            }

            test("Ereg failure stops the access check without granting or denying access") {
                val fixture = AccessFixture()
                fixture.pdp.permit(SYSTEM_USER_ORG)
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)
                val upstreamFailure = UpstreamFailure(
                    UpstreamName("ereg"),
                    UpstreamFailureStage.RESPONSE,
                    503,
                    IllegalStateException("private upstream body $REQUESTED_ORG"),
                )
                fixture.ereg.setFailure(upstreamFailure)
                val result = fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                result.shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs upstreamFailure
                fixture.pdp.requestedOrganizations shouldContainExactly listOf(setOf(REQUESTED_ORG))
            }

            test("is granted when PDP permits the requested organization") {
                val fixture = AccessFixture()
                fixture.pdp.permit(REQUESTED_ORG)

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted(organizationName = null)
                fixture.pdp.requestedOrganizations shouldContainExactly listOf(setOf(REQUESTED_ORG))
            }

            test("is granted through its own organization when it is in the requested organization's hierarchy") {
                val fixture = AccessFixture()
                fixture.pdp.permit(SYSTEM_USER_ORG)
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted(organizationName = null)
                fixture.pdp.requestedOrganizations shouldContainExactly
                    listOf(setOf(REQUESTED_ORG), setOf(SYSTEM_USER_ORG))
            }

            test("is rejected without checking its own organization when outside the hierarchy") {
                val fixture = AccessFixture()
                fixture.pdp.permit(SYSTEM_USER_ORG)
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(UNRELATED_ORG)

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
                fixture.pdp.requestedOrganizations shouldContainExactly listOf(setOf(REQUESTED_ORG))
            }

            test("is rejected when PDP denies both the requested and its own organization") {
                val fixture = AccessFixture()
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
            }
        }
    })

private const val PERSON_IDENT = "12345678901"
private const val REQUESTED_ORG = "910000001"
private const val SYSTEM_USER_ORG = "910000002"
private const val UNRELATED_ORG = "910000003"

private class AccessFixture {
    val altinn = FakeAltinnTilgangerClient().also { it.accessPolicy.clear() }
    val pdp = RecordingAltinnAuthorizationClient()
    val ereg = FakeEregClient().also { it.organisasjoner.clear() }
    var eregRequests = 0
    private val recordingEreg = object : EregClient {
        override suspend fun getOrganisasjon(orgnummer: String): UpstreamResult<Organisasjon?> {
            eregRequests++
            return ereg.getOrganisasjon(orgnummer)
        }
    }
    private val eregCache = mockk<EregCache> {
        every { getOrganisasjon(any()) } returns null
        every { putOrganisasjon(any(), any()) } just runs
    }
    val access = AltinnOrganizationAccess(
        AltinnTilgangerService(altinn),
        pdp,
        CachedEregClient(recordingEreg, eregCache),
    )
}

private class RecordingAltinnAuthorizationClient : AltinnAuthorizationClient {
    private val permitted = mutableSetOf<String>()
    val requestedOrganizations = mutableListOf<Set<String>>()
    val failures = mutableMapOf<String, UpstreamFailure>()

    fun permit(orgNumber: String) {
        permitted += orgNumber
    }

    override suspend fun authorize(user: User, orgNumberSet: Set<String>, resource: String): UpstreamResult<AuthorizationDecision> {
        requestedOrganizations += orgNumberSet
        failures[orgNumberSet.single()]?.let { return UpstreamResult.Failure(it) }
        val decision = if (orgNumberSet.all { it in permitted }) AuthorizationDecision.Permit else AuthorizationDecision.Deny
        return UpstreamResult.Success(decision)
    }
}

private fun personnelManager() = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonalIdentificationNumber(PERSON_IDENT),
    accessToken = AccessToken("token"),
)

private fun systemUser() = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = "system-user-id",
    systemUserOrganizationNumber = OrganizationNumber(SYSTEM_USER_ORG),
)

private fun organisationWithParent(parentOrgNumber: String) = Organisasjon(
    organisasjonsnummer = REQUESTED_ORG,
    inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = parentOrgNumber)),
)
