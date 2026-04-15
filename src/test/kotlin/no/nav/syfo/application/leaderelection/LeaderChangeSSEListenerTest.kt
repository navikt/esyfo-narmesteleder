package no.nav.syfo.application.leaderelection

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

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
                var then: Instant? = null
                val job =
                    launch {
                        listener.listenForLeaderChanges()
                        Clock.System.now() shouldBeGreaterThan then!! // Just to illustrate that the first call to listenForLeaderChanges won't return immediately
                    }

                // Give the first call a moment to set the AtomicBoolean
                delay(timeToWait)

                // Second call should return immediately due to idempotency guard (Otherwise it would hang in the while loop)
                listener.listenForLeaderChanges()
                true shouldBe true
                then = Clock.System.now()
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
        }
    })
