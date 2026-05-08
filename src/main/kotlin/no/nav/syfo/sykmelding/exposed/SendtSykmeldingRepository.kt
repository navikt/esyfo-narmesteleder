package no.nav.syfo.sykmelding.exposed

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

interface IActiveSykmeldingRepository {
    suspend fun hasActiveSykmelding(fnr: String, orgnummer: String): Boolean
}

class SendtSykmeldingRepository(
    private val database: Database,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Oslo")),
) : IActiveSykmeldingRepository {
    override suspend fun hasActiveSykmelding(fnr: String, orgnummer: String): Boolean {
        val cutoffDate = LocalDate.now(clock).minusDays(ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS)
        return suspendTransaction(db = database, readOnly = true) {
            SendtSykmeldingTable
                .selectAll()
                .where {
                    (SendtSykmeldingTable.fnr eq fnr) and
                        (SendtSykmeldingTable.orgnummer eq orgnummer) and
                        SendtSykmeldingTable.revokedDate.isNull() and
                        (SendtSykmeldingTable.tom greaterEq cutoffDate)
                }
                .limit(1)
                .any()
        }
    }

    companion object {
        const val ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS = 16L
    }
}
