package no.nav.syfo.application.leaderelection

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class LeaderChangeSSEListenerTest :
    DescribeSpec({
        describe("idempotency guard") {
            it("should prevent duplicate listener starts") {
                val mockEngine =
                    MockEngine { _ ->
                        respond("", HttpStatusCode.InternalServerError)
                    }
                val httpClient = HttpClient(mockEngine)
                val listener = LeaderChangeSSEListener(httpClient, "http://localhost/elector", false)
                val timeToWait = 20.milliseconds
                val job =
                    launch {
                        listener.listenForLeaderChanges()
                    }

                // Give the first call a moment to set the AtomicBoolean
                delay(timeToWait)

                // Second call should throw an exception to avoid opening multiple connections across the instance
                shouldThrow<LeaderChangeSSEListener.LeaderChangeSSEException> { listener.listenForLeaderChanges() }
                job.cancel()
            }

            it("should default isLeader to false") {
                val mockEngine =
                    MockEngine { _ ->
                        respond("", HttpStatusCode.InternalServerError)
                    }
                val httpClient = HttpClient(mockEngine)
                val listener = LeaderChangeSSEListener(httpClient, "http://localhost/elector", false)

                listener.isLeader.value shouldBe false
            }

            it("should initialize leader state from simple API result") {
                val mockEngine =
                    MockEngine { _ ->
                        respond("", HttpStatusCode.InternalServerError)
                    }
                val httpClient = HttpClient(mockEngine)
                val listener = LeaderChangeSSEListener(httpClient, "http://localhost/elector", false)

                listener.initializeLeaderState(isLeader = true)

                listener.isLeader.value shouldBe true
            }
        }
    })
