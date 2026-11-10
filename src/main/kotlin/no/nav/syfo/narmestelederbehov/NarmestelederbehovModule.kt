package no.nav.syfo.narmestelederbehov

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.narmestelederbehov.api.registerFulfillNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerGetNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerListNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.auth.texas.authenticateMaskinportenOrTokenX
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModules

fun Application.narmestelederbehovModule(settings: NarmestelederbehovSettings) {
    koinModules(narmestelederbehovDependencies(settings))
    val getNarmestelederbehov = get<GetNarmestelederbehovUseCase>()
    val fulfillNarmestelederbehov = get<FulfillNarmestelederbehovUseCase>()
    val listNarmestelederbehov = get<ListNarmestelederbehovUseCase>()
    routing {
        apiV1 {
            authenticateMaskinportenOrTokenX {
                registerGetNarmestelederbehovApi(getNarmestelederbehov = getNarmestelederbehov)
                registerFulfillNarmestelederbehovApi(fulfillNarmestelederbehov = fulfillNarmestelederbehov)
                registerListNarmestelederbehovApi(listNarmestelederbehov = listNarmestelederbehov)
            }
        }
    }
}
