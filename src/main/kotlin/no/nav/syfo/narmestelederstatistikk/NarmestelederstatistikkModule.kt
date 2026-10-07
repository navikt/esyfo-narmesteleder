package no.nav.syfo.narmestelederstatistikk

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.narmestelederstatistikk.api.registerLinemanagerStatisticsApi
import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkUseCase
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.texas.client.TexasHttpClient
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModules

fun Application.narmestelederstatistikkModule() {
    koinModules(narmestelederstatistikkDependencies())
    val getNarmestelederstatistikk = get<GetNarmestelederstatistikkUseCase>()
    val texasHttpClient = get<TexasHttpClient>()
    routing {
        internalApiV1 {
            registerLinemanagerStatisticsApi(getNarmestelederstatistikk, texasHttpClient)
        }
    }
}
