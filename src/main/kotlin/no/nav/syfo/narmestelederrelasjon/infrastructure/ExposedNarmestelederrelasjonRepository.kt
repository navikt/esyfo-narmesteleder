package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationPersonName
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class ExposedNarmestelederrelasjonRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : NarmestelederrelasjonRepository {

    override suspend fun findActiveById(id: UUID): Narmestelederrelasjon? {
        val now = OffsetDateTime.now(clock)
        val today = now.toLocalDate()
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
                            (NarmestelederTable.aktivFom lessEq now) and
                            exists(activeSykmeldingQuery(today))
                    }
                    .limit(1)
                    .map { row ->
                        Narmestelederrelasjon(
                            id = row[NarmestelederTable.narmestelederId],
                            orgNumber = row[NarmestelederTable.orgnummer],
                            employee = RelationPerson(
                                nationalIdentificationNumber = row[NarmestelederTable.sykmeldtFnr],
                                name = row.toRelationPersonName(
                                    firstName = employeePerson[PersonTable.fornavn],
                                    middleName = employeePerson[PersonTable.mellomnavn],
                                    lastName = employeePerson[PersonTable.etternavn],
                                ),
                            ),
                        )
                    }
                    .singleOrNull()
            }
        }
    }

    private fun activeSykmeldingQuery(today: LocalDate) = SendtSykmeldingTable
        .select(SendtSykmeldingTable.id)
        .where {
            (SendtSykmeldingTable.fnr eq NarmestelederTable.sykmeldtFnr) and
                (SendtSykmeldingTable.orgnummer eq NarmestelederTable.orgnummer) and
                (SendtSykmeldingTable.tom greaterEq today) and
                (SendtSykmeldingTable.revokedDate.isNull() or (SendtSykmeldingTable.revokedDate greaterEq today))
        }
}

private fun ResultRow.toRelationPersonName(
    firstName: Expression<String?>,
    middleName: Expression<String?>,
    lastName: Expression<String?>,
): RelationPersonName? {
    val resolvedFirstName = this[firstName]
    val resolvedLastName = this[lastName]

    return if (resolvedFirstName != null && resolvedLastName != null) {
        RelationPersonName(
            firstName = resolvedFirstName,
            middleName = this[middleName],
            lastName = resolvedLastName,
        )
    } else {
        null
    }
}
