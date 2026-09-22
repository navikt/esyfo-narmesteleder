package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.NarmestelederEntity
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedNarmestelederrelasjonRepositoryTest :
    DescribeSpec({
        val now = Instant.parse("2026-02-01T12:00:00Z")
        val repository = ExposedNarmestelederrelasjonRepository(
            TestDB.exposedDatabase,
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val employeeIdent = "12345678901"
        val managerIdent = "10987654321"
        val activeFrom = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(1)

        beforeTest {
            TestDB.clearNarmestelederData()
            TestDB.clearPersonData()
            TestDB.clearSendtSykmeldingData()
        }

        fun insertRelation(
            id: UUID = UUID.randomUUID(),
            from: OffsetDateTime = activeFrom,
            to: OffsetDateTime? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                NarmestelederEntity.new {
                    narmesteLederId = id
                    orgnummer = "123456789"
                    sykmeldtFnr = employeeIdent
                    narmestelederFnr = managerIdent
                    narmestelederTelefonnummer = "90000000"
                    narmestelederEpost = "manager@example.com"
                    arbeidsgiverForskutterer = true
                    aktivFom = from
                    aktivTom = to
                }
            }
        }

        fun insertPerson(ident: String, firstName: String, middleName: String? = null, lastName: String) {
            transaction(TestDB.exposedDatabase) {
                personTable.batchInsertIgnoreExisting(
                    listOf(
                        PersonBatchInsertRow(
                            fnr = ident,
                            status = "ENRICHED",
                            fornavn = firstName,
                            mellomnavn = middleName,
                            etternavn = lastName,
                            foedselsdato = LocalDate.parse("1990-01-01"),
                        ),
                    ),
                )
            }
        }

        fun insertActiveSykmelding() {
            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingTable.insert {
                    it[SendtSykmeldingTable.sykmeldingId] = UUID.randomUUID()
                    it[SendtSykmeldingTable.orgnummer] = "123456789"
                    it[SendtSykmeldingTable.syketilfelleStartDato] = now.atZone(ZoneOffset.UTC).toLocalDate()
                    it[SendtSykmeldingTable.fnr] = employeeIdent
                    it[SendtSykmeldingTable.fom] = now.atZone(ZoneOffset.UTC).toLocalDate()
                    it[SendtSykmeldingTable.tom] = now.atZone(ZoneOffset.UTC).toLocalDate()
                    it[SendtSykmeldingTable.revokedDate] = null
                }
            }
        }

        it("returns a current relation with employee name when it has an active sykmelding") {
            val id = UUID.randomUUID()
            insertPerson(employeeIdent, "Employee", "Middle", "Person")
            insertRelation(id)
            insertActiveSykmelding()

            val relation = repository.findActiveById(id)

            relation?.id shouldBe id
            relation?.orgNumber shouldBe "123456789"
            relation?.employee?.nationalIdentificationNumber shouldBe employeeIdent
            relation?.employee?.name?.firstName shouldBe "Employee"
        }

        it("returns names as null when person projections are absent") {
            val id = UUID.randomUUID()
            insertRelation(id)
            insertActiveSykmelding()

            val relation = repository.findActiveById(id)

            relation?.employee?.name.shouldBeNull()
        }

        it("excludes a revoked relation") {
            val id = UUID.randomUUID()
            insertRelation(id, to = activeFrom.plusSeconds(1))
            insertActiveSykmelding()

            repository.findActiveById(id).shouldBeNull()
        }

        it("excludes a future relation") {
            val id = UUID.randomUUID()
            insertRelation(id, from = activeFrom.plusDays(2))
            insertActiveSykmelding()

            repository.findActiveById(id).shouldBeNull()
        }

        it("excludes a relation without an active sykmelding") {
            val id = UUID.randomUUID()
            insertRelation(id)

            repository.findActiveById(id).shouldBeNull()
        }
    })
