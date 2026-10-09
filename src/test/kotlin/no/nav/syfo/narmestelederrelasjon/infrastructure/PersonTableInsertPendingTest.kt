package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PersonTableInsertPendingTest :
    FunSpec({
        beforeTest {
            TestDB.clearPersonData()
        }

        test("PersonTable mapping matches the database schema") {
            transaction(TestDB.exposedDatabase) {
                checkMappingConsistence(PersonTable, withLogs = true) shouldBe emptyList()
            }
        }

        context("PersonTable.insertPendingIgnoringExisting") {
            test("inserts each new fnr once as pending without names") {
                insertPending(listOf("12345678901", "10987654321", "12345678901"))

                persons() shouldBe mapOf(
                    "12345678901" to Person(PersonStatus.PENDING.name, fornavn = null),
                    "10987654321" to Person(PersonStatus.PENDING.name, fornavn = null),
                )
            }

            test("leaves existing persons untouched and still inserts new fnrs") {
                transaction(TestDB.exposedDatabase) {
                    PersonTable.insert {
                        it[fnr] = "12345678901"
                        it[status] = "ACTIVE"
                        it[fornavn] = "Ada"
                    }
                }

                insertPending(listOf("12345678901", "10987654321"))

                persons() shouldBe mapOf(
                    "12345678901" to Person("ACTIVE", fornavn = "Ada"),
                    "10987654321" to Person(PersonStatus.PENDING.name, fornavn = null),
                )
            }

            test("does nothing for an empty list") {
                insertPending(emptyList())

                persons() shouldBe emptyMap()
            }
        }
    })

private data class Person(val status: String, val fornavn: String?)

private fun insertPending(fnrs: List<String>) = transaction(TestDB.exposedDatabase) {
    PersonTable.insertPendingIgnoringExisting(fnrs)
}

private fun persons(): Map<String, Person> = transaction(TestDB.exposedDatabase) {
    PersonTable.selectAll().associate { it[PersonTable.fnr] to Person(it[PersonTable.status], it[PersonTable.fornavn]) }
}
