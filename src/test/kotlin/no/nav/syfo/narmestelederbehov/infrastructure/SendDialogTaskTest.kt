package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederbehov.application.SEND_NOW
import no.nav.syfo.narmestelederbehov.application.SendDialogsFixture

class SendDialogTaskTest :
    FunSpec({
        test("executes the send use case") {
            val fixture = SendDialogsFixture(emptyList())
            val task = SendDialogTask(fixture.useCase)

            task.execute()

            fixture.queries shouldBe listOf(SEND_NOW.minusSeconds(10) to 100)
        }
    })
