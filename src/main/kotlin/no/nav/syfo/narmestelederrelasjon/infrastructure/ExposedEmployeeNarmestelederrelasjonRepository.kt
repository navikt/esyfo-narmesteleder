package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmestelederrelasjonRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.OffsetDateTime

class ExposedEmployeeNarmestelederrelasjonRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EmployeeNarmestelederrelasjonRepository {
    override suspend fun findActive(
        employeeIdent: PersonIdent,
        organizationNumber: OrganizationNumber?,
    ): List<EmployeeNarmestelederrelasjon> = withContext(dispatcher) {
        suspendTransaction(db = database) {
            NarmestelederTable
                .join(
                    otherTable = PersonTable,
                    joinType = JoinType.LEFT,
                    onColumn = NarmestelederTable.narmestelederFnr,
                    otherColumn = PersonTable.fnr,
                )
                .select(
                    listOf(
                        NarmestelederTable.narmestelederId,
                        NarmestelederTable.orgnummer,
                        NarmestelederTable.aktivFom,
                        NarmestelederTable.narmestelederEpost,
                        NarmestelederTable.narmestelederTelefonnummer,
                        PersonTable.fornavn,
                        PersonTable.mellomnavn,
                        PersonTable.etternavn,
                    ),
                )
                .where {
                    val activeForEmployee = (NarmestelederTable.sykmeldtFnr eq employeeIdent.value) and
                        NarmestelederTable.aktivTom.isNull() and
                        (NarmestelederTable.aktivFom lessEq OffsetDateTime.now(clock))
                    organizationNumber
                        ?.let { activeForEmployee and (NarmestelederTable.orgnummer eq it.value) }
                        ?: activeForEmployee
                }
                .orderBy(
                    NarmestelederTable.orgnummer to SortOrder.ASC,
                    NarmestelederTable.aktivFom to SortOrder.DESC,
                    NarmestelederTable.id to SortOrder.ASC,
                )
                .map { row ->
                    EmployeeNarmestelederrelasjon(
                        id = row[NarmestelederTable.narmestelederId],
                        organizationNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                        activeFrom = row[NarmestelederTable.aktivFom].toInstant(),
                        managerFirstName = row[PersonTable.fornavn],
                        managerMiddleName = row[PersonTable.mellomnavn],
                        managerLastName = row[PersonTable.etternavn],
                        managerEmail = row[NarmestelederTable.narmestelederEpost],
                        managerMobile = row[NarmestelederTable.narmestelederTelefonnummer],
                    )
                }
        }
    }
}
