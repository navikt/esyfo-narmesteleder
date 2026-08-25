package no.nav.syfo.narmesteleder.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Instant
import java.util.UUID

data class ActiveNarmestelederEntity(
    val narmestelederId: UUID,
    val narmestelederFnr: PersonalIdentificationNumber,
    val narmestelederEpost: String,
    val aktivFom: Instant,
)

interface INarmestelederLookupDb {
    suspend fun findActiveNarmesteledere(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): List<ActiveNarmestelederEntity>
}

class NarmestelederLookupDb(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : INarmestelederLookupDb {
    override suspend fun findActiveNarmesteledere(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): List<ActiveNarmestelederEntity> = withContext(dispatcher) {
        suspendTransaction(db = database) {
            NarmestelederTable
                .select(
                    NarmestelederTable.narmestelederFnr,
                    NarmestelederTable.narmestelederEpost,
                    NarmestelederTable.aktivFom,
                    NarmestelederTable.narmestelederId,
                )
                .where {
                    (NarmestelederTable.sykmeldtFnr eq sykmeldtFnr.value) and
                        (NarmestelederTable.orgnummer eq orgnummer.value) and
                        NarmestelederTable.aktivTom.isNull()
                }
                .orderBy(
                    NarmestelederTable.aktivFom to SortOrder.DESC,
                    NarmestelederTable.narmestelederId to SortOrder.DESC,
                )
                .map { row ->
                    ActiveNarmestelederEntity(
                        narmestelederId = row[NarmestelederTable.narmestelederId],
                        narmestelederFnr = PersonalIdentificationNumber(row[NarmestelederTable.narmestelederFnr]),
                        narmestelederEpost = row[NarmestelederTable.narmestelederEpost],
                        aktivFom = row[NarmestelederTable.aktivFom].toInstant(),
                    )
                }
        }
    }
}
