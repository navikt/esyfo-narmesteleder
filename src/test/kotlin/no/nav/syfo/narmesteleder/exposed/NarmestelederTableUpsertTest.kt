package no.nav.syfo.narmesteleder.exposed

import defaultLeesahKafkaMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class NarmestelederTableUpsertTest :
    DescribeSpec({
        beforeTest {
            TestDB.clearNarmestelederData()
        }
        describe("NarmestelederTable") {
            it("should not find issues with indexes in table") {
                transaction(TestDB.exposedDatabase) {
                    checkMappingConsistence(NarmestelederTable, withLogs = true) shouldBe emptyList()
                }
            }
        }
        describe("NarmestelederTable narmestelederTable.upsertFromLeesahKafkaMessage") {
            it("should insert new entity when no existing row") {
                val message = defaultLeesahKafkaMessage().copy(
                    aktivTom = LocalDate.of(2024, 12, 31),
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(message)
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq message.narmesteLederId
                    }
                    results.count() shouldBe 1

                    val entity = results.first()
                    entity.narmesteLederId shouldBe message.narmesteLederId
                    entity.orgnummer shouldBe message.orgnummer
                    entity.sykmeldtFnr shouldBe message.fnr
                    entity.narmestelederFnr shouldBe message.narmesteLederFnr
                    entity.narmestelederTelefonnummer shouldBe message.narmesteLederTelefonnummer
                    entity.narmestelederEpost shouldBe message.narmesteLederEpost
                    entity.arbeidsgiverForskutterer shouldBe message.arbeidsgiverForskutterer
                    entity.aktivFom.toInstant() shouldBe message.aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC)
                        .toInstant()
                    entity.aktivTom.shouldNotBeNull().toInstant() shouldBe message.aktivTom!!.atStartOfDay()
                        .atOffset(ZoneOffset.UTC).toInstant()
                    entity.created.shouldNotBeNull()
                    entity.updated.shouldNotBeNull()
                }
            }

            it("should update mutable fields when row with same narmesteLederId exists") {
                val narmesteLederId = UUID.randomUUID()

                val originalMessage = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(originalMessage)
                }

                val originalEntity = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }.first()
                    Triple(entity.id.value, entity.created, entity.updated)
                }
                val originalId = originalEntity.first
                val originalCreated = originalEntity.second

                val updatedMessage = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                    aktivTom = LocalDate.of(2025, 1, 31),
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(updatedMessage)
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }
                    results.count() shouldBe 1

                    val entity = results.first()
                    entity.id.value shouldBe originalId
                    entity.created.shouldNotBeNull().toInstant() shouldBe originalCreated.shouldNotBeNull().toInstant()
                    entity.orgnummer shouldBe updatedMessage.orgnummer
                    entity.narmestelederTelefonnummer shouldBe updatedMessage.narmesteLederTelefonnummer
                    entity.narmestelederEpost shouldBe updatedMessage.narmesteLederEpost
                    entity.arbeidsgiverForskutterer shouldBe updatedMessage.arbeidsgiverForskutterer
                    entity.aktivFom.toInstant() shouldBe updatedMessage.aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC)
                        .toInstant()
                    entity.aktivTom.shouldNotBeNull().toInstant() shouldBe updatedMessage.aktivTom!!.atStartOfDay()
                        .atOffset(ZoneOffset.UTC).toInstant()
                }
            }

            it("should persist last mutable field update after repeated upserts in same transaction") {
                val narmesteLederId = UUID.randomUUID()
                val baseMessage = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                )
                val messages = listOf(
                    baseMessage.copy(
                        aktivTom = LocalDate.of(2025, 1, 31),
                        narmesteLederEpost = "first@example.com",
                        narmesteLederTelefonnummer = "11111111",
                    ),
                    baseMessage.copy(
                        aktivTom = LocalDate.of(2025, 2, 28),
                        narmesteLederEpost = "second@example.com",
                        narmesteLederTelefonnummer = "22222222",
                    ),
                    baseMessage.copy(
                        aktivTom = null,
                        narmesteLederEpost = "last@example.com",
                        narmesteLederTelefonnummer = "33333333",
                    ),
                )
                val lastMessage = messages.last()

                transaction(TestDB.exposedDatabase) {
                    messages.forEach { message ->
                        narmestelederTable.upsertFromLeesahKafkaMessage(message)
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }
                    results.count() shouldBe 1

                    val entity = results.first()
                    entity.narmestelederTelefonnummer shouldBe lastMessage.narmesteLederTelefonnummer
                    entity.narmestelederEpost shouldBe lastMessage.narmesteLederEpost
                    entity.aktivTom?.toInstant() shouldBe lastMessage.aktivTom?.atStartOfDay()?.atOffset(ZoneOffset.UTC)
                        ?.toInstant()
                }
            }

            it("should not overwrite brukerFnr and narmestelederFnr on update") {
                val narmesteLederId = UUID.randomUUID()

                val originalMessage = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(originalMessage)
                }

                val messageWithDifferentFnr = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(messageWithDifferentFnr)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }.first()
                    entity.sykmeldtFnr shouldBe originalMessage.fnr
                    entity.narmestelederFnr shouldBe originalMessage.narmesteLederFnr
                }
            }

            it("should handle nullable fields correctly across insert and update") {
                val narmesteLederId = UUID.randomUUID()

                // Step a: Insert with nulls
                val messageWithNulls = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                    aktivTom = null,
                    arbeidsgiverForskutterer = null,
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(messageWithNulls)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }.first()
                    entity.aktivTom.shouldBeNull()
                    entity.arbeidsgiverForskutterer.shouldBeNull()
                }

                // Step b: Update with non-null values
                val messageWithValues = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                    aktivTom = LocalDate.of(2024, 12, 31),
                    arbeidsgiverForskutterer = true,
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(messageWithValues)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }.first()
                    entity.aktivTom.shouldNotBeNull()
                    entity.arbeidsgiverForskutterer shouldBe true
                }

                // Step c: Update back to nulls
                val messageBackToNulls = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                    aktivTom = null,
                    arbeidsgiverForskutterer = null,
                )

                transaction(TestDB.exposedDatabase) {
                    narmestelederTable.upsertFromLeesahKafkaMessage(messageBackToNulls)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }.first()
                    entity.aktivTom.shouldBeNull()
                    entity.arbeidsgiverForskutterer.shouldBeNull()
                }
            }
        }
    })
