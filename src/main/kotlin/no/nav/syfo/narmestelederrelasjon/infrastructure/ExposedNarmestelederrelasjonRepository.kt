package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class ExposedNarmestelederrelasjonRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : NarmestelederrelasjonRepository {

    override suspend fun findById(id: UUID): NarmestelederrelasjonLookup? {
        val now = OffsetDateTime.now(clock)
        val employeePerson = PersonTable.alias("employee_person")

        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                NarmestelederTable
                    .join(
                        otherTable = employeePerson,
                        joinType = JoinType.LEFT,
                        onColumn = NarmestelederTable.sykmeldtFnr,
                        otherColumn = employeePerson[PersonTable.fnr],
                    )
                    .select(
                        listOf(
                            NarmestelederTable.narmestelederId,
                            NarmestelederTable.orgnummer,
                            NarmestelederTable.sykmeldtFnr,
                            employeePerson[PersonTable.fornavn],
                            employeePerson[PersonTable.mellomnavn],
                            employeePerson[PersonTable.etternavn],
                        ),
                    )
                    .where {
                        (NarmestelederTable.narmestelederId eq id) and
                            NarmestelederTable.aktivTom.isNull() and
                            (NarmestelederTable.aktivFom lessEq now)
                    }
                    .limit(1)
                    .map { row ->
                        NarmestelederrelasjonLookup(
                            id = row[NarmestelederTable.narmestelederId],
                            organizationNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                            employeeIdent = PersonIdent(row[NarmestelederTable.sykmeldtFnr]),
                            employeeFirstName = row[employeePerson[PersonTable.fornavn]],
                            employeeMiddleName = row[employeePerson[PersonTable.mellomnavn]],
                            employeeLastName = row[employeePerson[PersonTable.etternavn]],
                        )
                    }
                    .singleOrNull()
            }
        }
    }
}
