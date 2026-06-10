package no.nav.syfo.outbox

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OutboxEventRepositoryTest :
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

        suspend fun persistRelationEvent(): UUID = repository.persist(
            PersistOutboxEvent(
                destination = OutboxDestination.SYKMELDING_NL,
                eventType = OutboxEventType.NL_RELASJON,
                kafkaKey = "123456789",
                payload = objectMapper.writeValueAsString(
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
                ),
                payloadVersion = 1,
            ),
        )

        beforeTest {
            TestDB.clearOutboxEventData()
        }

        describe("claimPending") {
            it("claims pending events in created order and marks them processing") {
                val firstId = persistRelationEvent()
                val secondId = persistRelationEvent()

                val claimed = repository.claimPending(batchSize = 10, lockedBy = "test-runner")

                claimed.map { it.id } shouldContainExactly listOf(firstId, secondId)
                claimed.forEach {
                    it.status shouldBe OutboxEventStatus.PROCESSING
                    it.attemptCount shouldBe 1
                    it.lockedBy shouldBe "test-runner"
                }
            }

            it("does not claim retry events before next attempt") {
                val eventId = persistRelationEvent()
                val claimed = repository.claimById(eventId, lockedBy = "worker-a").shouldNotBeNull()

                repository.markRetry(
                    id = claimed.id,
                    expectedLockedBy = claimed.lockedBy.shouldNotBeNull(),
                    expectedClaimId = claimed.claimId.shouldNotBeNull(),
                    attemptCount = claimed.attemptCount,
                    errorMessage = "temporary error",
                ) shouldBe 1

                repository.claimPending(batchSize = 10, lockedBy = "scheduler") shouldHaveSize 0

                val advancedRepository = OutboxEventRepository(
                    database = TestDB.exposedDatabase,
                    clock = Clock.offset(fixedClock, Duration.ofMinutes(1)),
                    retryBackoffBase = Duration.ofMinutes(1),
                    retryBackoffMax = Duration.ofMinutes(10),
                )
                advancedRepository.claimPending(batchSize = 10, lockedBy = "scheduler").map { it.id } shouldContainExactly listOf(eventId)
            }
        }

        describe("claim finalization") {
            it("rejects markSent when lockedBy does not match the current claim") {
                val eventId = persistRelationEvent()
                val claimed = repository.claimById(eventId, lockedBy = "stale-lock").shouldNotBeNull()

                repository.markSent(
                    id = eventId,
                    expectedLockedBy = "other-worker",
                    expectedClaimId = claimed.claimId.shouldNotBeNull(),
                ) shouldBe 0

                repository.getById(eventId)?.status shouldBe OutboxEventStatus.PROCESSING
            }

            it("rejects markDead when claimId does not match the current claim") {
                val eventId = persistRelationEvent()
                val claimed = repository.claimById(eventId, lockedBy = "stale-lock").shouldNotBeNull()

                repository.markDead(
                    id = eventId,
                    expectedLockedBy = claimed.lockedBy.shouldNotBeNull(),
                    expectedClaimId = UUID.randomUUID(),
                    errorMessage = "boom",
                ) shouldBe 0

                repository.getById(eventId)?.status shouldBe OutboxEventStatus.PROCESSING
            }
        }

        describe("requeueStaleProcessing") {
            it("moves stale processing events to retry") {
                val eventId = persistRelationEvent()
                repository.claimById(eventId, lockedBy = "stale-lock")

                TestDB.database.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE outbox_event
                        SET locked_at = ?
                        WHERE id = ?;
                        """.trimIndent(),
                    ).use { preparedStatement ->
                        preparedStatement.setObject(1, fixedInstant.minus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC))
                        preparedStatement.setObject(2, eventId)
                        preparedStatement.executeUpdate()
                    }
                    connection.commit()
                }

                repository.requeueStaleProcessing(Duration.ofMinutes(5)) shouldBe 1

                val stored = repository.getById(eventId) ?: error("Expected outbox event")
                stored.status shouldBe OutboxEventStatus.RETRY
                stored.claimId shouldBe null
                stored.lockedAt shouldBe null
                stored.lockedBy shouldBe null
                stored.nextAttemptAt shouldBe fixedInstant.atOffset(ZoneOffset.UTC)
            }
        }
    })
