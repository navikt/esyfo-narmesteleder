package no.nav.syfo.sykmelding.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.sykmelding.application.SendtSykmelding
import no.nav.syfo.sykmelding.application.SykmeldingDb
import no.nav.syfo.sykmelding.application.SykmeldingTransaction
import no.nav.syfo.util.logger
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.event.Level
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class SykmeldingDbException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val sykmeldingTransactionRolledBack = applicationEvent<Unit>(
    name = "sykmelding_transaction_rolled_back",
    level = Level.INFO,
    message = "Sykmelding transaction failed and was rolled back",
    operation = "persist_sendt_sykmelding",
)

class ExposedSykmeldingDb(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SykmeldingDb {

    private object TransactionImpl : SykmeldingTransaction {
        override fun batchUpsertSykmeldingerIfMoreRecentTom(sykmeldinger: List<SendtSykmelding>) {
            if (sykmeldinger.isEmpty()) return

            SendtSykmeldingTable.batchUpsert(
                sykmeldinger,
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
            ) { sykmelding ->
                this[SendtSykmeldingTable.orgnummer] = sykmelding.orgnummer
                this[SendtSykmeldingTable.sykmeldingId] = sykmelding.sykmeldingId
                this[SendtSykmeldingTable.fnr] = sykmelding.fnr
                this[SendtSykmeldingTable.syketilfelleStartDato] = sykmelding.syketilfelleStartDato
                this[SendtSykmeldingTable.fom] = sykmelding.fom
                this[SendtSykmeldingTable.tom] = sykmelding.tom
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
            logger.logEvent(sykmeldingTransactionRolledBack, Unit, cause = e)
            throw SykmeldingDbException("Transaction failed", e)
        }
    }

    companion object {
        private val logger = logger()
    }
}
