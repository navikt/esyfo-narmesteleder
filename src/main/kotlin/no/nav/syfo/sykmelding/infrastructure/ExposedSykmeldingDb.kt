package no.nav.syfo.sykmelding.infrastructure

import no.nav.syfo.platform.database.ioTransaction
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.logger
import no.nav.syfo.sykmelding.application.SendtSykmelding
import no.nav.syfo.sykmelding.application.SykmeldingDb
import no.nav.syfo.sykmelding.application.SykmeldingTransaction
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
) : SykmeldingDb {

    private object TransactionImpl : SykmeldingTransaction {
        override fun saveLatestPerArbeidsforhold(sykmeldinger: List<SendtSykmelding>): Int {
            if (sykmeldinger.isEmpty()) return 0

            // sykmelding_id is also unique, but the upsert only resolves conflicts on (fnr, orgnummer).
            // A sykmelding that is resent with another fnr or orgnummer (e.g. a corrected paper sykmelding)
            // would violate the sykmelding_id constraint, so the stored row is removed first.
            val replacedCount = deleteBySykmeldingIds(sykmeldinger.map { it.sykmeldingId })

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

            return replacedCount
        }

        override fun revoke(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int {
            if (sykmeldingIds.isEmpty()) return 0

            return SendtSykmeldingTable.update({ SendtSykmeldingTable.sykmeldingId inList sykmeldingIds }) {
                it[SendtSykmeldingTable.revokedDate] = revokedDate
            }
        }

        private fun deleteBySykmeldingIds(sykmeldingIds: List<UUID>): Int = SendtSykmeldingTable.deleteWhere { SendtSykmeldingTable.sykmeldingId inList sykmeldingIds }
    }

    override suspend fun transaction(block: suspend SykmeldingTransaction.() -> Unit) {
        try {
            database.ioTransaction {
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
