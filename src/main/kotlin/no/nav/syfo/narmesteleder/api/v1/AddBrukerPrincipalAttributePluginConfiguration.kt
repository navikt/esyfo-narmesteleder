package no.nav.syfo.narmesteleder.api.v1

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.principal
import io.ktor.util.AttributeKey
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.exceptions.UnauthorizedException
import no.nav.syfo.texas.client.TexasHttpClient

class AddBrukerPrincipalAttributePluginConfiguration(
    var texasHttpClient: TexasHttpClient? = null,
)

val BRUKER_PRINCIPAL = AttributeKey<BrukerPrincipal>("brukerPrincipal")

val AddBrukerPrincipalPlugin = createRouteScopedPlugin(
    name = "AddSykmeldtBrukerFnrAttributePlugin",
    createConfiguration = ::AddBrukerPrincipalAttributePluginConfiguration,
) {
    pluginConfig.apply {
        onCall { call ->
            val innloggetBruker = call.principal<BrukerPrincipal>()
                ?: throw UnauthorizedException("No user principal found in request")

            call.attributes[BRUKER_PRINCIPAL] = innloggetBruker
        }
    }
}
