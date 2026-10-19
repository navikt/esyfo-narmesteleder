package no.nav.syfo.sykmelding.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.sykmelding.application.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.application.SykmeldingDb
import no.nav.syfo.sykmelding.application.SykmeldingTransaction
import no.nav.syfo.util.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ExposedSykmeldingDb(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SykmeldingDb {

    private object TransactionImpl : SykmeldingTransaction {
        override fun batchUpsertSykmeldingerIfMoreRecentTom(entities: List<SendtSykmeldingEntity>) {
            if (entities.isEmpty()) return

            SendtSykmeldingTable.batchUpsert(
                entities,
                SendtSykmeldingTable.fnr,
                SendtSykmeldingTable.orgnummer,
                onUpdate = {
                    it[SendtSykmeldingTable.sykmeldingId] = insertValue(SendtSykmeldingTable.sykmeldingId)
                    it[SendtSykmeldingTable.syketilfelleStartDato] = insertValue(SendtSykmeldingTable.syketilfelleStartDato)
                    it[SendtSykmeldingTable.fom] = insertValue(SendtSykmeldingTable.fom)
                    it[SendtSykmeldingTable.tom] = insertValue(SendtSykmeldingTable.tom)
                    it[SendtSykmeldingTable.revokedDate] = null
                    it[SendtSykmeldingTable.updated] = CurrentTimestampWithTimeZone
                },
                where = { insertValue(SendtSykmeldingTable.tom) greaterEq SendtSykmeldingTable.tom },
                shouldReturnGeneratedValues = false,
            ) { entity ->
                this[SendtSykmeldingTable.orgnummer] = entity.orgnummer
                this[SendtSykmeldingTable.sykmeldingId] = entity.sykmeldingId
                this[SendtSykmeldingTable.fnr] = entity.fnr
                this[SendtSykmeldingTable.syketilfelleStartDato] = entity.syketilfelleStartDato
                this[SendtSykmeldingTable.fom] = entity.fom
                this[SendtSykmeldingTable.tom] = entity.tom
            }
        }

        override fun batchRevokeSykmelding(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int {
            if (sykmeldingIds.isEmpty()) return 0

            return SendtSykmeldingTable.update({ SendtSykmeldingTable.sykmeldingId inList sykmeldingIds }) {
                it[SendtSykmeldingTable.revokedDate] = revokedDate
            }
        }

        override fun batchDeleteAllBySykmeldingIds(sykmeldingIds: List<UUID>): Int {
            if (sykmeldingIds.isEmpty()) return 0

            return SendtSykmeldingTable.deleteWhere { SendtSykmeldingTable.sykmeldingId inList sykmeldingIds }
        }
    }

    override suspend fun transaction(block: suspend SykmeldingTransaction.() -> Unit) = withContext(dispatcher) {
        try {
            suspendTransaction(db = database) {
                // Preserve the legacy behavior: a failed batch is not retried within the adapter.
                maxAttempts = 1
                TransactionImpl.block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info("Transaction failed. Rolling back", e)
            throw SykmeldingDbException("Transaction failed", e)
        }
    }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity? = withContext(dispatcher) {
        suspendTransaction(db = database) {
            SendtSykmeldingTable
                .selectAll()
                .where { SendtSykmeldingTable.sykmeldingId eq sykmeldingId }
                .limit(1)
                .firstOrNull()
                ?.toSendtSykmeldingEntity()
        }
    }

    companion object {
        private val logger = logger()
    }
}

internal fun ResultRow.toSendtSykmeldingEntity(): SendtSykmeldingEntity = SendtSykmeldingEntity(
    id = this[SendtSykmeldingTable.id].value.toLong(),
    sykmeldingId = this[SendtSykmeldingTable.sykmeldingId],
    fnr = this[SendtSykmeldingTable.fnr],
    orgnummer = this[SendtSykmeldingTable.orgnummer],
    fom = this[SendtSykmeldingTable.fom],
    tom = this[SendtSykmeldingTable.tom],
    revokedDate = this[SendtSykmeldingTable.revokedDate],
    syketilfelleStartDato = this[SendtSykmeldingTable.syketilfelleStartDato],
    created = this[SendtSykmeldingTable.created].toInstant(),
    updated = this[SendtSykmeldingTable.updated].toInstant(),
)
