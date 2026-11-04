package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import no.nav.syfo.platform.observability.ProductionLogs

@OptIn(ExperimentalCoroutinesApi::class)
class ExpireNarmestelederbehovDialogsUseCaseTest :
    FunSpec({
        val logs = extension(ProductionLogs(ExpireNarmestelederbehovDialogsUseCase::class.java, Level.WARN))
        val scanLogs = extension(ProductionLogs(BoundedDialogScan::class.java, Level.INFO))
        val mapper = jacksonObjectMapper()

        test("an empty batch has no dialog calls or marks") {
            runTest {
                val fixture = ExpireDialogsFixture()
                fixture.useCase.execute()
                fixture.repository.expiryQueries shouldBe 1
                fixture.calls shouldBe emptyList()
                fixture.repository.expired shouldBe emptyList()
            }
        }

        test("expires and marks each behov with now plus ten minutes in query order") {
            runTest {
                val batch = listOf(expirableDialog(), expirableDialog(2))
                val fixture = ExpireDialogsFixture(batch)

                fixture.useCase.execute()

                fixture.calls shouldBe batch.map { it.dialogId to UPDATE_NOW.plusSeconds(600) }
                fixture.repository.expired shouldBe batch.map { it.id to UPDATE_NOW.plusSeconds(600) }
                fixture.repository.expiryQueries shouldBe 1
            }
        }

        listOf("unavailable", "expire exception", "mark exception").forEach { failureAt ->
            test("$failureAt logs EXPIRE safely, leaves the failed behov unmarked and continues") {
                runTest {
                    val first = expirableDialog()
                    val second = expirableDialog(2)
                    val fixture = ExpireDialogsFixture(listOf(first, second))
                    fixture.expire = { id ->
                        when {
                            id != first.dialogId -> DialogExpiry.Expired
                            failureAt == "unavailable" -> DialogExpiry.Unavailable(updateFailure())
                            failureAt == "expire exception" -> throw IllegalStateException("private-diagnostic-canary")
                            else -> DialogExpiry.Expired
                        }
                    }
                    var marks = 0
                    if (failureAt == "mark exception") {
                        fixture.repository.markExpired = {
                            if (++marks == 1) throw IllegalStateException("private-diagnostic-canary")
                        }
                    }

                    logs.capture().use { capture ->
                        fixture.useCase.execute()
                        val output = capture.records.single()
                        val record = mapper.readTree(output)
                        record["event_type"].asText() shouldBe "dialogporten_dialog_failed"
                        record["action"].asText() shouldBe "EXPIRE"
                        record["behov_id"].asText() shouldBe first.id.value.toString()
                        record["dialog_id"].asText() shouldBe first.dialogId.toString()
                        if (failureAt == "unavailable") {
                            record["upstream"].asText() shouldBe "dialogporten"
                            record["upstream_status"].asInt() shouldBe 503
                            record["failure_stage"].asText() shouldBe "response"
                        }
                        output shouldNotContain "private-diagnostic-canary"
                    }
                    fixture.calls.map { it.first } shouldBe listOf(first.dialogId, second.dialogId)
                    fixture.repository.expired shouldBe listOf(second.id to UPDATE_NOW.plusSeconds(600))
                }
            }
        }

        listOf(99, 100, 101, 200).forEach { count ->
            test("refetches after full batches for $count behov and stops on a smaller batch") {
                runTest {
                    val batch = (1..count).map { expirableDialog(it) }
                    val fixture = ExpireDialogsFixture(batch)

                    fixture.useCase.execute()

                    fixture.calls.map { it.first } shouldBe batch.map { it.dialogId }
                    fixture.repository.expired.map { it.first } shouldBe batch.map { it.id }
                    fixture.repository.expiryQueries shouldBe count / 100 + 1
                    testScheduler.currentTime shouldBe (count / 100) * 100L
                }
            }
        }

        test("caps a run at 1,000 failing behov, continues at behov 1,001 and retries from the oldest in the next cycle") {
            runTest {
                val fixture = ExpireDialogsFixture((1..1001).map { expirableDialog(it) })
                fixture.expire = { DialogExpiry.Unavailable(updateFailure()) }

                scanLogs.capture().use { capture ->
                    fixture.useCase.execute()
                    // Nine pauses between ten batches.
                    testScheduler.currentTime shouldBe 900L
                    fixture.progress.state shouldBe mapOf(
                        DialogScanFlow.EXPIRE to DialogScanProgress(expirableDialog(1000).position, highWaterMark = UPDATE_NOW),
                    )

                    fixture.useCase.execute()
                    fixture.progress.state shouldBe emptyMap()

                    // Dialogporten is available again when the next cycle starts.
                    fixture.expire = { DialogExpiry.Expired }
                    fixture.useCase.execute()

                    capture.scanSummaries() shouldBe listOf(
                        DialogScanSummary(DialogScanFlow.EXPIRE, batches = 10, attempted = 1000, failed = 1000, capped = true),
                        DialogScanSummary(DialogScanFlow.EXPIRE, batches = 1, attempted = 1, failed = 1, capped = false),
                        DialogScanSummary(DialogScanFlow.EXPIRE, batches = 10, attempted = 1000, failed = 0, capped = true),
                    )
                }
                fixture.calls.map { it.first } shouldBe ((1..1001) + (1..1000)).map { expirableDialog(it).dialogId }
            }
        }

        listOf("fetch", "expire", "mark").forEach { step ->
            test("cancellation at $step is rethrown without logging or processing the next behov") {
                runTest {
                    val cancellation = CancellationException("cancel test")
                    val fixture = ExpireDialogsFixture(listOf(expirableDialog(), expirableDialog(2)))
                    when (step) {
                        "fetch" -> fixture.repository.findExpired = { _, _, _ -> throw cancellation }
                        "expire" -> fixture.expire = { throw cancellation }
                        "mark" -> fixture.repository.markExpired = { throw cancellation }
                    }

                    logs.capture().use { capture ->
                        shouldThrow<CancellationException> { fixture.useCase.execute() } shouldBe cancellation
                        capture.records shouldBe emptyList()
                    }
                    fixture.calls.size shouldBe if (step == "fetch") 0 else 1
                    fixture.repository.expired shouldBe emptyList()
                }
            }
        }
    })
