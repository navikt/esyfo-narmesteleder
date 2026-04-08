package no.nav.syfo.application.leaderelection

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import no.nav.syfo.application.environment.isLocalEnv

class LeaderChangeSSEListenerTest :
    DescribeSpec({
        beforeTest {
            clearAllMocks()
            mockkStatic(::isLocalEnv)
        }

        afterTest {
            unmockkStatic(::isLocalEnv)
        }

        fun createListener(): LeaderChangeSSEListener {
            val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
            val client = HttpClient(mockEngine)
            return LeaderChangeSSEListener(client, "http://localhost/elector")
        }

        describe("LeaderChangeSSEListener") {
            describe("initial state") {
                it("should have isLeader as false before listening") {
                    val listener = createListener()
                    listener.isLeader.value shouldBe false
                }

                it("should expose isLeader as a StateFlow") {
                    val listener = createListener()
                    listener.isLeader.shouldBeInstanceOf<StateFlow<Boolean>>()
                }

                it("should expose isLeader as an observable StateFlow") {
                    val listener = createListener()
                    listener.isLeader.take(1).toList() shouldBe listOf(false)
                }
            }

            describe("duplicate listener guard") {
                it("should not re-enter listener body on second call") {
                    every { isLocalEnv() } returns true
                    val listener = createListener()

                    // First call enters the body and checks isLocalEnv()
                    listener.listenForLeaderChanges()
                    // Second call is guarded by isListening AtomicBoolean
                    // and returns before reaching isLocalEnv()
                    listener.listenForLeaderChanges()

                    // isLocalEnv() sits after the isListening guard, so it should
                    // only be invoked on the first call
                    verify(exactly = 1) { isLocalEnv() }
                }

                it("should not change isLeader state on duplicate call") {
                    every { isLocalEnv() } returns true
                    val listener = createListener()

                    listener.listenForLeaderChanges()
                    listener.isLeader.value shouldBe true

                    // Second call should be a no-op
                    listener.listenForLeaderChanges()
                    listener.isLeader.value shouldBe true
                }
            }

            describe("local environment shortcut") {
                it("should set isLeader to true in local environment") {
                    every { isLocalEnv() } returns true
                    val listener = createListener()

                    listener.isLeader.value shouldBe false
                    listener.listenForLeaderChanges()
                    listener.isLeader.value shouldBe true
                }
            }
        }
    })
