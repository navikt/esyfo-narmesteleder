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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SykmeldingTableTest :
    FunSpec({
        beforeTest {
            TestDB.clearAllData()
        }

        test("mapping matches the database schema") {
            transaction(TestDB.exposedDatabase) {
                checkMappingConsistence(SykmeldingTable, withLogs = true) shouldBe emptyList()
            }
        }

        test("round-trips through the table mapping") {
            transaction(TestDB.exposedDatabase) {
                val personId = insertPerson()
                insertSykmelding(personId)

                val row = SykmeldingTable.selectAll().single()
                assertSoftly {
                    row[SykmeldingTable.sykmeldingId] shouldBe SYKMELDING_ID
                    row[SykmeldingTable.personId] shouldBe personId
                    row[SykmeldingTable.fnr] shouldBe FNR
                    row[SykmeldingTable.orgnummer] shouldBe ORGNUMMER
                    row[SykmeldingTable.sentAt].toInstant() shouldBe SENT_AT.toInstant()
                    row[SykmeldingTable.maxTom] shouldBe MAX_TOM
                    row[SykmeldingTable.sourcePartition] shouldBe 1
                    row[SykmeldingTable.sourceOffset] shouldBe 42L
                }
            }
        }

        test("requires an existing person") {
            shouldThrow<ExposedSQLException> {
                transaction(TestDB.exposedDatabase) { insertSykmelding(personId = UUID.randomUUID()) }
            }.sqlState shouldBe FOREIGN_KEY_VIOLATION
        }
    })

private const val FOREIGN_KEY_VIOLATION = "23503"
private val FNR = digits(11)
private val ORGNUMMER = digits(9)
private val SYKMELDING_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
private val SENT_AT = OffsetDateTime.of(2026, 11, 2, 10, 15, 30, 123_456_000, ZoneOffset.ofHours(1))
private val MAX_TOM = LocalDate.of(2026, 11, 30)

private fun insertPerson(): UUID = PersonTable.insertAndGetId {
    it[fnr] = FNR
    it[status] = "ACTIVE"
}.value

private fun insertSykmelding(personId: UUID) = SykmeldingTable.insert {
    it[sykmeldingId] = SYKMELDING_ID
    it[SykmeldingTable.personId] = personId
    it[fnr] = FNR
    it[orgnummer] = ORGNUMMER
    it[sentAt] = SENT_AT
    it[maxTom] = MAX_TOM
    it[sourcePartition] = 1
    it[sourceOffset] = 42L
}
