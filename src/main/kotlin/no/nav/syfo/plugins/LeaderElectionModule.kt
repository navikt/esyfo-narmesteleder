package no.nav.syfo.plugins

import no.nav.syfo.platform.http.httpClientSSE
import no.nav.syfo.platform.scheduling.LeaderChangeSSEListener
import no.nav.syfo.platform.scheduling.LeaderElection
import org.koin.dsl.module

internal fun leaderElectionModule(isLocalEnv: Boolean) = module {
    single {
        LeaderChangeSSEListener(
            sseHttpClient = httpClientSSE(),
            electorSseUrl = env().otherProperties.electorSSEUrl,
            isLocalEnv = isLocalEnv,
        )
    }
    single { LeaderElection(httpClient = get(), electorPath = env().otherProperties.electorPath, isLocal = isLocalEnv) }
}
