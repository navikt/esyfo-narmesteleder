package no.nav.syfo.sykmelding.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.digits
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonTable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class NarmestelederConfirmationTableTest :
    FunSpec({
        beforeTest {
            TestDB.clearAllData()
        }

        test("mapping matches the database schema") {
            transaction(TestDB.exposedDatabase) {
                checkMappingConsistence(NarmestelederConfirmationTable, withLogs = true) shouldBe emptyList()
            }
        }

        test("round-trips through the table mapping without a sykmelding row") {
            transaction(TestDB.exposedDatabase) {
                val personId = insertPerson()
                insertConfirmation(personId)

                val row = NarmestelederConfirmationTable.selectAll().single()
                assertSoftly {
                    row[NarmestelederConfirmationTable.sykmeldingId] shouldBe SYKMELDING_ID
                    row[NarmestelederConfirmationTable.personId] shouldBe personId
                    row[NarmestelederConfirmationTable.orgnummer] shouldBe ORGNUMMER
                    row[NarmestelederConfirmationTable.confirmedAt].toInstant() shouldBe CONFIRMED_AT.toInstant()
                }
            }
        }

        test("requires an existing person") {
            shouldThrow<ExposedSQLException> {
                transaction(TestDB.exposedDatabase) { insertConfirmation(personId = UUID.randomUUID()) }
            }.sqlState shouldBe FOREIGN_KEY_VIOLATION
        }
    })

private const val FOREIGN_KEY_VIOLATION = "23503"
private val ORGNUMMER = digits(9)
private val SYKMELDING_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
private val CONFIRMED_AT = OffsetDateTime.of(2026, 11, 2, 10, 15, 30, 123_456_000, ZoneOffset.ofHours(1))

private fun insertPerson(): UUID = PersonTable.insertAndGetId {
    it[fnr] = digits(11)
    it[status] = "ACTIVE"
}.value

private fun insertConfirmation(personId: UUID) = NarmestelederConfirmationTable.insert {
    it[sykmeldingId] = SYKMELDING_ID
    it[NarmestelederConfirmationTable.personId] = personId
    it[orgnummer] = ORGNUMMER
    it[confirmedAt] = CONFIRMED_AT
}
