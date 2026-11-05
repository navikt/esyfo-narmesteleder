package no.nav.syfo.bootstrap

import no.nav.syfo.platform.http.httpClientSSE
import no.nav.syfo.platform.scheduling.LeaderChangeSSEListener
import no.nav.syfo.platform.scheduling.LeaderElection
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import org.koin.dsl.module

internal fun leaderElectionModule(settings: LeaderElectionSettings, isLocalEnv: Boolean) = module {
    single {
        LeaderChangeSSEListener(
            sseHttpClient = httpClientSSE(),
            electorSseUrl = settings.electorSseUrl,
            isLocalEnv = isLocalEnv,
        )
    }
    single { LeaderElection(httpClient = get(), electorPath = settings.electorPath, isLocal = isLocalEnv) }
}
