package no.nav.syfo.application.texas

import no.nav.syfo.application.getEnvVar

data class TexasEnvironment(
    val tokenIntrospectionEndpoint: String,
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val exchangeTargetIsAltinnTilganger: String,
) {
    companion object {
        fun createForLocal() = TexasEnvironment(
            tokenIntrospectionEndpoint = "http://localhost:3000/api/v1/introspect",
            tokenExchangeEndpoint = "http://localhost:3000/api/v1/token/exchange",
            tokenEndpoint = "http://localhost:3000/api/v1/token",
            exchangeTargetIsAltinnTilganger = "dev:teamsykefravr:istilgangskontroll",
        )

        fun createFromEnvVars() =
            TexasEnvironment(
                tokenIntrospectionEndpoint = getEnvVar("NAIS_TOKEN_INTROSPECTION_ENDPOINT"),
                tokenExchangeEndpoint = getEnvVar("NAIS_TOKEN_EXCHANGE_ENDPOINT"),
                tokenEndpoint = getEnvVar("NAIS_TOKEN_ENDPOINT"),
                exchangeTargetIsAltinnTilganger = "${getEnvVar("NAIS_CLUSTER_NAME")}:fager:arbeidsgiver-altinn-tilganger"
            )
    }
}
