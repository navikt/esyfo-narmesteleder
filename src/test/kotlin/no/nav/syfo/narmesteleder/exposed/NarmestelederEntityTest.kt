package no.nav.syfo.narmesteleder.exposed

import faker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

class NarmestelederEntityTest :
    DescribeSpec({
        beforeTest {
            TestDB.clearNarmestelederData()
        }

        describe("NarmestelederEntity") {
            it("should create and read back entity with all fields") {
                val narmesteLederId = UUID.randomUUID()
                val orgnummer = faker.numerify("#########")
                val sykmeldtFnr = faker.numerify("###########")
                val narmestelederFnr = faker.numerify("###########")
                val narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                val narmestelederEpost = faker.internet().emailAddress()
                val arbeidsgiverForskutterer = true
                val aktivFom = OffsetDateTime.now().minusDays(30).truncatedTo(ChronoUnit.MICROS)
                val aktivTom = OffsetDateTime.now().plusDays(30).truncatedTo(ChronoUnit.MICROS)

                val entityId = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.new {
                        this.narmesteLederId = narmesteLederId
                        this.orgnummer = orgnummer
                        this.sykmeldtFnr = sykmeldtFnr
                        this.narmestelederFnr = narmestelederFnr
                        this.narmestelederTelefonnummer = narmestelederTelefonnummer
                        this.narmestelederEpost = narmestelederEpost
                        this.arbeidsgiverForskutterer = arbeidsgiverForskutterer
                        this.aktivFom = aktivFom
                        this.aktivTom = aktivTom
                    }
                    entity.id.value
                }

                transaction(TestDB.exposedDatabase) {
                    val readBack = NarmestelederEntity.findById(entityId)
                    readBack.shouldNotBeNull()

                    readBack.narmesteLederId shouldBe narmesteLederId
                    readBack.orgnummer shouldBe orgnummer
                    readBack.sykmeldtFnr shouldBe sykmeldtFnr
                    readBack.narmestelederFnr shouldBe narmestelederFnr
                    readBack.narmestelederTelefonnummer shouldBe narmestelederTelefonnummer
                    readBack.narmestelederEpost shouldBe narmestelederEpost
                    readBack.arbeidsgiverForskutterer shouldBe arbeidsgiverForskutterer
                    readBack.aktivFom.toInstant() shouldBe aktivFom.toInstant()
                    readBack.aktivTom.shouldNotBeNull().toInstant() shouldBe aktivTom.toInstant()
                    readBack.created.shouldNotBeNull()
                    readBack.updated.shouldNotBeNull()
                }
            }

            it("should handle nullable fields correctly") {
                val narmesteLederId = UUID.randomUUID()
                val aktivFom = OffsetDateTime.now()

                val entityId = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.new {
                        this.narmesteLederId = narmesteLederId
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.arbeidsgiverForskutterer = null
                        this.aktivFom = aktivFom
                        this.aktivTom = null
                    }
                    entity.id.value
                }

                transaction(TestDB.exposedDatabase) {
                    val readBack = NarmestelederEntity.findById(entityId)
                    readBack.shouldNotBeNull()

                    readBack.arbeidsgiverForskutterer.shouldBeNull()
                    readBack.aktivTom.shouldBeNull()
                }
            }

            it("should auto-generate id on insert") {
                val entityId = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.new {
                        this.narmesteLederId = UUID.randomUUID()
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                    entity.id.value
                }

                entityId shouldNotBe null
                entityId shouldNotBe 0
            }

            it("should set created and updated defaults from database") {
                val entityId = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.new {
                        this.narmesteLederId = UUID.randomUUID()
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                    entity.refresh(flush = true)
                    entity.id.value
                }

                transaction(TestDB.exposedDatabase) {
                    val readBack = NarmestelederEntity.findById(entityId)
                    readBack.shouldNotBeNull()
                    readBack.created.shouldNotBeNull()
                    readBack.updated.shouldNotBeNull()
                }
            }

            it("should update entity fields") {
                val entityId = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.new {
                        this.narmesteLederId = UUID.randomUUID()
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                    entity.id.value
                }

                val updatedOrgnummer = faker.numerify("#########")
                val updatedEpost = faker.internet().emailAddress()
                val updatedBrukerNavn = faker.name().firstName() + " " + faker.name().lastName()

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.findById(entityId)
                    entity.shouldNotBeNull()
                    entity.orgnummer = updatedOrgnummer
                    entity.narmestelederEpost = updatedEpost
                }

                transaction(TestDB.exposedDatabase) {
                    val readBack = NarmestelederEntity.findById(entityId)
                    readBack.shouldNotBeNull()
                    readBack.orgnummer shouldBe updatedOrgnummer
                    readBack.narmestelederEpost shouldBe updatedEpost
                }
            }

            it("should find by narmesteleder_id") {
                val narmesteLederId = UUID.randomUUID()
                val orgnummer = faker.numerify("#########")

                transaction(TestDB.exposedDatabase) {
                    NarmestelederEntity.new {
                        this.narmesteLederId = narmesteLederId
                        this.orgnummer = orgnummer
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }
                    results.count() shouldBe 1
                    results.first().narmesteLederId shouldBe narmesteLederId
                    results.first().orgnummer shouldBe orgnummer
                }
            }

            it("should find by sykmeldt_fnr") {
                val sykmeldtFnr = faker.numerify("###########")

                transaction(TestDB.exposedDatabase) {
                    NarmestelederEntity.new {
                        this.narmesteLederId = UUID.randomUUID()
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = sykmeldtFnr
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.sykmeldtFnr eq sykmeldtFnr
                    }
                    results.count() shouldBe 1
                    results.first().sykmeldtFnr shouldBe sykmeldtFnr
                }
            }

            it("should find by narmesteleder_fnr") {
                val narmestelederFnr = faker.numerify("###########")

                transaction(TestDB.exposedDatabase) {
                    NarmestelederEntity.new {
                        this.narmesteLederId = UUID.randomUUID()
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = narmestelederFnr
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederFnr eq narmestelederFnr
                    }
                    results.count() shouldBe 1
                    results.first().narmestelederFnr shouldBe narmestelederFnr
                }
            }

            it("should delete entity") {
                val entityId = transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.new {
                        this.narmesteLederId = UUID.randomUUID()
                        this.orgnummer = faker.numerify("#########")
                        this.sykmeldtFnr = faker.numerify("###########")
                        this.narmestelederFnr = faker.numerify("###########")
                        this.narmestelederTelefonnummer = faker.phoneNumber().cellPhone()
                        this.narmestelederEpost = faker.internet().emailAddress()
                        this.aktivFom = OffsetDateTime.now()
                    }
                    entity.id.value
                }

                transaction(TestDB.exposedDatabase) {
                    val entity = NarmestelederEntity.findById(entityId)
                    entity.shouldNotBeNull()
                    entity.delete()
                }

                transaction(TestDB.exposedDatabase) {
                    NarmestelederEntity.findById(entityId).shouldBeNull()
                }
            }

            describe("fromLeesahKafkaMessage") {
                it("should create entity from LeesahKafkaMessage with all fields") {
                    val narmesteLederId = UUID.randomUUID()
                    val fnr = faker.numerify("###########")
                    val orgnummer = faker.numerify("#########")
                    val narmesteLederFnr = faker.numerify("###########")
                    val narmesteLederTelefonnummer = faker.phoneNumber().cellPhone()
                    val narmesteLederEpost = faker.internet().emailAddress()
                    val aktivFom = LocalDate.of(2024, 1, 15)
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

                    val entityId = transaction(TestDB.exposedDatabase) {
                        val entity = NarmestelederEntity.fromLeesahKafkaMessage(message)
                        entity.id.value
                    }

                    transaction(TestDB.exposedDatabase) {
                        val readBack = NarmestelederEntity.findById(entityId)
                        readBack.shouldNotBeNull()

                        readBack.narmesteLederId shouldBe narmesteLederId
                        readBack.orgnummer shouldBe orgnummer
                        readBack.sykmeldtFnr shouldBe fnr
                        readBack.narmestelederFnr shouldBe narmesteLederFnr
                        readBack.narmestelederTelefonnummer shouldBe narmesteLederTelefonnummer
                        readBack.narmestelederEpost shouldBe narmesteLederEpost
                        readBack.arbeidsgiverForskutterer shouldBe arbeidsgiverForskutterer
                        readBack.aktivFom.toInstant() shouldBe aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant()
                        readBack.aktivTom.shouldNotBeNull().toInstant() shouldBe aktivTom.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant()
                        readBack.created.shouldNotBeNull()
                        readBack.updated.shouldNotBeNull()
                    }
                }

                it("should create entity from LeesahKafkaMessage with nullable fields as null") {
                    val message = NarmestelederLeesahKafkaMessage(
                        narmesteLederId = UUID.randomUUID(),
                        fnr = faker.numerify("###########"),
                        orgnummer = faker.numerify("#########"),
                        narmesteLederFnr = faker.numerify("###########"),
                        narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                        narmesteLederEpost = faker.internet().emailAddress(),
                        aktivFom = LocalDate.of(2024, 3, 1),
                        aktivTom = null,
                        arbeidsgiverForskutterer = null,
                        timestamp = OffsetDateTime.now(),
                        status = LeesahStatus.NY_LEDER,
                    )

                    val entityId = transaction(TestDB.exposedDatabase) {
                        val entity = NarmestelederEntity.fromLeesahKafkaMessage(message)
                        entity.id.value
                    }

                    transaction(TestDB.exposedDatabase) {
                        val readBack = NarmestelederEntity.findById(entityId)
                        readBack.shouldNotBeNull()

                        readBack.aktivTom.shouldBeNull()
                        readBack.arbeidsgiverForskutterer.shouldBeNull()
                    }
                }

                it("should correctly convert LocalDate to OffsetDateTime with UTC offset") {
                    val aktivFom = LocalDate.of(2024, 6, 15)
                    val aktivTom = LocalDate.of(2025, 1, 20)

                    val message = NarmestelederLeesahKafkaMessage(
                        narmesteLederId = UUID.randomUUID(),
                        fnr = faker.numerify("###########"),
                        orgnummer = faker.numerify("#########"),
                        narmesteLederFnr = faker.numerify("###########"),
                        narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
                        narmesteLederEpost = faker.internet().emailAddress(),
                        aktivFom = aktivFom,
                        aktivTom = aktivTom,
                        arbeidsgiverForskutterer = true,
                        timestamp = OffsetDateTime.now(),
                        status = LeesahStatus.NY_LEDER,
                    )

                    val entityId = transaction(TestDB.exposedDatabase) {
                        val entity = NarmestelederEntity.fromLeesahKafkaMessage(message)
                        entity.id.value
                    }

                    transaction(TestDB.exposedDatabase) {
                        val readBack = NarmestelederEntity.findById(entityId)
                        readBack.shouldNotBeNull()

                        readBack.aktivFom.hour shouldBe 0
                        readBack.aktivFom.minute shouldBe 0
                        readBack.aktivFom.second shouldBe 0
                        readBack.aktivFom.offset shouldBe ZoneOffset.UTC

                        val readBackAktivTom = readBack.aktivTom
                        readBackAktivTom.shouldNotBeNull()
                        readBackAktivTom.hour shouldBe 0
                        readBackAktivTom.minute shouldBe 0
                        readBackAktivTom.second shouldBe 0
                        readBackAktivTom.offset shouldBe ZoneOffset.UTC
                    }
                }
            }
        }
    })
