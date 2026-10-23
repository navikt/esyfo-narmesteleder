package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjonRepository
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select

class ExposedActiveNarmestelederrelasjonRepository(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActiveNarmestelederrelasjonRepository {
    override suspend fun findActive(
        employeeIdent: PersonalIdentificationNumber,
        organizationNumber: OrganizationNumber,
    ): List<ActiveNarmestelederrelasjon> = database.ioTransaction(dispatcher) {
        NarmestelederTable
            .select(
                NarmestelederTable.narmestelederFnr,
                NarmestelederTable.narmestelederEpost,
                NarmestelederTable.aktivFom,
                NarmestelederTable.narmestelederId,
            )
            .where {
                (NarmestelederTable.sykmeldtFnr eq employeeIdent.value) and
                    (NarmestelederTable.orgnummer eq organizationNumber.value) and
                    NarmestelederTable.aktivTom.isNull()
            }
            .orderBy(
                NarmestelederTable.aktivFom to SortOrder.DESC,
                NarmestelederTable.narmestelederId to SortOrder.DESC,
            )
            .map { row ->
                ActiveNarmestelederrelasjon(
                    id = row[NarmestelederTable.narmestelederId],
                    managerIdent = PersonalIdentificationNumber(row[NarmestelederTable.narmestelederFnr]),
                    managerEmail = row[NarmestelederTable.narmestelederEpost],
                    activeFrom = row[NarmestelederTable.aktivFom].toInstant(),
                )
            }
    }
}
