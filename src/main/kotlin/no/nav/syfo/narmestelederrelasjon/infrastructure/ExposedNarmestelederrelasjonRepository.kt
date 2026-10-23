package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.RevocableNarmestelederrelasjon
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
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

        return database.ioTransaction {
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
                        NarmestelederTable.aktivFom,
                        NarmestelederTable.aktivTom,
                    ),
                )
                .where { NarmestelederTable.narmestelederId eq id }
                .limit(1)
                .map { row ->
                    NarmestelederrelasjonLookup(
                        id = row[NarmestelederTable.narmestelederId],
                        organizationNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                        employeeIdent = PersonalIdentificationNumber(row[NarmestelederTable.sykmeldtFnr]),
                        employeeFirstName = row[employeePerson[PersonTable.fornavn]],
                        employeeMiddleName = row[employeePerson[PersonTable.mellomnavn]],
                        employeeLastName = row[employeePerson[PersonTable.etternavn]],
                        isActive = row[NarmestelederTable.aktivTom] == null &&
                            !row[NarmestelederTable.aktivFom].isAfter(now),
                    )
                }
                .singleOrNull()
        }
    }

    override suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon? = database.ioTransaction {
        NarmestelederTable
            .select(
                NarmestelederTable.narmestelederId,
                NarmestelederTable.sykmeldtFnr,
                NarmestelederTable.narmestelederFnr,
                NarmestelederTable.orgnummer,
                NarmestelederTable.aktivTom,
            )
            .where { NarmestelederTable.narmestelederId eq id }
            .limit(1)
            .map { row ->
                RevocableNarmestelederrelasjon(
                    id = row[NarmestelederTable.narmestelederId],
                    employeeIdent = PersonalIdentificationNumber(row[NarmestelederTable.sykmeldtFnr]),
                    managerIdent = PersonalIdentificationNumber(row[NarmestelederTable.narmestelederFnr]),
                    organizationNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                    isActive = row[NarmestelederTable.aktivTom] == null,
                )
            }
            .singleOrNull()
    }
}
