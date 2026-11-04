package no.nav.syfo.platform.auth.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class AzureAdPreAuthorizedApp(
    val name: String,
    val clientId: String,
)

fun preAuthorizedAppsFromJson(configuredApps: String): Set<String> = jacksonObjectMapper()
    .readValue<List<AzureAdPreAuthorizedApp>>(configuredApps)
    .map(AzureAdPreAuthorizedApp::clientId)
    .toSet()
