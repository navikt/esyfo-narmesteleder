package no.nav.syfo.plugins

import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.util.httpClientSSE
import org.koin.dsl.module

internal fun leaderElectionModule(isLocalEnv: Boolean) = module {
    single {
        LeaderChangeSSEListener(
            sseHttpClient = httpClientSSE(),
            electorSseUrl = env().otherProperties.electorSSEUrl,
            isLocalEnv = isLocalEnv,
        )
    }
    single { LeaderElection(httpClient = get(), electorPath = env().otherProperties.electorPath) }
}
