package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.employee.EmployeeNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.employee.EmployeeNarmestelederrelasjonRepository
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Clock
import java.time.OffsetDateTime

class ExposedEmployeeNarmestelederrelasjonRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : EmployeeNarmestelederrelasjonRepository {
    override suspend fun findActive(
        employeeIdent: PersonalIdentificationNumber,
        organizationNumber: OrganizationNumber?,
    ): List<EmployeeNarmestelederrelasjon> = database.ioTransaction {
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
                listOfNotNull(
                    NarmestelederTable.sykmeldtFnr eq employeeIdent.value,
                    NarmestelederTable.aktivTom.isNull(),
                    NarmestelederTable.aktivFom lessEq OffsetDateTime.now(clock),
                    organizationNumber?.let { NarmestelederTable.orgnummer eq it.value },
                ).compoundAnd()
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
