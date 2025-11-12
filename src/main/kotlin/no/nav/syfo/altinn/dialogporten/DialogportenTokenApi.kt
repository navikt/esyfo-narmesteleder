package no.nav.syfo.altinn.dialogporten

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.altinn.dialogporten.client.IDialogportenClient
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerDialogportenTokenApi(
    texasHttpClient: TexasHttpClient,
    dialogportenClient: IDialogportenClient,
) {
    route("/dialogporten/token") {
        install(AddTokenIssuerPlugin)
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }
        get {
            call.respondText(dialogportenClient.getDialogportenToken())
        }
    }
}
