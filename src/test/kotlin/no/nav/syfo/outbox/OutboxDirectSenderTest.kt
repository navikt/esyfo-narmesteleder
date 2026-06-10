package no.nav.syfo.outbox

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class OutboxDirectSenderTest :
    DescribeSpec({
        val objectMapper = jacksonMapper()
        val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val repository = OutboxEventRepository(
            database = TestDB.exposedDatabase,
            clock = fixedClock,
            retryBackoffBase = Duration.ofMinutes(1),
            retryBackoffMax = Duration.ofMinutes(10),
        )
        val fakeProducer = FakeSykmeldingNLKafkaProducer()
        val dispatcher = OutboxDispatcher(
            handlers = listOf(
                SykmeldingNlRelasjonOutboxHandler(fakeProducer, objectMapper),
                SykmeldingNlAvbruttOutboxHandler(fakeProducer, objectMapper),
            ),
        )
        val directSender = OutboxDirectSender(
            outboxEventRepository = repository,
            outboxDispatcher = dispatcher,
            outboxMetrics = OutboxMetrics(),
        )

        fun validRelationPayload(): String = objectMapper.writeValueAsString(
            OutboxNlRelasjonPayload(
                nlResponse = NlResponse(
                    orgnummer = "123456789",
                    leder = Leder(
                        fnr = "10987654321",
                        mobil = "99999999",
                        epost = "leder@example.com",
                        fornavn = "Leder",
                        etternavn = "Test",
                    ),
                    sykmeldt = Sykmeldt(
                        fnr = "12345678910",
                        navn = "Sykmeldt Test",
                    ),
                    utbetalesLonn = true,
                ),
                source = NlResponseSource.LPS,
            ),
        )

        suspend fun persistRelationEvent(payload: String = validRelationPayload()): java.util.UUID = repository.persist(
            PersistOutboxEvent(
                destination = OutboxDestination.SYKMELDING_NL,
                eventType = OutboxEventType.NL_RELASJON,
                kafkaKey = "123456789",
                payload = payload,
                payloadVersion = 1,
            ),
        )

        beforeTest {
            TestDB.clearOutboxEventData()
            fakeProducer.sentRelasjoner.clear()
            fakeProducer.sentBrudd.clear()
            fakeProducer.shouldFailRelasjon = false
            fakeProducer.shouldFailBrudd = false
        }

        describe("sendById") {
            it("marks event as sent on successful dispatch") {
                val eventId = persistRelationEvent()

                directSender.sendById(eventId)

                repository.getById(eventId)?.status shouldBe OutboxEventStatus.SENT
                fakeProducer.sentRelasjoner.size shouldBe 1
            }

            it("marks event as retry when dispatch fails") {
                val eventId = persistRelationEvent()
                fakeProducer.shouldFailRelasjon = true

                directSender.sendById(eventId)

                repository.getById(eventId)?.status shouldBe OutboxEventStatus.RETRY
                repository.getById(eventId)?.nextAttemptAt shouldBe fixedInstant.plus(Duration.ofMinutes(1)).atOffset(ZoneOffset.UTC)
                fakeProducer.sentRelasjoner.size shouldBe 0
            }

            it("marks event as dead when payload cannot be deserialized") {
                val eventId = persistRelationEvent(payload = """{"unexpected":"value"}""")

                directSender.sendById(eventId)

                repository.getById(eventId)?.status shouldBe OutboxEventStatus.DEAD
            }

            it("marks event as dead when route is unknown") {
                TestDB.database.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO outbox_event(destination, event_type, kafka_key, payload, payload_version, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id;
                        """.trimIndent(),
                    ).use { preparedStatement ->
                        preparedStatement.setString(1, "UNKNOWN_DESTINATION")
                        preparedStatement.setString(2, "UNKNOWN_EVENT")
                        preparedStatement.setString(3, "123456789")
                        preparedStatement.setString(4, "{}")
                        preparedStatement.setInt(5, 1)
                        preparedStatement.setString(6, OutboxEventStatus.PENDING.name)
                        preparedStatement.executeQuery().use { resultSet ->
                            resultSet.next()
                            val eventId = resultSet.getObject("id", java.util.UUID::class.java)
                            connection.commit()

                            directSender.sendById(eventId)

                            repository.getById(eventId)?.status shouldBe OutboxEventStatus.DEAD
                        }
                    }
                }
            }
        }
    })
