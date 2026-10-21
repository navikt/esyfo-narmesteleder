package no.nav.syfo.platform.auth.texas

import no.nav.syfo.platform.config.getEnvVar

data class TexasEnvironment(
    val tokenIntrospectionEndpoint: String,
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val exchangeTargetIsAltinnTilganger: String,
    val azurePreAuthorizedApps: Set<String>,
) {
    companion object {
        fun createForLocal() = TexasEnvironment(
            tokenIntrospectionEndpoint = "http://localhost:3000/api/v1/introspect",
            tokenExchangeEndpoint = "http://localhost:3000/api/v1/token/exchange",
            tokenEndpoint = "http://localhost:3000/api/v1/token",
            exchangeTargetIsAltinnTilganger = "dev:teamsykefravr:istilgangskontroll",
            azurePreAuthorizedApps = setOf("syfo-budstikka"),
        )

        fun createFromEnvVars() = TexasEnvironment(
            tokenIntrospectionEndpoint = getEnvVar("NAIS_TOKEN_INTROSPECTION_ENDPOINT"),
            tokenExchangeEndpoint = getEnvVar("NAIS_TOKEN_EXCHANGE_ENDPOINT"),
            tokenEndpoint = getEnvVar("NAIS_TOKEN_ENDPOINT"),
            exchangeTargetIsAltinnTilganger = "${getEnvVar("NAIS_CLUSTER_NAME")}:fager:arbeidsgiver-altinn-tilganger",
            azurePreAuthorizedApps = preAuthorizedAppsFromJson(getEnvVar("AZURE_APP_PRE_AUTHORIZED_APPS")),
        )
    }
}
