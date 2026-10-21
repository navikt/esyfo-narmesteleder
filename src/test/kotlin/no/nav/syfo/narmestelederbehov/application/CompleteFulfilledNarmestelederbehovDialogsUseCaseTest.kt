package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import no.nav.syfo.logging.ProductionLogs

class CompleteFulfilledNarmestelederbehovDialogsUseCaseTest :
    FunSpec({
        val logs = extension(ProductionLogs(CompleteFulfilledNarmestelederbehovDialogsUseCase::class.java, Level.INFO))
        val mapper = jacksonObjectMapper()

        test("queries once with a strict ten-second cutoff and does nothing for an empty batch") {
            val fixture = CompleteDialogsFixture()
            fixture.useCase.execute()
            fixture.repository.fulfilledQueries shouldBe listOf(UPDATE_NOW.minusSeconds(10))
            fixture.calls shouldBe emptyList()
            fixture.repository.marked shouldBe emptyList()
        }

        test("completed including already completed via the port is conditionally marked in query order, with only one full batch") {
            val batch = (1..100).map { dialogReference(it) }
            val fixture = CompleteDialogsFixture(batch)

            fixture.useCase.execute()

            fixture.calls shouldBe batch.map { it.dialogId }
            fixture.repository.marked shouldBe batch.map { it.id }
            fixture.repository.fulfilledQueries shouldBe listOf(UPDATE_NOW.minusSeconds(10))
        }

        test("a concurrent status change is only logged at info, with no new failure event") {
            val fixture = CompleteDialogsFixture(listOf(dialogReference()))
            fixture.repository.mark = { MarkDialogCompletedResult.NotFulfilled }

            logs.capture().use { capture ->
                fixture.useCase.execute()
                val records = capture.records.map { mapper.readTree(it) }
                records.any { it["message"].asText().contains("is no longer fulfilled") } shouldBe true
                records.none { it.has("event_type") } shouldBe true
            }
        }

        listOf("unavailable", "complete exception", "mark exception").forEach { failureAt ->
            test("$failureAt logs COMPLETE with safe diagnostics and does not abort the next behov") {
                val first = dialogReference()
                val second = dialogReference(2)
                val fixture = CompleteDialogsFixture(listOf(first, second))
                fixture.complete = { id ->
                    when {
                        id != first.dialogId -> DialogCompletion.Completed
                        failureAt == "unavailable" -> DialogCompletion.Unavailable(updateFailure())
                        failureAt == "complete exception" -> throw IllegalStateException("private-diagnostic-canary")
                        else -> DialogCompletion.Completed
                    }
                }
                var marks = 0
                if (failureAt == "mark exception") {
                    fixture.repository.mark = {
                        if (++marks == 1) throw IllegalStateException("private-diagnostic-canary")
                        MarkDialogCompletedResult.Marked
                    }
                }

                logs.capture().use { capture ->
                    fixture.useCase.execute()
                    val output = capture.records.single { mapper.readTree(it)["event_type"]?.asText() == "dialogporten_dialog_failed" }
                    val record = mapper.readTree(output)
                    record["action"].asText() shouldBe "COMPLETE"
                    record["behov_id"].asText() shouldBe first.id.value.toString()
                    record["dialog_id"].asText() shouldBe first.dialogId.toString()
                    if (failureAt == "unavailable") {
                        record["upstream"].asText() shouldBe "dialogporten"
                        record["upstream_status"].asInt() shouldBe 503
                        record["failure_stage"].asText() shouldBe "response"
                    }
                    output shouldNotContain "private-diagnostic-canary"
                }
                fixture.calls shouldBe listOf(first.dialogId, second.dialogId)
                fixture.repository.marked shouldBe listOf(second.id)
            }
        }

        listOf("fetch", "complete", "mark").forEach { step ->
            test("cancellation at $step is rethrown without failure logging or processing the next behov") {
                val cancellation = CancellationException("cancel test")
                val fixture = CompleteDialogsFixture(listOf(dialogReference(), dialogReference(2)))
                when (step) {
                    "fetch" -> fixture.repository.findFulfilled = { throw cancellation }
                    "complete" -> fixture.complete = { throw cancellation }
                    "mark" -> fixture.repository.mark = { throw cancellation }
                }

                logs.capture().use { capture ->
                    shouldThrow<CancellationException> { fixture.useCase.execute() } shouldBe cancellation
                    capture.records.none { mapper.readTree(it)["event_type"]?.asText() == "dialogporten_dialog_failed" } shouldBe true
                }
                fixture.calls.size shouldBe if (step == "fetch") 0 else 1
                fixture.repository.marked shouldBe emptyList()
            }
        }
    })
