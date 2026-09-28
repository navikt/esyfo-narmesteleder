package no.nav.syfo.narmestelederrelasjon.api

import DefaultOrganization
import createMockToken
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RevocableNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.AuthorizationDetail
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.util.UUID

internal const val TOKEN_X_ISSUER = "https://tokenx.example.com"
internal const val MASKINPORTEN_ISSUER = "https://test.maskinporten.no"

internal class NarmestelederrelasjonRouteFixture {
    val texasHttpClient = mockk<TexasHttpClient>()
    val repository = mockk<NarmestelederrelasjonRepository>()
    val organizationAccess = mockk<OrganizationAccess>()
    val organization = mockk<NarmestelederrelasjonOrganization>()
    val activeSykmeldingLookup = ActiveSykmeldingLookup { _, _ -> true }
    val published = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
    val revokeNarmestelederrelasjon = RevokeNarmestelederrelasjonUseCase(
        repository,
        organizationAccess,
    ) { command -> published.add(command) }
    val getNarmestelederrelasjon = GetNarmestelederrelasjonUseCase(
        repository,
        organizationAccess,
        activeSykmeldingLookup,
        organization,
    )
    val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val employeeIdent = "12345678901"

    fun lookup() = NarmestelederrelasjonLookup(
        id = id,
        organizationNumber = OrganizationNumber("123456789"),
        employeeIdent = PersonIdent(employeeIdent),
        employeeFirstName = "Employee",
        employeeMiddleName = null,
        employeeLastName = "Person",
        isActive = true,
    )

    fun revocableLookup(isActive: Boolean = true) = RevocableNarmestelederrelasjon(
        id = id,
        employeeIdent = PersonIdent(employeeIdent),
        managerIdent = PersonIdent("10987654321"),
        organizationNumber = OrganizationNumber("123456789"),
        isActive = isActive,
    )

    fun withTestApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                installContentNegotiation()
                installStatusPages()
                routing {
                    route(INTERNAL_API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerNarmestelederrelasjonApi(getNarmestelederrelasjon, revokeNarmestelederrelasjon, texasHttpClient)
                    }
                }
            }
            test()
        }
    }

    fun token(issuer: String = TOKEN_X_ISSUER) = createMockToken(employeeIdent, issuer = issuer)
    fun path(id: String) = NARMESTELEDERRELASJON_API_PATH.replace("{id}", id)

    fun introspectMaskinporten() {
        coEvery { texasHttpClient.introspectToken("maskinporten", any()) } returns TexasIntrospectionResponse(
            active = true,
            scope = MASKINPORTEN_NL_SCOPE,
            consumer = DefaultOrganization,
            authorizationDetails = listOf(
                AuthorizationDetail(
                    type = "urn:altinn:systemuser",
                    systemuserOrg = DefaultOrganization,
                    systemuserId = listOf("some-user-id"),
                    systemId = "some-system-id",
                ),
            ),
        )
    }

    fun reset() {
        published.clear()
        clearMocks(texasHttpClient, repository, organizationAccess, organization, answers = false)
        coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
            TexasIntrospectionResponse(active = true, acr = "Level4", pid = employeeIdent)
        coEvery { repository.findById(id) } returns lookup()
        coEvery { repository.findRevocableById(id) } returns revocableLookup()
        coEvery { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) } returns OrganizationAccessResult.Granted
        coEvery { organization.findName(OrganizationNumber("123456789")) } returns "Organization"
    }
}

internal fun replaceTimestamp(body: String): String = body.replace(Regex("""("timestamp":")[^"]+(")"""), "$1<dynamic>$2")
