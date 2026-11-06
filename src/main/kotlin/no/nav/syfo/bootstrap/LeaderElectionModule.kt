package no.nav.syfo.bootstrap

import no.nav.syfo.platform.http.httpClientSSE
import no.nav.syfo.platform.scheduling.HttpLeaderElection
import no.nav.syfo.platform.scheduling.LeaderChangeListener
import no.nav.syfo.platform.scheduling.LeaderChangeSSEListener
import no.nav.syfo.platform.scheduling.LeaderElection
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import org.koin.dsl.module

/** Registers leader election against the NAIS elector sidecar. */
internal fun leaderElectionModule(settings: LeaderElectionSettings) = module {
    single<LeaderChangeListener> {
        LeaderChangeSSEListener(
            sseHttpClient = httpClientSSE(),
            electorSseUrl = settings.electorSseUrl,
        )
    }
    single<LeaderElection> { HttpLeaderElection(httpClient = get(), electorPath = settings.electorPath) }
}
