package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RegisteredPerson
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedRelationPersonRepositoryTest :
    FunSpec({
        val repository = ExposedRelationPersonRepository(TestDB.exposedDatabase)
        val ada = RegisteredPerson("Ada", "Augusta", "Lovelace", LocalDate.of(1985, 12, 10))
        val longAgo = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val first = "12345678901"
        val second = "10987654321"

        beforeTest {
            TestDB.clearAllData()
        }

        fun insertStoredPerson(fnr: String, status: PersonStatus = PersonStatus.PENDING, created: OffsetDateTime = longAgo) = insertPerson(fnr, status = status.name, firstName = "Old", lastName = "Name", created = created)

        fun person(fnr: String): ResultRow = transaction(TestDB.exposedDatabase) {
            PersonTable.selectAll().where { PersonTable.fnr eq fnr }.single()
        }

        test("findPending returns the oldest pending persons up to the limit") {
            insertStoredPerson(first, created = longAgo.plusDays(2))
            insertStoredPerson(second)
            insertStoredPerson("11111111111", PersonStatus.ENRICHED, created = longAgo.minusDays(1))
            insertStoredPerson("22222222222", created = longAgo.plusDays(1))

            repository.findPending(2) shouldBe listOf(PersonalIdentificationNumber(second), PersonalIdentificationNumber("22222222222"))
        }

        test("saveEnrichment stores details for enriched persons and only the status for persons not found") {
            insertStoredPerson(first)
            insertStoredPerson(second)

            repository.saveEnrichment(
                enriched = mapOf(PersonalIdentificationNumber(first) to ada),
                notFound = listOf(PersonalIdentificationNumber(second)),
            )

            assertSoftly(person(first)) {
                this[PersonTable.status] shouldBe PersonStatus.ENRICHED.name
                this[PersonTable.fornavn] shouldBe ada.firstName
                this[PersonTable.mellomnavn] shouldBe ada.middleName
                this[PersonTable.etternavn] shouldBe ada.lastName
                this[PersonTable.foedselsdato] shouldBe ada.birthDate
            }
            assertSoftly(person(second)) {
                this[PersonTable.status] shouldBe PersonStatus.NOT_FOUND.name
                this[PersonTable.fornavn] shouldBe "Old"
            }
        }

        test("findExisting returns only persons in the projection") {
            insertStoredPerson(first, status = PersonStatus.NOT_FOUND)

            repository.findExisting(listOf(PersonalIdentificationNumber(first), PersonalIdentificationNumber(second))) shouldBe listOf(PersonalIdentificationNumber(first))
        }

        test("updateRegisteredDetails updates details and timestamp without changing status and returns updated persons") {
            insertStoredPerson(first, status = PersonStatus.ENRICHED)
            val updatedBefore = person(first)[PersonTable.updated]

            val updated = repository.updateRegisteredDetails(mapOf(PersonalIdentificationNumber(first) to ada, PersonalIdentificationNumber(second) to ada))

            updated shouldBe setOf(PersonalIdentificationNumber(first))
            assertSoftly(person(first)) {
                this[PersonTable.status] shouldBe PersonStatus.ENRICHED.name
                this[PersonTable.fornavn] shouldBe ada.firstName
                this[PersonTable.mellomnavn] shouldBe ada.middleName
                this[PersonTable.etternavn] shouldBe ada.lastName
                this[PersonTable.foedselsdato] shouldBe ada.birthDate
                this[PersonTable.updated] shouldNotBe updatedBefore
            }
        }
    })
