package no.nav.syfo.sykmelding.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import no.nav.syfo.TestDB
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetter
import no.nav.syfo.sykmelding.application.SykmeldingsregisterPosition
import no.nav.syfo.sykmelding.application.SykmeldingsregisterRejection
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

class ExposedSykmeldingsregisterDeadLetterRepositoryTest :
    FunSpec({
        val repository = ExposedSykmeldingsregisterDeadLetterRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearAllData()
        }

        test("stores position, sykmelding id and reason for each dead letter and returns them") {
            val deadLetters = listOf(
                SykmeldingsregisterDeadLetter(
                    SykmeldingsregisterPosition(partition = 1, offset = 10),
                    SYKMELDING_ID,
                    SykmeldingsregisterRejection.INVALID_PAYLOAD,
                    "kafkaMetadata.fnr",
                ),
                SykmeldingsregisterDeadLetter(
                    SykmeldingsregisterPosition(partition = 2, offset = 20),
                    null,
                    SykmeldingsregisterRejection.MISSING_KEY,
                ),
            )

            val stored = repository.saveAll(deadLetters)

            assertSoftly {
                stored shouldContainExactlyInAnyOrder deadLetters
                storedRows() shouldContainExactlyInAnyOrder listOf(
                    Row(1, 10, SYKMELDING_ID, "INVALID_PAYLOAD: kafkaMetadata.fnr"),
                    Row(2, 20, null, "MISSING_KEY"),
                )
            }
        }

        test("a redelivered position is stored once, keeps the first reason and is not returned again") {
            val position = SykmeldingsregisterPosition(partition = 0, offset = 5)
            repository.saveAll(
                listOf(SykmeldingsregisterDeadLetter(position, SYKMELDING_ID, SykmeldingsregisterRejection.MALFORMED_PAYLOAD)),
            )
            val newDeadLetter = SykmeldingsregisterDeadLetter(
                SykmeldingsregisterPosition(partition = 0, offset = 6),
                null,
                SykmeldingsregisterRejection.MISSING_KEY,
            )

            val stored = repository.saveAll(
                listOf(
                    SykmeldingsregisterDeadLetter(position, SYKMELDING_ID, SykmeldingsregisterRejection.INVALID_PAYLOAD, "event"),
                    newDeadLetter,
                ),
            )

            assertSoftly {
                stored shouldContainExactlyInAnyOrder listOf(newDeadLetter)
                storedRows() shouldContainExactlyInAnyOrder listOf(
                    Row(0, 5, SYKMELDING_ID, "MALFORMED_PAYLOAD"),
                    Row(0, 6, null, "MISSING_KEY"),
                )
            }
        }
    })

private val SYKMELDING_ID: UUID = UUID.fromString("0b6f7c1e-2d3a-4b5c-9d8e-7f6a5b4c3d2e")

private data class Row(val partition: Int, val offset: Long, val sykmeldingId: UUID?, val reason: String)

private fun storedRows(): List<Row> = transaction(TestDB.exposedDatabase) {
    SykmeldingsregisterDeadLetterTable.selectAll().map {
        Row(
            it[SykmeldingsregisterDeadLetterTable.sourcePartition],
            it[SykmeldingsregisterDeadLetterTable.sourceOffset],
            it[SykmeldingsregisterDeadLetterTable.sykmeldingId],
            it[SykmeldingsregisterDeadLetterTable.reason],
        )
    }
}
