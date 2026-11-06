package no.nav.syfo.platform.auth.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class TexasSettings(
    val tokenIntrospectionEndpoint: String,
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val exchangeTargetIsAltinnTilganger: String,
    val azurePreAuthorizedApps: Set<String>,
)

fun altinnTilgangerExchangeTarget(clusterName: String) = "$clusterName:fager:arbeidsgiver-altinn-tilganger"

internal fun preAuthorizedAppsFromJson(configuredApps: String): Set<String> = jacksonObjectMapper()
    .readValue<List<AzureAdPreAuthorizedApp>>(configuredApps)
    .map(AzureAdPreAuthorizedApp::clientId)
    .toSet()

/** Returns null instead of throwing, so the caller can report the setting without its value. */
fun preAuthorizedAppsFromJsonOrNull(configuredApps: String): Set<String>? = runCatching { preAuthorizedAppsFromJson(configuredApps) }.getOrNull()

private data class AzureAdPreAuthorizedApp(
    val name: String,
    val clientId: String,
)
