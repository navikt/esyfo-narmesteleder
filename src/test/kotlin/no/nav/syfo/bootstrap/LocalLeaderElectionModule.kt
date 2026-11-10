package no.nav.syfo.bootstrap

import no.nav.syfo.platform.scheduling.FakeLeaderChangeListener
import no.nav.syfo.platform.scheduling.FakeLeaderElection
import no.nav.syfo.platform.scheduling.LeaderChangeListener
import no.nav.syfo.platform.scheduling.LeaderElection
import org.koin.dsl.module

/** Replaces [leaderElectionModule] for local runs, where the single instance is always the leader. */
internal fun localLeaderElectionModule() = module {
    single<LeaderChangeListener> { FakeLeaderChangeListener() }
    single<LeaderElection> { FakeLeaderElection() }
}
