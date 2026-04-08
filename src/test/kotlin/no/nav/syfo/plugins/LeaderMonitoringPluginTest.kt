package no.nav.syfo.plugins

import io.kotest.core.spec.style.DescribeSpec
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener

class LeaderMonitoringPluginTest :
    DescribeSpec({
        describe("configureLeaderMonitoring") {
            it("starts the SSE listener when the application is ready") {
                val isLeader = MutableStateFlow(false)
                val leaderChangeSSEListener = mockk<LeaderChangeSSEListener>()

                every { leaderChangeSSEListener.isLeader } returns isLeader
                coEvery { leaderChangeSSEListener.listenForLeaderChanges() } coAnswers {
                    awaitCancellation()
                }

                val server = embeddedServer(Netty, port = 0) {
                    configureLeaderMonitoring(leaderChangeSSEListener)
                }

                try {
                    server.start(wait = false)

                    coVerify(timeout = 1000, exactly = 1) {
                        leaderChangeSSEListener.listenForLeaderChanges()
                    }
                } finally {
                    server.stop(1000, 1000)
                }
            }
        }
    })
