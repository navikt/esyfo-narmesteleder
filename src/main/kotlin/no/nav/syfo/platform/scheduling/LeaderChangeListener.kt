package no.nav.syfo.platform.scheduling

import kotlinx.coroutines.flow.StateFlow

/** Tracks whether this pod is the elected leader and publishes changes. */
interface LeaderChangeListener {
    val isLeader: StateFlow<Boolean>

    fun initializeLeaderState(isLeader: Boolean)

    /** Runs until cancelled and updates [isLeader] when leadership changes. */
    suspend fun listenForLeaderChanges()
}
