package no.nav.syfo.sykmelding.infrastructure

import no.nav.syfo.platform.database.ioTransaction
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetter
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetterRepository
import no.nav.syfo.sykmelding.application.SykmeldingsregisterPosition
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import java.sql.Connection

/**
 * Uses READ COMMITTED explicitly: under the pool default REPEATABLE READ, a concurrent insert of the
 * same position can fail with a serialization error instead of being ignored.
 */
class ExposedSykmeldingsregisterDeadLetterRepository(
    private val database: Database,
) : SykmeldingsregisterDeadLetterRepository {
    override suspend fun saveAll(deadLetters: List<SykmeldingsregisterDeadLetter>): List<SykmeldingsregisterDeadLetter> {
        if (deadLetters.isEmpty()) return emptyList()
        // With ignore = true, Exposed returns only the rows the database inserted.
        val inserted = database.ioTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            SykmeldingsregisterDeadLetterTable.batchInsert(
                data = deadLetters,
                ignore = true,
                shouldReturnGeneratedValues = false,
            ) { deadLetter ->
                this[SykmeldingsregisterDeadLetterTable.sourcePartition] = deadLetter.position.partition
                this[SykmeldingsregisterDeadLetterTable.sourceOffset] = deadLetter.position.offset
                this[SykmeldingsregisterDeadLetterTable.sykmeldingId] = deadLetter.sykmeldingId
                this[SykmeldingsregisterDeadLetterTable.reason] = deadLetter.reason
            }.map {
                SykmeldingsregisterPosition(
                    it[SykmeldingsregisterDeadLetterTable.sourcePartition],
                    it[SykmeldingsregisterDeadLetterTable.sourceOffset],
                )
            }.toSet()
        }
        return deadLetters.filter { it.position in inserted }
    }
}
