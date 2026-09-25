package no.nav.syfo.platform.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StepTest :
    FunSpec({
        test("orStop returns the value when the step continues") {
            run(Step.Continue(42)) shouldBe "value 42"
        }

        test("orStop returns the stop result from the enclosing function when the step stops") {
            run(Step.Stop("stopped")) shouldBe "stopped"
        }

        test("Proceed continues with Unit") {
            Step.Proceed.orStop { error("should not stop") } shouldBe Unit
        }
    })

private fun run(step: Step<Int, String>): String {
    val value = step.orStop { return it }
    return "value $value"
}
