package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.ProductionLogs
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedDialogScanTest :
    FunSpec({
        val logs = extension(ProductionLogs(BoundedDialogScan::class.java, Level.INFO))

        test("an idle run fetches once and writes no progress") {
            runTest {
                val fixture = ScanFixture()

                fixture.run() shouldBe DialogScanSummary(DialogScanFlow.SEND, batches = 0, attempted = 0, failed = 0, capped = false)

                fixture.fetches shouldBe listOf(null to HIGH_WATER_MARK)
                fixture.progress.writes shouldBe 0
            }
        }

        test("a new cycle fetches after each full batch and removes its progress when a partial batch ends it") {
            runTest {
                val fixture = ScanFixture((1..5).map { entry(it) })

                fixture.run() shouldBe DialogScanSummary(DialogScanFlow.SEND, batches = 3, attempted = 5, failed = 0, capped = false)

                fixture.attempts shouldBe (1..5).map { entry(it) }
                fixture.fetches shouldBe listOf(null, entry(2).position, entry(4).position).map { it to HIGH_WATER_MARK }
                fixture.progress.state shouldBe emptyMap()
            }
        }

        test("failing behov cannot extend a run past the batch limit, and the run saves where the cycle continues") {
            runTest {
                val fixture = ScanFixture((1..8).map { entry(it) })
                fixture.fails = { true }

                fixture.run() shouldBe DialogScanSummary(DialogScanFlow.SEND, batches = 3, attempted = 6, failed = 6, capped = true)

                fixture.attempts shouldBe (1..6).map { entry(it) }
                fixture.progress.state shouldBe mapOf(DialogScanFlow.SEND to DialogScanProgress(entry(6).position, HIGH_WATER_MARK))
            }
        }

        test("the next run continues after the saved position with the cycle's own high-water mark") {
            runTest {
                // Behov 9 is created after the cycle's high-water mark, so it belongs to the next cycle.
                val fixture = ScanFixture((1..8).map { entry(it) } + entry(9, created = HIGH_WATER_MARK.plusSeconds(30)))
                fixture.progress.state[DialogScanFlow.SEND] = DialogScanProgress(entry(6).position, HIGH_WATER_MARK)

                fixture.run(newCycleHighWaterMark = HIGH_WATER_MARK.plusSeconds(60))

                fixture.attempts shouldBe listOf(entry(7), entry(8))
                fixture.fetches shouldBe listOf(entry(6).position, entry(8).position).map { it to HIGH_WATER_MARK }
                fixture.progress.state shouldBe emptyMap()
            }
        }

        test("failed behov and behov committed behind the position are attempted when the next cycle starts") {
            runTest {
                val fixture = ScanFixture((1..7).map { entry(it) })
                fixture.fails = { it == entry(1) }
                val lateCommit = entry(10, created = HIGH_WATER_MARK.minusSeconds(3600))

                fixture.run()
                fixture.eligible += lateCommit
                fixture.run()
                fixture.run()

                fixture.attempts shouldBe (1..7).map { entry(it) } + listOf(lateCommit, entry(1))
            }
        }

        test("a run that stops mid-cycle resumes after the last saved batch") {
            runTest {
                val fixture = ScanFixture((1..5).map { entry(it) })
                fixture.fails = { true }
                fixture.crashOnFetch = 3

                shouldThrow<IllegalStateException> { fixture.run() }
                fixture.progress.state shouldBe mapOf(DialogScanFlow.SEND to DialogScanProgress(entry(4).position, HIGH_WATER_MARK))

                fixture.crashOnFetch = null
                fixture.run()

                fixture.attempts shouldBe (1..5).map { entry(it) }
                fixture.progress.state shouldBe emptyMap()
            }
        }

        test("a run pauses between batches but not before the first or after the last") {
            runTest {
                val fixture = ScanFixture((1..8).map { entry(it) }, batchDelay = 100.milliseconds)

                fixture.run()

                fixture.fetches.size shouldBe 3
                testScheduler.currentTime shouldBe 200L
            }
        }

        test("each flow continues and ends only its own cycle") {
            runTest {
                val progress = RecordingDialogScanProgressRepository()
                val send = ScanFixture((1..8).map { entry(it) }, DialogScanFlow.SEND, progress).apply { fails = { true } }
                val expire = ScanFixture((1..8).map { entry(it) }, DialogScanFlow.EXPIRE, progress).apply { fails = { true } }

                send.run()
                expire.run()
                expire.run()

                expire.attempts shouldBe (1..8).map { entry(it) }
                progress.state shouldBe mapOf(DialogScanFlow.SEND to DialogScanProgress(entry(6).position, HIGH_WATER_MARK))
            }
        }

        test("a run logs one aggregate event") {
            runTest {
                val fixture = ScanFixture((1..8).map { entry(it) }, DialogScanFlow.EXPIRE)
                fixture.fails = { it == entry(2) }

                logs.capture().use { capture ->
                    fixture.run()

                    val record = capture.logs.single()
                    record.eventType shouldBe "dialog_scan_run_finished"
                    record.level shouldBe Level.INFO
                    record["flow"] shouldBe "EXPIRE"
                    record["batches"] shouldBe 3
                    record["attempted"] shouldBe 6
                    record["failed"] shouldBe 1
                    record["capped"] shouldBe true
                }
            }
        }
    })

private val HIGH_WATER_MARK: Instant = Instant.parse("2026-10-01T10:00:00Z")

private data class Entry(override val id: NarmestelederbehovId, override val created: Instant) : DialogScanEntry

private fun entry(number: Int, created: Instant = HIGH_WATER_MARK.minusSeconds(100L - number)) = Entry(NarmestelederbehovId(UUID(0, number.toLong())), created)

/** Scans [eligible] in batches of two, at most three per run. */
private class ScanFixture(
    entries: List<Entry> = emptyList(),
    flow: DialogScanFlow = DialogScanFlow.SEND,
    val progress: RecordingDialogScanProgressRepository = RecordingDialogScanProgressRepository(),
    batchDelay: Duration = Duration.ZERO,
) {
    /** A successful attempt removes the behov, as marking it does in PostgreSQL; a failed one stays eligible. */
    val eligible = entries.toMutableList()
    val fetches = mutableListOf<Pair<DialogScanPosition?, Instant>>()
    val attempts = mutableListOf<Entry>()
    var fails: (Entry) -> Boolean = { false }

    /** The fetch, counted over all runs, that throws as if the process stopped. */
    var crashOnFetch: Int? = null
    private val scan = BoundedDialogScan(flow, progress, batchSize = 2, maxBatches = 3, batchDelay = batchDelay)

    suspend fun run(newCycleHighWaterMark: Instant = HIGH_WATER_MARK): DialogScanSummary = scan.runOnce(
        newCycleHighWaterMark = newCycleHighWaterMark,
        fetch = { after, highWaterMark, limit ->
            fetches += after to highWaterMark
            check(fetches.size != crashOnFetch) { "Simulated process stop" }
            scanPage(eligible, after, highWaterMark, limit)
        },
        attempt = { entry ->
            attempts += entry
            if (fails(entry)) {
                DialogAttemptOutcome.FAILED
            } else {
                eligible -= entry
                DialogAttemptOutcome.SUCCEEDED
            }
        },
    )
}
