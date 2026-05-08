package no.nav.syfo.sykmelding.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate

interface IActiveSykmeldingRepository {
    suspend fun hasActiveSykmelding(fnr: String, orgnummer: String): Boolean
}

class SendtSykmeldingRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemDefaultZone(),
) : IActiveSykmeldingRepository {

    override suspend fun hasActiveSykmelding(fnr: String, orgnummer: String): Boolean {
        val activeTomThreshold = LocalDate.now(clock).minusDays(ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS)

        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                SendtSykmeldingTable.selectAll()
                    .where {
                        (SendtSykmeldingTable.fnr eq fnr) and
                            (SendtSykmeldingTable.orgnummer eq orgnummer) and
                            SendtSykmeldingTable.revokedDate.isNull() and
                            (SendtSykmeldingTable.tom greaterEq activeTomThreshold)
                    }
                    .limit(1)
                    .any()
            }
        }
    }

    companion object {
        const val ACTIVE_SYKMELDING_GRACE_PERIOD_DAYS = 16L
    }
}
