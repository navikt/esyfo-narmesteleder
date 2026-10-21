package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.syfo.platform.scheduling.LeaderChange
import no.nav.syfo.platform.scheduling.LeaderChangeSSEListener

class LeaderMonitoringPluginTest :
    FunSpec({

        context("deriveLeaderChange") {
            test("should return Promoted when transitioning from non-leader to leader") {
                deriveLeaderChange(wasLeader = false, isLeader = true) shouldBe LeaderChange.Promoted
            }

            test("should return Demoted when transitioning from leader to non-leader") {
                deriveLeaderChange(wasLeader = true, isLeader = false) shouldBe LeaderChange.Demoted
            }

            test("should return Unaffected when remaining non-leader") {
                deriveLeaderChange(wasLeader = false, isLeader = false) shouldBe LeaderChange.Unaffected
            }

            test("should return Unaffected when remaining leader") {
                deriveLeaderChange(wasLeader = true, isLeader = true) shouldBe LeaderChange.Unaffected
            }
        }

        context("LeaderChange sealed interface") {
            test("should support exhaustive when matching") {
                val results =
                    listOf(
                        LeaderChange.Promoted,
                        LeaderChange.Demoted,
                        LeaderChange.Unaffected,
                    ).map { change ->
                        when (change) {
                            is LeaderChange.Promoted -> "promoted"
                            is LeaderChange.Demoted -> "demoted"
                            is LeaderChange.Unaffected -> "unaffected"
                        }
                    }
                results shouldBe listOf("promoted", "demoted", "unaffected")
            }
        }

        context("deriveLeaderChangeEvent") {
            test("should skip the initial default non-leader emission") {
                deriveLeaderChangeEvent(index = 0, wasLeader = false, isLeader = false) shouldBe null
            }

            test("should emit promoted after the initial default non-leader emission") {
                deriveLeaderChangeEvent(index = 1, wasLeader = false, isLeader = true) shouldBe LeaderChange.Promoted
            }

            test("should emit promoted when the pod is already leader on initial state") {
                deriveLeaderChangeEvent(index = 0, wasLeader = false, isLeader = true) shouldBe LeaderChange.Promoted
            }
        }

        context("registerLeaderStatusGauge") {
            fun newListener(): LeaderChangeSSEListener {
                val httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })
                return LeaderChangeSSEListener(httpClient, "http://localhost/elector", false)
            }

            test("reports 0 for a non-leader pod") {
                val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                val listener = newListener()

                val gauge = registerLeaderStatusGauge(listener, registry)

                gauge.value() shouldBe 0.0
            }

            test("has no labels and reflects the false to true leader transition") {
                val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                val listener = newListener()

                val gauge = registerLeaderStatusGauge(listener, registry)

                gauge.id.tags shouldBe emptyList()
                gauge.value() shouldBe 0.0

                listener.initializeLeaderState(isLeader = true)
                gauge.value() shouldBe 1.0

                listener.initializeLeaderState(isLeader = false)
                gauge.value() shouldBe 0.0
            }

            test("emits the label-free leader status metric in the scrape output") {
                val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                val listener = newListener()
                registerLeaderStatusGauge(listener, registry)

                listener.initializeLeaderState(isLeader = true)

                registry.scrape() shouldContain "syfo_narmesteleder_leader_status 1.0"
            }

            test("replaces the listener state when the registry is reused") {
                val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                val initialListener = newListener().apply { initializeLeaderState(isLeader = true) }
                registerLeaderStatusGauge(initialListener, registry)
                registry.scrape() shouldContain "syfo_narmesteleder_leader_status 1.0"

                val restartedListener = newListener()
                registerLeaderStatusGauge(restartedListener, registry)
                registry.scrape() shouldContain "syfo_narmesteleder_leader_status 0.0"

                initialListener.initializeLeaderState(isLeader = true)
                registry.scrape() shouldContain "syfo_narmesteleder_leader_status 0.0"

                restartedListener.initializeLeaderState(isLeader = true)
                registry.scrape() shouldContain "syfo_narmesteleder_leader_status 1.0"
            }

            test("clears listener state when its application stops") {
                val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                val listener = newListener().apply { initializeLeaderState(isLeader = true) }
                registerLeaderStatusGauge(listener, registry)

                clearLeaderStatusGauge(listener, registry)

                registry.scrape() shouldContain "syfo_narmesteleder_leader_status 0.0"
            }
        }
    })
