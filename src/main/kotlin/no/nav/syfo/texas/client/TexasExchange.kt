package no.nav.syfo.texas.client

import com.fasterxml.jackson.annotation.JsonProperty

data class TexasExchangeRequest(
    @get:JsonProperty("identity_provider")
    val identityProvider: String,
    val target: String,
    @get:JsonProperty("user_token")
    val userToken: String,
)

data class TexasResponse(
    @get:JsonProperty("access_token")
    val accessToken: String,
    @get:JsonProperty("expires_in")
    val expiresIn: Long,
    @get:JsonProperty("token_type")
    val tokenType: String,
)

data class TexasTokenRequest(
    @get:JsonProperty("identity_provider")
    val identityProvider: String,
    val target: String,
)
