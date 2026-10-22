package no.nav.syfo.platform.auth.texas

import io.mockk.coEvery
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.texas.client.AuthorizationDetail
import no.nav.syfo.platform.auth.texas.client.OrganizationId
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.client.TexasIntrospectionResponse

val DefaultOrganization = OrganizationId(
    ID = "0192:999777555",
    authority = "some-authority",
)

private val TOKEN_X = requireNotNull(JwtIssuer.TOKEN_X.value)
private val MASKINPORTEN = requireNotNull(JwtIssuer.MASKINPORTEN.value)
private val AZURE_AD = requireNotNull(JwtIssuer.AZURE_AD.value)

/** Texas accepts a TokenX token for a citizen. */
fun TexasHttpClient.stubTokenX(
    pid: String? = "userIdentifier",
    acr: String? = "Level4",
    navident: String? = null,
) {
    coEvery { introspectToken(TOKEN_X, any()) } returns TexasIntrospectionResponse(
        active = true,
        pid = pid,
        acr = acr,
        NAVident = navident,
    )
}

/** Texas accepts a Maskinporten token issued to an Altinn system user. */
fun TexasHttpClient.stubMaskinportenSystemUser(
    systemUserOrganization: OrganizationId = DefaultOrganization,
    consumer: OrganizationId? = DefaultOrganization,
    supplier: OrganizationId? = null,
    scope: String? = MASKINPORTEN_NL_SCOPE,
    systemUserId: String = "some-user-id",
    systemId: String = "some-system-id",
) {
    coEvery { introspectToken(MASKINPORTEN, any()) } returns TexasIntrospectionResponse(
        active = true,
        consumer = consumer,
        supplier = supplier,
        scope = scope,
        authorizationDetails = listOf(
            AuthorizationDetail(
                type = "urn:altinn:systemuser",
                systemuserOrg = systemUserOrganization,
                systemuserId = listOf(systemUserId),
                systemId = systemId,
            ),
        ),
    )
}

/** Texas accepts an Azure AD token from the calling application [azp]. */
fun TexasHttpClient.stubAzureAd(azp: String) {
    coEvery { introspectToken(AZURE_AD, any()) } returns TexasIntrospectionResponse(active = true, azp = azp)
}

/** Texas reports the token as inactive. A null [provider] matches every identity provider. */
fun TexasHttpClient.stubInactive(provider: String? = null, azp: String? = null) {
    coEvery { introspectToken(if (provider == null) any() else eq(provider), any()) } returns
        TexasIntrospectionResponse(active = false, azp = azp)
}

/** Texas introspection throws [throwable]. A null [provider] matches every identity provider. */
fun TexasHttpClient.stubIntrospectionFailure(throwable: Throwable, provider: String? = null) {
    coEvery { introspectToken(if (provider == null) any() else eq(provider), any()) } throws throwable
}
