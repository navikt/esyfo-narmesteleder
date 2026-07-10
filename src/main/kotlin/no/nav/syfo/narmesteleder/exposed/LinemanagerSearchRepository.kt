package no.nav.syfo.narmesteleder.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.domain.LinemanagerManagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerPersonRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchQuery
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchResult
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.OffsetDateTime

interface ILinemanagerSearchRepository {
    suspend fun search(query: LinemanagerSearchQuery): List<LinemanagerSearchResult>
}

class LinemanagerSearchRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : ILinemanagerSearchRepository {

    override suspend fun search(query: LinemanagerSearchQuery): List<LinemanagerSearchResult> {
        val now = OffsetDateTime.now(clock)
        val employeePerson = PersonTable.alias("employee_person")
        val managerPerson = PersonTable.alias("manager_person")

        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val joinedTables = NarmestelederTable
                    .join(
                        otherTable = employeePerson,
                        joinType = JoinType.LEFT,
                        onColumn = NarmestelederTable.sykmeldtFnr,
                        otherColumn = employeePerson[PersonTable.fnr],
                    )
                    .join(
                        otherTable = managerPerson,
                        joinType = JoinType.LEFT,
                        onColumn = NarmestelederTable.narmestelederFnr,
                        otherColumn = managerPerson[PersonTable.fnr],
                    )

                joinedTables
                    .selectAll()
                    .where { query.toWhereClause(now) }
                    .orderBy(NarmestelederTable.id to SortOrder.ASC)
                    .limit(query.pageSize + 1)
                    .map { row ->
                        LinemanagerSearchResult(
                            cursor = LinemanagerSearchCursor(id = row[NarmestelederTable.id].value),
                            linemanager = LinemanagerRead(
                                orgNumber = OrganizationNumber(row[NarmestelederTable.orgnummer]),
                                activeFrom = row[NarmestelederTable.aktivFom].toInstant(),
                                employee = LinemanagerPersonRead(
                                    nationalIdentificationNumber = PersonalIdentificationNumber(row[NarmestelederTable.sykmeldtFnr]),
                                    name = row.toName(
                                        firstName = employeePerson[PersonTable.fornavn],
                                        middleName = employeePerson[PersonTable.mellomnavn],
                                        lastName = employeePerson[PersonTable.etternavn],
                                    ),
                                ),
                                manager = LinemanagerManagerRead(
                                    nationalIdentificationNumber = PersonalIdentificationNumber(row[NarmestelederTable.narmestelederFnr]),
                                    name = row.toName(
                                        firstName = managerPerson[PersonTable.fornavn],
                                        middleName = managerPerson[PersonTable.mellomnavn],
                                        lastName = managerPerson[PersonTable.etternavn],
                                    ),
                                    email = row[NarmestelederTable.narmestelederEpost],
                                    mobile = row[NarmestelederTable.narmestelederTelefonnummer],
                                ),
                            ),
                        )
                    }
            }
        }
    }

    private fun LinemanagerSearchQuery.toWhereClause(now: OffsetDateTime): Op<Boolean> {
        val filters = mutableListOf<Op<Boolean>>(
            NarmestelederTable.orgnummer eq orgNumber.value,
            NarmestelederTable.aktivTom.isNull(),
            NarmestelederTable.aktivFom lessEq now,
        )

        managerNationalIdentificationNumber?.let {
            filters.add(NarmestelederTable.narmestelederFnr eq it.value)
        }
        cursor?.let {
            filters.add(NarmestelederTable.id greater it.id)
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
