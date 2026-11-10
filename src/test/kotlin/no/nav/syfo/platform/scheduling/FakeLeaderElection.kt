package no.nav.syfo.platform.scheduling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Makes the single local instance the leader, so leader-controlled consumers and tasks run. */
class FakeLeaderElection : LeaderElection {
    override suspend fun isLeader(): Boolean = true
}

class FakeLeaderChangeListener : LeaderChangeListener {
    private val leader = MutableStateFlow(false)

    override val isLeader: StateFlow<Boolean> = leader.asStateFlow()

    override fun initializeLeaderState(isLeader: Boolean) {
        leader.value = isLeader
    }

    override suspend fun listenForLeaderChanges() {
        leader.value = true
    }
}
