package no.nav.syfo.narmesteleder.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.domain.EmailAddress
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerLookupResult
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerQuery
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerRead
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.parseEmailAddresses
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
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

interface IEmployeeLinemanagerRepository {
    suspend fun findActiveForEmployee(query: EmployeeLinemanagerQuery): EmployeeLinemanagerLookupResult
}

class EmployeeLinemanagerRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : IEmployeeLinemanagerRepository {

    override suspend fun findActiveForEmployee(query: EmployeeLinemanagerQuery): EmployeeLinemanagerLookupResult = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val results = NarmestelederTable
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
                .where { query.toWhereClause(OffsetDateTime.now(clock)) }
                .orderBy(
                    NarmestelederTable.orgnummer to SortOrder.ASC,
                    NarmestelederTable.aktivFom to SortOrder.DESC,
                    NarmestelederTable.id to SortOrder.ASC,
                )
                .map { row ->
                    val parsedEmailAddresses = row[NarmestelederTable.narmestelederEpost].parseEmailAddresses()
                    EmployeeLinemanagerRead(
                        id = row[NarmestelederTable.narmestelederId],
                        orgNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                        activeFrom = row[NarmestelederTable.aktivFom].toInstant(),
                        name = row.toName(
                            firstName = PersonTable.fornavn,
                            middleName = PersonTable.mellomnavn,
                            lastName = PersonTable.etternavn,
                        ),
                        emailAddresses = parsedEmailAddresses.validEmailAddresses.map(EmailAddress::value),
                        mobile = row[NarmestelederTable.narmestelederTelefonnummer],
                    ) to parsedEmailAddresses.discardedEmailAddressCount
                }

            EmployeeLinemanagerLookupResult(
                linemanagers = results.map { it.first },
                discardedEmailAddressCount = results.sumOf { it.second },
            )
        }
    }

    private fun EmployeeLinemanagerQuery.toWhereClause(now: OffsetDateTime): Op<Boolean> {
        val filters = mutableListOf(
            NarmestelederTable.sykmeldtFnr eq employeeNationalIdentificationNumber.value,
            NarmestelederTable.aktivTom.isNull(),
            NarmestelederTable.aktivFom lessEq now,
        )
        orgNumber?.let {
            filters.add(NarmestelederTable.orgnummer eq it.value)
        }
        return filters.reduce(Op<Boolean>::and)
    }
}

private fun ResultRow.toName(
    firstName: Expression<String?>,
    middleName: Expression<String?>,
    lastName: Expression<String?>,
): Name? {
    val resolvedFirstName = this[firstName]
    val resolvedLastName = this[lastName]

    return if (resolvedFirstName != null && resolvedLastName != null) {
        Name(
            firstName = resolvedFirstName,
            middleName = this[middleName],
            lastName = resolvedLastName,
        )
    } else {
        null
    }
}
