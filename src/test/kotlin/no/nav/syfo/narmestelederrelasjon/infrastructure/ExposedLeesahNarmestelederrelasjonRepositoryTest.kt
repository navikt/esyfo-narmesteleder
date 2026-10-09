package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.NarmestelederEntity
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.narmestelederLeesahKafkaMessage
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate

class ExposedLeesahNarmestelederrelasjonRepositoryTest :
    DescribeSpec({
        val repository = ExposedLeesahNarmestelederrelasjonRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearNarmestelederData()
            TestDB.clearPersonData()
        }

        describe("upsertAll") {
            it("upserts replayed relations idempotently") {
                val original = narmestelederLeesahKafkaMessage().copy(narmesteLederTelefonnummer = "11111111")
                val replayed = original.copy(
                    narmesteLederTelefonnummer = "22222222",
                    narmesteLederEpost = "updated@example.com",
                    aktivTom = LocalDate.of(2025, 1, 31),
                )

                repository.upsertAll(listOf(original.toLeesahNarmestelederrelasjon()), emptyList())
                repository.upsertAll(listOf(replayed.toLeesahNarmestelederrelasjon()), emptyList())

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq original.narmesteLederId
                    }
                    results.count() shouldBe 1
                    val entity = results.first()
                    entity.narmestelederTelefonnummer shouldBe replayed.narmesteLederTelefonnummer
                    entity.narmestelederEpost shouldBe replayed.narmesteLederEpost
                    entity.aktivTom?.toLocalDate() shouldBe replayed.aktivTom
                }
            }

            it("stores every relation and inserts the given persons as pending") {
                val first = narmestelederLeesahKafkaMessage()
                val second = narmestelederLeesahKafkaMessage()

                repository.upsertAll(
                    listOf(first.toLeesahNarmestelederrelasjon(), second.toLeesahNarmestelederrelasjon()),
                    listOf("12345678901", "10987654321"),
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederEntity.all().map { it.narmesteLederId }.toSet() shouldBe
                        setOf(first.narmesteLederId, second.narmesteLederId)
                    PersonEntity.all().associate { it.fnr to it.status } shouldBe mapOf(
                        "12345678901" to PersonStatus.PENDING.name,
                        "10987654321" to PersonStatus.PENDING.name,
                    )
                }
            }

            it("keeps existing persons without overwriting them") {
                transaction(TestDB.exposedDatabase) {
                    personTable.batchInsertIgnoreExisting(
                        listOf(
                            PersonBatchInsertRow(
                                fnr = "12345678901",
                                status = "ACTIVE",
                                fornavn = "Ada",
                                etternavn = "Lovelace",
                            ),
                        ),
                    )
                }

                repository.upsertAll(
                    listOf(narmestelederLeesahKafkaMessage().toLeesahNarmestelederrelasjon()),
                    listOf("12345678901", "10987654321"),
                )

                transaction(TestDB.exposedDatabase) {
                    val persons = PersonEntity.all().associateBy { it.fnr }
                    persons.keys shouldBe setOf("12345678901", "10987654321")
                    persons.getValue("12345678901").status shouldBe "ACTIVE"
                    persons.getValue("12345678901").fornavn shouldBe "Ada"
                    persons.getValue("10987654321").status shouldBe PersonStatus.PENDING.name
                }
            }
        }
    })
