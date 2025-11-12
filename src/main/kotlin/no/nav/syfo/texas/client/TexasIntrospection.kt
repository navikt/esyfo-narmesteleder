package no.nav.syfo.texas.client

import com.fasterxml.jackson.annotation.JsonProperty


data class TexasIntrospectionRequest(
    @get:JsonProperty("identity_provider")
    val identityProvider: String,
    val token: String,
)

data class OrganizationId(
    val authority: String,
    val ID: String,
)

data class AuthorizationDetail(
    val type: String,
    @get:JsonProperty("systemuser_org")
    val systemuserOrg: OrganizationId,
    @get:JsonProperty("systemuser_id")
    val systemuserId: List<String>,
    @get:JsonProperty("system_id")
    val systemId: String
)

@Suppress("ConstructorParameterNaming")
data class TexasIntrospectionResponse(
    val active: Boolean,
    val error: String? = null,
    val pid: String? = null,
    val acr: String? = null,
    val aud: String? = null,
    val azp: String? = null,
    val exp: Long? = null,
    val iat: Long? = null,
    val iss: String? = null,
    val jti: String? = null,
    val nbf: Long? = null,
    val sub: String? = null,
    val tid: String? = null,
    @get:JsonProperty("authorization_details")
    val authorizationDetails: List<AuthorizationDetail>? = null,
    val NAVident: String? = null,
    val consumer: OrganizationId? = null,
    val supplier: OrganizationId? = null,
    val scope: String? = null,
)

fun TexasIntrospectionResponse.isAltinnSystemUser() = this.authorizationDetails?.first()?.type == "urn:altinn:systemuser"

fun TexasIntrospectionResponse.getSystemUserOrganization(): String? {
    if (this.isAltinnSystemUser()) {
        return this.authorizationDetails?.first()?.systemuserOrg?.ID
    }
    return null
}

fun TexasIntrospectionResponse.getSystemUserId(): String? {
    if (this.isAltinnSystemUser()) {
        return this.authorizationDetails?.first()?.systemuserId?.firstOrNull()
    }
    return null
}
