package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Inserts a row in `narmeste_leder` and returns its database id. */
internal fun insertRelation(
    narmestelederId: UUID = UUID.randomUUID(),
    employeeIdent: String = "12345678901",
    managerIdent: String = "10987654321",
    organizationNumber: String = "123456789",
    activeFrom: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
    activeTo: OffsetDateTime? = null,
    email: String = "leder@example.com",
    mobile: String = "99999999",
    employerAdvancesPay: Boolean? = null,
): Int = transaction(TestDB.exposedDatabase) {
    NarmestelederTable.insertAndGetId {
        it[NarmestelederTable.narmestelederId] = narmestelederId
        it[orgnummer] = organizationNumber
        it[sykmeldtFnr] = employeeIdent
        it[narmestelederFnr] = managerIdent
        it[narmestelederTelefonnummer] = mobile
        it[narmestelederEpost] = email
        it[arbeidsgiverForskutterer] = employerAdvancesPay
        it[aktivFom] = activeFrom
        it[aktivTom] = activeTo
    }.value
}

/** Inserts a row in `person`; [created] falls back to the database default when null. */
internal fun insertPerson(
    fnr: String,
    status: String = PersonStatus.ENRICHED.name,
    firstName: String? = null,
    middleName: String? = null,
    lastName: String? = null,
    birthDate: LocalDate? = null,
    created: OffsetDateTime? = null,
) {
    transaction(TestDB.exposedDatabase) {
        PersonTable.insert {
            it[PersonTable.fnr] = fnr
            it[PersonTable.status] = status
            it[fornavn] = firstName
            it[mellomnavn] = middleName
            it[etternavn] = lastName
            it[foedselsdato] = birthDate
            if (created != null) it[PersonTable.created] = created
        }
    }
}
