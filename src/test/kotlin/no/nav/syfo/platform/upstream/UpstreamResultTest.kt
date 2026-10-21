package no.nav.syfo.platform.upstream

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

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

        test("a failure requires a cause or at least one error") {
            shouldThrow<IllegalArgumentException> {
                UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200)
            }
        }

        test("legacy bridge accepts error responses without a cause and does not copy their contents into its message") {
            val failure = UpstreamFailure(
                UpstreamName("pdl"),
                UpstreamFailureStage.RESPONSE,
                200,
                errors = listOf(UpstreamError("server_error", "technical_id", "hentPerson.navn")),
            )
            val exception = shouldThrow<UpstreamRequestException> { UpstreamResult.Failure(failure).getOrThrow() }
            exception.cause shouldBe null
            exception.upstream shouldBe "pdl"
            exception.upstreamStatus shouldBe 200
            exception.failureStage shouldBe UpstreamFailureStage.RESPONSE
            exception.message shouldBe "pdl failed at response"
            failure.toString() shouldNotContain "technical_id"
            failure.toString() shouldNotContain "hentPerson.navn"
        }
    })
