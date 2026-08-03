package no.nav.syfo.sykmelding.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

interface IActiveSykmeldingRepository {
    suspend fun findActiveSykmelding(fnr: String, orgnummer: String): LocalActiveSykmeldingResult
}

class LocalActiveSykmeldingResult(
    val isActive: Boolean,
    /** Internal value for controlled troubleshooting; must never be logged or used as a standard log correlation ID. */
    val sykmeldingId: UUID?,
) {
    override fun toString(): String = "LocalActiveSykmeldingResult(isActive=$isActive, sykmeldingId=<redacted>)"
}

class SendtSykmeldingRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemDefaultZone(),
) : IActiveSykmeldingRepository {

    override suspend fun findActiveSykmelding(fnr: String, orgnummer: String): LocalActiveSykmeldingResult {
        val activeTomThreshold = LocalDate.now(clock).minusDays(ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS)

        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val row = SendtSykmeldingTable
                    .select(SendtSykmeldingTable.sykmeldingId)
                    .where {
                        (SendtSykmeldingTable.fnr eq fnr) and
                            (SendtSykmeldingTable.orgnummer eq orgnummer) and
                            SendtSykmeldingTable.revokedDate.isNull() and
                            (SendtSykmeldingTable.tom greaterEq activeTomThreshold)
                    }
                    .limit(1)
                    .firstOrNull()

                LocalActiveSykmeldingResult(
                    isActive = row != null,
                    sykmeldingId = row?.get(SendtSykmeldingTable.sykmeldingId),
                )
            }
        }
    }

    companion object {
        const val ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS = 16L
    }
}
