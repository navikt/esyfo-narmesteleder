package no.nav.syfo.application.environment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtherEnvironmentPropertiesTest :
    FunSpec({
        test("local and test defaults keep employment revocation disabled") {
            OtherEnvironmentProperties.createForLocal().employmentCheckRevokeEnabled shouldBe false
        }

        test("startup rejects simultaneous employment revocation and source observation even when checks are disabled") {
            val failure = shouldThrow<IllegalArgumentException> {
                OtherEnvironmentProperties.createForLocal().copy(
                    employmentCheckRevokeEnabled = true,
                    employmentCheckObserveSourceEnabled = true,
                )
            }

            failure.message shouldBe "EMPLOYMENT_CHECK_REVOKE_ENABLED and EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED cannot both be enabled"
        }

        listOf(false to false, true to false, false to true).forEach { (revoke, observe) ->
            test("startup accepts revoke=$revoke and observe=$observe") {
                val properties = OtherEnvironmentProperties.createForLocal().copy(
                    employmentCheckRevokeEnabled = revoke,
                    employmentCheckObserveSourceEnabled = observe,
                )

                properties.employmentCheckRevokeEnabled shouldBe revoke
                properties.employmentCheckObserveSourceEnabled shouldBe observe
            }
        }
    })
