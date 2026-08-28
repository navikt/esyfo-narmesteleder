package no.nav.syfo.narmesteleder.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.util.UUID

data class RevokableNarmestelederEntity(
    val narmestelederId: UUID,
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val managerIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val isActive: Boolean,
)

interface INarmestelederRevokeDb {
    suspend fun findByNarmestelederId(narmestelederId: UUID): RevokableNarmestelederEntity?
}

class NarmestelederRevokeDb(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : INarmestelederRevokeDb {

    /**
     * Looks up a relation regardless of whether it is still active. The caller needs to tell
     * "unknown id" apart from "already revoked" to keep the revoke operation idempotent.
     */
    override suspend fun findByNarmestelederId(narmestelederId: UUID): RevokableNarmestelederEntity? = withContext(dispatcher) {
        suspendTransaction(db = database) {
            NarmestelederTable
                .select(
                    NarmestelederTable.narmestelederId,
                    NarmestelederTable.sykmeldtFnr,
                    NarmestelederTable.narmestelederFnr,
                    NarmestelederTable.orgnummer,
                    NarmestelederTable.aktivTom,
                )
                .where { NarmestelederTable.narmestelederId eq narmestelederId }
                .limit(1)
                .map { row ->
                    RevokableNarmestelederEntity(
                        narmestelederId = row[NarmestelederTable.narmestelederId],
                        employeeIdentificationNumber = PersonalIdentificationNumber(row[NarmestelederTable.sykmeldtFnr]),
                        managerIdentificationNumber = PersonalIdentificationNumber(row[NarmestelederTable.narmestelederFnr]),
                        orgNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                        isActive = row[NarmestelederTable.aktivTom] == null,
                    )
                }
                .singleOrNull()
        }
    }
}
