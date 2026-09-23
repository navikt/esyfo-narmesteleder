package no.nav.syfo.narmesteleder.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import defaultLeesahKafkaMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.InsertedPerson
import no.nav.syfo.narmesteleder.exposed.NarmestelederEntity
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.narmesteleder.kafka.LeesahNarmestelederRecord
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

class NarmestelederRegisterServiceTest :
    DescribeSpec({
        val service = NarmestelederRegisterService(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearNarmestelederData()
            TestDB.clearPersonData()
        }
        describe("processLeesahBatch") {
            describe("validate insert or update of narmesteleder") {
                it("logs only the register record id and Kafka position for an invalid record") {
                    val message = defaultLeesahKafkaMessage().copy(fnr = "123")
                    val appender = ListAppender<ILoggingEvent>().apply { start() }
                    val logger = LoggerFactory.getLogger(NarmestelederRegisterService::class.java) as Logger
                    val previousLevel = logger.level
                    logger.level = Level.WARN
                    logger.addAppender(appender)
                    try {
                        service.processLeesahBatch(listOf(LeesahNarmestelederRecord(offset = 42, partition = 3, message = message)))
                        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
                        fields["event_type"] shouldBe "nl_register_record_invalid"
                        fields["narmesteleder_id"] shouldBe message.narmesteLederId.toString()
                        fields["partition"] shouldBe 3
                        fields["offset"] shouldBe 42L
                        val logged = appender.list.single().formattedMessage + fields.toString()
                        logged.contains(message.orgnummer) shouldBe false
                        logged.contains(message.narmesteLederFnr) shouldBe false
                    } finally {
                        logger.detachAppender(appender)
                        logger.level = previousLevel
                        appender.stop()
                    }
                }

                it("should upsert replayed records idempotently") {
                    val narmesteLederId = UUID.randomUUID()
                    val originalMessage = defaultLeesahKafkaMessage().copy(
                        narmesteLederId = narmesteLederId,
                        narmesteLederTelefonnummer = "11111111",
                    )
                    val replayedMessage = originalMessage.copy(
                        narmesteLederTelefonnummer = "22222222",
                        narmesteLederEpost = "updated@example.com",
                        aktivTom = LocalDate.of(2025, 1, 31),
                    )

                    service.processLeesahBatch(
                        listOf(
                            LeesahNarmestelederRecord(
                                offset = 1L,
                                message = originalMessage
                            )
                        )
                    )
                    service.processLeesahBatch(
                        listOf(
                            LeesahNarmestelederRecord(
                                offset = 2L,
                                message = replayedMessage
                            )
                        )
                    )

                    transaction(TestDB.exposedDatabase) {
                        val results = NarmestelederEntity.find {
                            NarmestelederTable.narmestelederId eq narmesteLederId
                        }

                        results.count() shouldBe 1
                        val entity = results.first()
                        entity.narmestelederTelefonnummer shouldBe replayedMessage.narmesteLederTelefonnummer
                        entity.narmestelederEpost shouldBe replayedMessage.narmesteLederEpost
                        entity.aktivTom?.toLocalDate() shouldBe replayedMessage.aktivTom
                    }
                }

                it("should skip invalid records without blocking valid records in the same batch") {
                    val validMessage = defaultLeesahKafkaMessage()
                    val invalidMessage = defaultLeesahKafkaMessage().copy(fnr = "123")
                    service.processLeesahBatch(
                        listOf(
                            LeesahNarmestelederRecord(offset = 1L, message = validMessage),
                            LeesahNarmestelederRecord(offset = 2L, message = invalidMessage),
                        ),
                    )

                    transaction(TestDB.exposedDatabase) {
                        NarmestelederEntity.all().count() shouldBe 1
                        NarmestelederEntity.find {
                            NarmestelederTable.narmestelederId eq validMessage.narmesteLederId
                        }.count() shouldBe 1
                    }
                }
            }

            describe("validate insert of person") {
                it("should insert person rows for both employee and nearest leader from valid records") {
                    val firstMessage = defaultLeesahKafkaMessage().copy(
                        fnr = "12345678901",
                        narmesteLederFnr = "10987654321",
                    )
                    val secondMessage = defaultLeesahKafkaMessage().copy(
                        fnr = "11111111111",
                        narmesteLederFnr = "22222222222",
                    )

                    val insertedPersons = service.processLeesahBatch(
                        listOf(
                            LeesahNarmestelederRecord(offset = 1L, message = firstMessage),
                            LeesahNarmestelederRecord(offset = 2L, message = secondMessage),
                        ),
                    )

                    insertedPersons.map(InsertedPerson::fnr).toSet() shouldBe setOf(
                        firstMessage.fnr,
                        firstMessage.narmesteLederFnr,
                        secondMessage.fnr,
                        secondMessage.narmesteLederFnr,
                    )
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.find(
                            PersonTable.fnr inList listOf(
                                "10987654321",
                                "11111111111",
                                "12345678901",
                                "10987654321",
                                "22222222222"
                            )
                        ).count() shouldBe 4
                    }
                }

                it("should not insert any persons when all records are invalid") {
                    val invalidRecords = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            message = defaultLeesahKafkaMessage().copy(
                                fnr = "123",
                                narmesteLederFnr = "456",
                            ),
                        ),
                        LeesahNarmestelederRecord(
                            offset = 2L,
                            message = defaultLeesahKafkaMessage().copy(
                                fnr = "abcdefghijk",
                                narmesteLederFnr = "1234567890a",
                            ),
                        ),
                    )

                    val insertedPersons = service.processLeesahBatch(invalidRecords)

                    insertedPersons shouldBe emptyList()
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.all().count() shouldBe 0
                    }
                }

                it("should only insert persons from valid records when batch contains both valid and invalid records") {
                    val validMessage = defaultLeesahKafkaMessage().copy(
                        fnr = "12345678901",
                        narmesteLederFnr = "10987654321",
                    )
                    val invalidMessage = defaultLeesahKafkaMessage().copy(
                        fnr = "123",
                        narmesteLederFnr = "22222222222",
                    )

                    val insertedPersons = service.processLeesahBatch(
                        listOf(
                            LeesahNarmestelederRecord(offset = 1L, message = validMessage),
                            LeesahNarmestelederRecord(offset = 2L, message = invalidMessage),
                        ),
                    )

                    insertedPersons.map(InsertedPerson::fnr).toSet() shouldBe setOf(
                        validMessage.fnr,
                        validMessage.narmesteLederFnr,
                    )
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.find(PersonTable.fnr inList insertedPersons.map(InsertedPerson::fnr))
                            .count() shouldBe 2
                    }
                }

                it("should not insert persons from records that are invalid for register validation") {
                    val record = LeesahNarmestelederRecord(
                        offset = 1L,
                        message = defaultLeesahKafkaMessage().copy(
                            fnr = "12345678901",
                            narmesteLederFnr = "10987654321",
                            orgnummer = "123",
                        ),
                    )

                    val insertedPersons = service.processLeesahBatch(listOf(record))

                    insertedPersons shouldBe emptyList()
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.all().count() shouldBe 0
                    }
                }

                it("should not insert employee or nearest leader when employee fnr is invalid") {
                    val record = LeesahNarmestelederRecord(
                        offset = 1L,
                        message = defaultLeesahKafkaMessage().copy(
                            fnr = "123",
                            narmesteLederFnr = "10987654321",
                        ),
                    )

                    val insertedPersons = service.processLeesahBatch(listOf(record))

                    insertedPersons shouldBe emptyList()
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.all().count() shouldBe 0
                    }
                }

                it("should not insert employee or nearest leader when nearest leader fnr is invalid") {
                    val record = LeesahNarmestelederRecord(
                        offset = 1L,
                        message = defaultLeesahKafkaMessage().copy(
                            fnr = "12345678901",
                            narmesteLederFnr = "123",
                        ),
                    )

                    val insertedPersons = service.processLeesahBatch(listOf(record))

                    insertedPersons shouldBe emptyList()
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.all().count() shouldBe 0
                    }
                }

                it("should only keep one row per fnr for duplicate fnr values across valid records") {
                    val records = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            message = defaultLeesahKafkaMessage().copy(
                                fnr = "12345678901",
                                narmesteLederFnr = "12345678901",
                            ),
                        ),
                        LeesahNarmestelederRecord(
                            offset = 2L,
                            message = defaultLeesahKafkaMessage().copy(
                                fnr = "12345678901",
                                narmesteLederFnr = "10987654321",
                            ),
                        ),
                        LeesahNarmestelederRecord(
                            offset = 3L,
                            message = defaultLeesahKafkaMessage().copy(
                                fnr = "10987654321",
                                narmesteLederFnr = "12345678901",
                            ),
                        ),
                    )

                    val insertedPersons = service.processLeesahBatch(records)
                    insertedPersons.map(InsertedPerson::fnr).toSet() shouldBe setOf("12345678901", "10987654321")
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.all().map { it.fnr }.sorted() shouldBe listOf(
                            "10987654321",
                            "12345678901"
                        ).sorted()
                    }
                }

                it("should ignore conflicts for existing persons without overwriting the existing row") {
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

                    val insertedPersons = service.processLeesahBatch(
                        listOf(
                            LeesahNarmestelederRecord(
                                offset = 1L,
                                message = defaultLeesahKafkaMessage().copy(
                                    fnr = "12345678901",
                                    narmesteLederFnr = "10987654321",
                                ),
                            ),
                        ),
                    )

                    insertedPersons.map(InsertedPerson::fnr) shouldBe listOf("10987654321")
                    val insertedPerson = insertedPersons.single()
                    transaction(TestDB.exposedDatabase) {
                        PersonEntity.all().map { it.fnr }.sorted() shouldBe listOf(
                            "10987654321",
                            "12345678901"
                        ).sorted()
                    }
                    insertedPerson.status shouldBe PersonStatus.PENDING.name
                }
            }
        }
    })
