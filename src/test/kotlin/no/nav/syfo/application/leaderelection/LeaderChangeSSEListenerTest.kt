package no.nav.syfo.application.leaderelection

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LeaderChangeSSEListenerTest :
    DescribeSpec({

        describe("idempotency guard") {
            it("should prevent duplicate listener starts") {
                val mockEngine =
                    MockEngine { _ ->
                        respond("", HttpStatusCode.InternalServerError)
                    }
                val httpClient = HttpClient(mockEngine)
                val listener = LeaderChangeSSEListener(httpClient, "http://localhost/elector")

                var secondCallCompleted = false

                val job =
                    launch {
                        listener.listenForLeaderChanges()
                    }

                // Give the first call a moment to set the AtomicBoolean
                delay(100)

                // Second call should return immediately due to idempotency guard
                listener.listenForLeaderChanges()
                secondCallCompleted = true

                secondCallCompleted shouldBe true
                job.cancel()
            }

            it("should default isLeader to false") {
                val mockEngine =
                    MockEngine { _ ->
                        respond("", HttpStatusCode.InternalServerError)
                    }
                val httpClient = HttpClient(mockEngine)
                val listener = LeaderChangeSSEListener(httpClient, "http://localhost/elector")

                listener.isLeader.value shouldBe false
            }
        }
    })
