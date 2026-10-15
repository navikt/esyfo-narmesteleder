package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.RegisteredPerson
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
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
            TestDB.clearPersonData()
        }

        fun insertPerson(fnr: String, status: PersonStatus = PersonStatus.PENDING, created: OffsetDateTime = longAgo) {
            transaction(TestDB.exposedDatabase) {
                PersonTable.insert {
                    it[PersonTable.fnr] = fnr
                    it[PersonTable.status] = status.name
                    it[PersonTable.fornavn] = "Old"
                    it[PersonTable.etternavn] = "Name"
                    it[PersonTable.created] = created
                }
            }
        }

        fun person(fnr: String): ResultRow = transaction(TestDB.exposedDatabase) {
            PersonTable.selectAll().where { PersonTable.fnr eq fnr }.single()
        }

        test("findPending returns the oldest pending persons up to the limit") {
            insertPerson(first, created = longAgo.plusDays(2))
            insertPerson(second)
            insertPerson("11111111111", PersonStatus.ENRICHED, created = longAgo.minusDays(1))
            insertPerson("22222222222", created = longAgo.plusDays(1))

            repository.findPending(2) shouldBe listOf(PersonIdent(second), PersonIdent("22222222222"))
        }

        test("saveEnrichment stores details for enriched persons and only the status for persons not found") {
            insertPerson(first)
            insertPerson(second)

            repository.saveEnrichment(
                enriched = mapOf(PersonIdent(first) to ada),
                notFound = listOf(PersonIdent(second)),
            )

            with(person(first)) {
                this[PersonTable.status] shouldBe PersonStatus.ENRICHED.name
                this[PersonTable.fornavn] shouldBe ada.firstName
                this[PersonTable.mellomnavn] shouldBe ada.middleName
                this[PersonTable.etternavn] shouldBe ada.lastName
                this[PersonTable.foedselsdato] shouldBe ada.birthDate
            }
            with(person(second)) {
                this[PersonTable.status] shouldBe PersonStatus.NOT_FOUND.name
                this[PersonTable.fornavn] shouldBe "Old"
            }
        }
    })
