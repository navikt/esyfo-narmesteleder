package no.nav.syfo.platform.upstream

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.application.exception.UpstreamRequestException

class UpstreamResultTest :
    FunSpec({
        test("legacy bridge returns the success value") {
            UpstreamResult.Success("value").getOrThrow() shouldBe "value"
        }

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

        test("legacy bridge preserves failure metadata and cause without exposing its message") {
            val syntheticIdent = "00000000000"
            val cause = IllegalStateException("Unknown ident: $syntheticIdent")
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, cause)

            val exception = shouldThrow<UpstreamRequestException> {
                UpstreamResult.Failure(failure).getOrThrow()
            }

            exception.cause shouldBe cause
            exception.upstreamStatus shouldBe 503
            exception.failureStage shouldBe UpstreamFailureStage.RESPONSE
            exception.upstream shouldBe "aareg"
            exception.message shouldBe "aareg failed at response"
            exception.message.orEmpty() shouldNotContain syntheticIdent
            failure.toString() shouldNotContain syntheticIdent
        }
    })
