package no.nav.syfo.bootstrap

import no.nav.syfo.platform.http.httpClientSSE
import no.nav.syfo.platform.scheduling.LeaderChangeSSEListener
import no.nav.syfo.platform.scheduling.LeaderElection
import org.koin.dsl.module

internal fun leaderElectionModule(other: OtherEnvironmentProperties, isLocalEnv: Boolean) = module {
    single {
        LeaderChangeSSEListener(
            sseHttpClient = httpClientSSE(),
            electorSseUrl = other.electorSSEUrl,
            isLocalEnv = isLocalEnv,
        )
    }
    single { LeaderElection(httpClient = get(), electorPath = other.electorPath, isLocal = isLocalEnv) }
}
