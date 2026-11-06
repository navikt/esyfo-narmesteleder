package no.nav.syfo.platform.auth.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.syfo.platform.config.getEnvVar

data class TexasEnvironment(
    val tokenIntrospectionEndpoint: String,
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val exchangeTargetIsAltinnTilganger: String,
    val azurePreAuthorizedApps: Set<String>,
) {
    companion object {
        fun createFromEnvVars() = TexasEnvironment(
            tokenIntrospectionEndpoint = getEnvVar("NAIS_TOKEN_INTROSPECTION_ENDPOINT"),
            tokenExchangeEndpoint = getEnvVar("NAIS_TOKEN_EXCHANGE_ENDPOINT"),
            tokenEndpoint = getEnvVar("NAIS_TOKEN_ENDPOINT"),
            exchangeTargetIsAltinnTilganger = "${getEnvVar("NAIS_CLUSTER_NAME")}:fager:arbeidsgiver-altinn-tilganger",
            azurePreAuthorizedApps = preAuthorizedAppsFromJson(getEnvVar("AZURE_APP_PRE_AUTHORIZED_APPS")),
        )
    }
}

internal fun preAuthorizedAppsFromJson(configuredApps: String): Set<String> = jacksonObjectMapper()
    .readValue<List<AzureAdPreAuthorizedApp>>(configuredApps)
    .map(AzureAdPreAuthorizedApp::clientId)
    .toSet()

private data class AzureAdPreAuthorizedApp(
    val name: String,
    val clientId: String,
)
