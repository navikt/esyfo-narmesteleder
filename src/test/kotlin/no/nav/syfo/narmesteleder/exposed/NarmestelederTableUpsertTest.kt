package no.nav.syfo.narmesteleder.exposed

import faker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class NarmestelederTableUpsertTest :
    DescribeSpec({
        beforeTest {
            TestDB.clearNarmestelederData()
        }

        describe("NarmestelederTable.upsertFromLeesahKafkaMessage") {
            it("should insert new entity when no existing row") {
                val narmesteLederId = UUID.randomUUID()
                val fnr = faker.numerify("###########")
                val orgnummer = faker.numerify("#########")
                val narmesteLederFnr = faker.numerify("###########")
                val narmesteLederTelefonnummer = faker.phoneNumber().cellPhone()
                val narmesteLederEpost = faker.internet().emailAddress()
                val aktivFom = LocalDate.of(2024, 3, 15)
                val aktivTom = LocalDate.of(2024, 12, 31)
                val arbeidsgiverForskutterer = true

                val message = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = fnr,
                    orgnummer = orgnummer,
                    narmesteLederFnr = narmesteLederFnr,
                    narmesteLederTelefonnummer = narmesteLederTelefonnummer,
                    narmesteLederEpost = narmesteLederEpost,
                    aktivFom = aktivFom,
                    aktivTom = aktivTom,
                    arbeidsgiverForskutterer = arbeidsgiverForskutterer,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(message)
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }
                    results.count() shouldBe 1

                    val entity = results.first()
                    entity.narmesteLederId shouldBe narmesteLederId
                    entity.orgnummer shouldBe orgnummer
                    entity.brukerFnr shouldBe fnr
                    entity.narmestelederFnr shouldBe narmesteLederFnr
                    entity.narmestelederTelefonnummer shouldBe narmesteLederTelefonnummer
                    entity.narmestelederEpost shouldBe narmesteLederEpost
                    entity.arbeidsgiverForskutterer shouldBe arbeidsgiverForskutterer
                    entity.aktivFom.toInstant() shouldBe aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant()
                    entity.aktivTom.shouldNotBeNull().toInstant() shouldBe aktivTom.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant()
                    entity.brukerNavn.shouldBeNull()
                    entity.narmestelederNavn.shouldBeNull()
                    entity.created.shouldNotBeNull()
                    entity.updated.shouldNotBeNull()
                }
            }

            it("should update mutable fields when row with same narmesteLederId exists") {
                val narmesteLederId = UUID.randomUUID()

                val originalMessage = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 1, 1),
                    aktivTom = LocalDate.of(2024, 6, 30),
                    arbeidsgiverForskutterer = false,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(originalMessage)
                }

                val originalEntity = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    Triple(entity.id.value, entity.created, entity.updated)
                }
                val originalId = originalEntity.first
                val originalCreated = originalEntity.second

                val updatedOrgnummer = faker.numerify("#########")
                val updatedTelefonnummer = faker.phoneNumber().cellPhone()
                val updatedEpost = faker.internet().emailAddress()
                val updatedAktivFom = LocalDate.of(2024, 7, 1)
                val updatedAktivTom = LocalDate.of(2025, 1, 31)

                val updatedMessage = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = updatedOrgnummer,
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = updatedTelefonnummer,
                    narmesteLederEpost = updatedEpost,
                    aktivFom = updatedAktivFom,
                    aktivTom = updatedAktivTom,
                    arbeidsgiverForskutterer = true,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(updatedMessage)
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }
                    results.count() shouldBe 1

                    val entity = results.first()
                    entity.id.value shouldBe originalId
                    entity.created.shouldNotBeNull().toInstant() shouldBe originalCreated.shouldNotBeNull().toInstant()
                    entity.orgnummer shouldBe updatedOrgnummer
                    entity.narmestelederTelefonnummer shouldBe updatedTelefonnummer
                    entity.narmestelederEpost shouldBe updatedEpost
                    entity.arbeidsgiverForskutterer shouldBe true
                    entity.aktivFom.toInstant() shouldBe updatedAktivFom.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant()
                    entity.aktivTom.shouldNotBeNull().toInstant() shouldBe updatedAktivTom.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant()
                }
            }

            it("should not overwrite PDL-owned name fields on update") {
                val narmesteLederId = UUID.randomUUID()

                val message = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 1, 1),
                    aktivTom = null,
                    arbeidsgiverForskutterer = true,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(message)
                }

                val brukerNavn = faker.name().firstName() + " " + faker.name().lastName()
                val narmestelederNavn = faker.name().firstName() + " " + faker.name().lastName()

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    entity.brukerNavn = brukerNavn
                    entity.narmestelederNavn = narmestelederNavn
                }

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(message)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    entity.brukerNavn shouldBe brukerNavn
                    entity.narmestelederNavn shouldBe narmestelederNavn
                }
            }

            it("should not overwrite brukerFnr and narmestelederFnr on update") {
                val narmesteLederId = UUID.randomUUID()
                val originalBrukerFnr = faker.numerify("###########")
                val originalNarmestelederFnr = faker.numerify("###########")

                val originalMessage = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = originalBrukerFnr,
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = originalNarmestelederFnr,
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 1, 1),
                    aktivTom = null,
                    arbeidsgiverForskutterer = true,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(originalMessage)
                }

                val messageWithDifferentFnr = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 7, 1),
                    aktivTom = null,
                    arbeidsgiverForskutterer = false,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(messageWithDifferentFnr)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    entity.brukerFnr shouldBe originalBrukerFnr
                    entity.narmestelederFnr shouldBe originalNarmestelederFnr
                }
            }

            it("should handle nullable fields correctly across insert and update") {
                val narmesteLederId = UUID.randomUUID()

                // Step a: Insert with nulls
                val messageWithNulls = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 1, 1),
                    aktivTom = null,
                    arbeidsgiverForskutterer = null,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(messageWithNulls)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    entity.aktivTom.shouldBeNull()
                    entity.arbeidsgiverForskutterer.shouldBeNull()
                }

                // Step b: Update with non-null values
                val messageWithValues = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 3, 1),
                    aktivTom = LocalDate.of(2024, 12, 31),
                    arbeidsgiverForskutterer = true,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(messageWithValues)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    entity.aktivTom.shouldNotBeNull()
                    entity.arbeidsgiverForskutterer shouldBe true
                }

                // Step c: Update back to nulls
                val messageBackToNulls = NarmestelederLeesahKafkaMessage(
                    narmesteLederId = narmesteLederId,
                    fnr = faker.numerify("###########"),
                    orgnummer = faker.numerify("#########"),
                    narmesteLederFnr = faker.numerify("###########"),
                    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                    narmesteLederEpost = faker.internet().emailAddress(),
                    aktivFom = LocalDate.of(2024, 6, 1),
                    aktivTom = null,
                    arbeidsgiverForskutterer = null,
                    timestamp = OffsetDateTime.now(),
                    status = LeesahStatus.NY_LEDER,
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederTable.upsertFromLeesahKafkaMessage(messageBackToNulls)
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.find {
                        NarmestelederTable.narmesteLederId eq narmesteLederId
                    }.first()
                    entity.aktivTom.shouldBeNull()
                    entity.arbeidsgiverForskutterer.shouldBeNull()
                }
            }
        }
    })
