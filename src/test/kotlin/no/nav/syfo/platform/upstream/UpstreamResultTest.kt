package no.nav.syfo.platform.upstream

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UpstreamResultTest :
    FunSpec({
        test("getOrElse returns success without invoking the failure handler") {
            UpstreamResult.Success("value").getOrElse { error("Unexpected failure") } shouldBe "value"
        }

        test("getOrElse passes the failure to its handler") {
            val failure = UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, null, IllegalStateException())
            UpstreamResult.Failure(failure).getOrElse {
                it shouldBe failure
                "fallback"
            } shouldBe "fallback"
        }

        test("a failure requires a cause or at least one error") {
            shouldThrow<IllegalArgumentException> {
                UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200)
            }
        }
    })
