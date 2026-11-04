package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmestelederbehov.application.DialogScanFlow
import no.nav.syfo.narmestelederbehov.application.DialogScanPosition
import no.nav.syfo.narmestelederbehov.application.DialogScanProgress
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.postgresql.util.PSQLException
import java.time.Instant
import java.util.UUID

class ExposedDialogScanProgressRepositoryTest :
    FunSpec({
        val repository = ExposedDialogScanProgressRepository(TestDB.exposedDatabase)
        val mark = Instant.parse("2026-10-01T10:00:00Z")
        val positionBeforeMark = DialogScanPosition(created = mark.minusSeconds(600), id = NarmestelederbehovId(UUID(0, 100)))

        beforeTest {
            TestDB.clearAllData()
        }

        test("find returns null when no cycle is in progress") {
            repository.find(DialogScanFlow.SEND).shouldBeNull()
        }

        test("saves and reloads the position and the cycle high-water mark of every flow") {
            DialogScanFlow.entries.forEach { flow ->
                repository.save(flow, DialogScanProgress(position = positionBeforeMark, highWaterMark = mark))

                assertSoftly(requireNotNull(repository.find(flow))) {
                    position shouldBe positionBeforeMark
                    highWaterMark shouldBe mark
                }
            }
        }

        test("saving twice keeps one row per flow") {
            repository.save(DialogScanFlow.SEND, DialogScanProgress(position = positionBeforeMark, highWaterMark = mark))
            repository.save(DialogScanFlow.SEND, DialogScanProgress(position = DialogScanPosition(created = mark.minusSeconds(300), id = NarmestelederbehovId(UUID(0, 200))), highWaterMark = mark))

            repository.find(DialogScanFlow.SEND) shouldBe DialogScanProgress(
                position = DialogScanPosition(created = mark.minusSeconds(300), id = NarmestelederbehovId(UUID(0, 200))),
                highWaterMark = mark,
            )
        }

        test("reset clears the cycle so the next run starts from the beginning") {
            repository.save(DialogScanFlow.SEND, DialogScanProgress(position = positionBeforeMark, highWaterMark = mark))

            repository.reset(DialogScanFlow.SEND)

            repository.find(DialogScanFlow.SEND).shouldBeNull()
        }

        test("resetting one flow leaves the other flow's progress untouched") {
            repository.save(DialogScanFlow.SEND, DialogScanProgress(position = positionBeforeMark, highWaterMark = mark))
            repository.save(DialogScanFlow.EXPIRE, DialogScanProgress(position = positionBeforeMark, highWaterMark = mark.plusSeconds(60)))

            repository.reset(DialogScanFlow.SEND)

            repository.find(DialogScanFlow.SEND).shouldBeNull()
            repository.find(DialogScanFlow.EXPIRE) shouldBe DialogScanProgress(position = positionBeforeMark, highWaterMark = mark.plusSeconds(60))
        }

        test("PostgreSQL rejects unknown flows and positions at or beyond the high-water mark") {
            val invalidRows = listOf(
                """INSERT INTO nl_dialog_scan_progress (flow_key, last_created, last_id, high_water_mark)
                   VALUES ('OTHER', '2026-10-01T09:50:00Z', '00000000-0000-0000-0000-000000000001', '2026-10-01T10:00:00Z')""" to
                    "nl_dialog_scan_progress_flow_key_check",
                """INSERT INTO nl_dialog_scan_progress (flow_key, last_created, last_id, high_water_mark)
                   VALUES ('SEND', '2026-10-01T10:00:00Z', '00000000-0000-0000-0000-000000000001', '2026-10-01T10:00:00Z')""" to
                    "nl_dialog_scan_progress_position_before_mark_check",
            )

            invalidRows.forEach { (sql, expectedConstraint) ->
                val failure = shouldThrow<Exception> {
                    TestDB.database.connection.use { connection ->
                        connection.createStatement().use { it.executeUpdate(sql) }
                        connection.commit()
                    }
                }
                val postgresFailure = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<PSQLException>().single()
                postgresFailure.sqlState shouldBe "23514"
                postgresFailure.serverErrorMessage?.constraint shouldBe expectedConstraint
            }

            repository.find(DialogScanFlow.SEND).shouldBeNull()
        }

        test("a rejected progress upsert rolls back and preserves the last valid position") {
            val valid = DialogScanProgress(position = positionBeforeMark, highWaterMark = mark)
            repository.save(DialogScanFlow.SEND, valid)

            val failure = shouldThrow<Exception> {
                repository.save(
                    DialogScanFlow.SEND,
                    DialogScanProgress(position = DialogScanPosition(created = mark, id = NarmestelederbehovId(UUID(0, 101))), highWaterMark = mark),
                )
            }

            val postgresFailure = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<PSQLException>().single()
            postgresFailure.serverErrorMessage?.constraint shouldBe "nl_dialog_scan_progress_position_before_mark_check"
            repository.find(DialogScanFlow.SEND) shouldBe valid
        }
    })
