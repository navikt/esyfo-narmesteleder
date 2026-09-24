package no.nav.syfo.organisasjonstilgang.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.client.DecisionResult
import no.nav.syfo.altinn.pdp.client.PdpClient
import no.nav.syfo.altinn.pdp.client.PdpResponse
import no.nav.syfo.altinn.pdp.client.User
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class AltinnOrganizationAccessTest :
    FunSpec({
        context("personnel manager") {
            test("is granted with the narmesteleder resource for the organization") {
                val fixture = AccessFixture()
                fixture.altinn.addAccess(PERSON_IDENT, REQUESTED_ORG)

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted
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
            test("is granted when PDP permits the requested organization") {
                val fixture = AccessFixture()
                fixture.pdp.permit(REQUESTED_ORG)

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted
                fixture.pdp.requestedOrganizations shouldContainExactly listOf(setOf(REQUESTED_ORG))
            }

            test("is granted through its own organization when it is in the requested organization's hierarchy") {
                val fixture = AccessFixture()
                fixture.pdp.permit(SYSTEM_USER_ORG)
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted
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
    val pdp = RecordingPdpClient()
    val ereg = FakeEregClient().also { it.organisasjoner.clear() }
    private val eregCache = mockk<EregCache> {
        every { getOrganisasjon(any()) } returns null
        every { putOrganisasjon(any(), any()) } just runs
    }
    val access = AltinnOrganizationAccess(
        AltinnTilgangerService(altinn),
        PdpService(pdp),
        EregService(ereg, eregCache),
    )
}

private class RecordingPdpClient : PdpClient {
    private val permitted = mutableSetOf<String>()
    val requestedOrganizations = mutableListOf<Set<String>>()

    fun permit(orgNumber: String) {
        permitted += orgNumber
    }

    override suspend fun authorize(user: User, orgNumberSet: Set<String>, resource: String): PdpResponse {
        requestedOrganizations += orgNumberSet
        val decision = if (orgNumberSet.all { it in permitted }) Decision.Permit else Decision.Deny
        return PdpResponse(listOf(DecisionResult(decision)))
    }
}

private fun personnelManager() = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonIdent(PERSON_IDENT),
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
