package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.leesah.validated
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.narmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonRepositoryTest :
    FunSpec({
        val repository = ExposedLeesahNarmestelederrelasjonRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearAllData()
        }

        test("stores every relation and inserts missing persons as pending in one call") {
            val first = narmestelederLeesahKafkaMessage().toLeesahNarmestelederrelasjon().validated()
            val second = narmestelederLeesahKafkaMessage().toLeesahNarmestelederrelasjon().validated()
            insertPerson("12345678901", status = "ACTIVE")

            repository.upsertAll(listOf(first, second), listOf("12345678901", "10987654321").map(::PersonalIdentificationNumber))

            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.selectAll().map { it[NarmestelederTable.narmestelederId] }.toSet() shouldBe
                    setOf(first.narmestelederId, second.narmestelederId)
                PersonTable.selectAll().associate { it[PersonTable.fnr] to it[PersonTable.status] } shouldBe mapOf(
                    "12345678901" to "ACTIVE",
                    "10987654321" to PersonStatus.PENDING.name,
                )
            }
        }
    })
