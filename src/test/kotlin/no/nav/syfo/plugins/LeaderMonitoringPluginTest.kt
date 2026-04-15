package no.nav.syfo.plugins

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.events.LeaderChange

class LeaderMonitoringPluginTest :
    DescribeSpec({

        describe("deriveLeaderChange") {
            it("should return Promoted when transitioning from non-leader to leader") {
                deriveLeaderChange(wasLeader = false, isLeader = true) shouldBe LeaderChange.Promoted
            }

            it("should return Demoted when transitioning from leader to non-leader") {
                deriveLeaderChange(wasLeader = true, isLeader = false) shouldBe LeaderChange.Demoted
            }

            it("should return Unaffected when remaining non-leader") {
                deriveLeaderChange(wasLeader = false, isLeader = false) shouldBe LeaderChange.Unaffected
            }

            it("should return Unaffected when remaining leader") {
                deriveLeaderChange(wasLeader = true, isLeader = true) shouldBe LeaderChange.Unaffected
            }
        }

        describe("LeaderChange sealed interface") {
            it("should support exhaustive when matching") {
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
    })
