package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.clearMocks
import io.mockk.mockk
import no.nav.syfo.createMockToken
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.FakeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RecordingRevocationPublisher
import no.nav.syfo.narmestelederrelasjon.application.RevocableNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.get.FakeNarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.get.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.stubTokenX
import java.util.UUID

internal const val TOKEN_X_ISSUER = "https://tokenx.example.com"
internal const val MASKINPORTEN_ISSUER = "https://test.maskinporten.no"

internal class NarmestelederrelasjonRouteFixture {
    val texasHttpClient = mockk<TexasHttpClient>()
    val repository = FakeNarmestelederrelasjonRepository()
    val organizationAccess = FakeOrganizationAccess(OrganizationNumber("123456789"))
    val organization = FakeNarmestelederrelasjonOrganization()
    val activeSykmeldingLookup = ActiveSykmeldingLookup { _, _ -> true }
    val published = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
    val revokeNarmestelederrelasjon = RevokeNarmestelederrelasjonUseCase(
        repository,
        organizationAccess,
        RecordingRevocationPublisher { command -> published.add(command) },
    )
    val getNarmestelederrelasjon = GetNarmestelederrelasjonUseCase(
        repository,
        organizationAccess,
        activeSykmeldingLookup,
        organization,
    )
    val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val employeeIdent = "12345678901"

    fun lookup() = NarmestelederrelasjonLookup(
        id = id,
        organizationNumber = OrganizationNumber("123456789"),
        employeeIdent = PersonalIdentificationNumber(employeeIdent),
        employeeFirstName = "Employee",
        employeeMiddleName = null,
        employeeLastName = "Person",
        isActive = true,
    )

    fun revocableLookup(isActive: Boolean = true) = RevocableNarmestelederrelasjon(
        id = id,
        employeeIdent = PersonalIdentificationNumber(employeeIdent),
        managerIdent = PersonalIdentificationNumber("10987654321"),
        organizationNumber = OrganizationNumber("123456789"),
        isActive = isActive,
    )

    fun withTestApplication(test: suspend ApplicationTestBuilder.() -> Unit) = testRouteApplication(
        routes = { internalApiV1 { registerNarmestelederrelasjonApi(getNarmestelederrelasjon, revokeNarmestelederrelasjon, texasHttpClient) } },
        block = test,
    )

    fun token(issuer: String = TOKEN_X_ISSUER) = createMockToken(employeeIdent, issuer = issuer)
    fun path(id: String) = NARMESTELEDERRELASJON_API_PATH.replace("{id}", id)

    fun reset() {
        published.clear()
        clearMocks(texasHttpClient)
        texasHttpClient.stubTokenX(pid = employeeIdent)
        repository.reset()
        repository.lookupById = { requestedId -> lookup().also { check(requestedId == id) { "Unexpected id $requestedId" } } }
        repository.revocableById = { requestedId -> revocableLookup().also { check(requestedId == id) { "Unexpected id $requestedId" } } }
        organizationAccess.reset()
        organization.reset()
    }
}

internal fun replaceTimestamp(body: String): String = body.replace(Regex("""("timestamp":")[^"]+(")"""), "$1<dynamic>$2")
