package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.exposed.NarmestelederEntity
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.narmestelederrelasjon.application.RevocableNarmestelederrelasjon
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
        }

        fun insertRelation(
            id: UUID = UUID.randomUUID(),
            from: OffsetDateTime = activeFrom,
            to: OffsetDateTime? = null,
            employee: String = employeeIdent,
            manager: String = managerIdent,
        ) {
            transaction(TestDB.exposedDatabase) {
                NarmestelederEntity.new {
                    narmesteLederId = id
                    orgnummer = "123456789"
                    sykmeldtFnr = employee
                    narmestelederFnr = manager
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

        it("returns a current relation with employee name") {
            val id = UUID.randomUUID()
            insertPerson(employeeIdent, "Employee", "Middle", "Person")
            insertRelation(id)

            val relation = repository.findById(id)

            relation?.id shouldBe id
            relation?.organizationNumber shouldBe OrganizationNumber("123456789")
            relation?.employeeIdent shouldBe PersonIdent(employeeIdent)
            relation?.employeeFirstName shouldBe "Employee"
            relation?.isActive shouldBe true
        }

        it("returns names as null when person projections are absent") {
            val id = UUID.randomUUID()
            insertRelation(id)

            val relation = repository.findById(id)

            relation?.employeeFirstName.shouldBeNull()
        }

        it("marks a revoked relation as inactive") {
            val id = UUID.randomUUID()
            insertRelation(id, to = activeFrom.plusSeconds(1))

            repository.findById(id)?.isActive shouldBe false
        }

        it("marks a future relation as inactive") {
            val id = UUID.randomUUID()
            insertRelation(id, from = activeFrom.plusDays(2))

            repository.findById(id)?.isActive shouldBe false
        }

        it("returns null for an unknown relation") {
            repository.findById(UUID.randomUUID()).shouldBeNull()
        }

        it("finds a revocable relation with both parties and organization without a person projection") {
            val id = UUID.randomUUID()
            insertRelation(id)

            repository.findRevocableById(id) shouldBe RevocableNarmestelederrelasjon(
                id = id,
                employeeIdent = PersonIdent(employeeIdent),
                managerIdent = PersonIdent(managerIdent),
                organizationNumber = OrganizationNumber("123456789"),
                isActive = true,
            )
        }

        it("finds a revoked relation as inactive") {
            val id = UUID.randomUUID()
            insertRelation(id, to = activeFrom.plusSeconds(1))

            repository.findRevocableById(id)?.isActive shouldBe false
        }

        it("finds a future-dated relation as revocable") {
            val id = UUID.randomUUID()
            insertRelation(id, from = activeFrom.plusDays(2))

            repository.findRevocableById(id)?.isActive shouldBe true
        }

        it("returns null for an unknown revoke id") {
            insertRelation()

            repository.findRevocableById(UUID.randomUUID()).shouldBeNull()
        }

        it("does not return a revoke relation belonging to another id") {
            val other = UUID.randomUUID()
            val wanted = UUID.randomUUID()
            insertRelation(other, employee = "12345678902", manager = "10987654322")
            insertRelation(wanted)

            repository.findRevocableById(other)?.employeeIdent shouldBe PersonIdent("12345678902")
            repository.findRevocableById(other)?.managerIdent shouldBe PersonIdent("10987654322")
            repository.findRevocableById(wanted)?.employeeIdent shouldBe PersonIdent(employeeIdent)
        }
    })
