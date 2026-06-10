package no.nav.syfo.narmesteleder.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.TestDB
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.outbox.OutboxDirectSender
import no.nav.syfo.outbox.OutboxDispatcher
import no.nav.syfo.outbox.OutboxEventRepository
import no.nav.syfo.outbox.OutboxEventStatus
import no.nav.syfo.outbox.OutboxMetrics
import no.nav.syfo.outbox.SykmeldingNlAvbruttOutboxHandler
import no.nav.syfo.outbox.SykmeldingNlRelasjonOutboxHandler
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn
import java.util.UUID

class NarmestelederKafkaServiceOutboxTest :
    DescribeSpec({
        val objectMapper = jacksonMapper()
        val repository = OutboxEventRepository(TestDB.exposedDatabase)
        val fakeProducer = FakeSykmeldingNLKafkaProducer()
        val dispatcher = OutboxDispatcher(
            handlers = listOf(
                SykmeldingNlRelasjonOutboxHandler(fakeProducer, objectMapper),
                SykmeldingNlAvbruttOutboxHandler(fakeProducer, objectMapper),
            ),
        )
        val directSender = OutboxDirectSender(repository, dispatcher, OutboxMetrics())
        val service = NarmestelederKafkaService(
            outboxEventRepository = repository,
            outboxDirectSender = directSender,
            database = TestDB.exposedDatabase,
            objectMapper = objectMapper,
        )
        val narmestelederDb = NarmestelederDb(TestDB.database, Dispatchers.IO)

        val linemanager = Linemanager(
            employeeIdentificationNumber = "12345678910",
            lastName = "Sykmeldt",
            orgNumber = "123456789",
            manager = Manager(
                nationalIdentificationNumber = "10987654321",
                lastName = "Leder",
                email = "leder@example.com",
                mobile = "99999999",
            ),
        )
        val actors = LinemanagerActors(
            manager = Person(
                nationalIdentificationNumber = linemanager.manager.nationalIdentificationNumber,
                name = Navn("Leder", null, "Test"),
            ),
            employee = Person(
                nationalIdentificationNumber = linemanager.employeeIdentificationNumber,
                name = Navn("Sykmeldt", null, "Test"),
            ),
        )

        suspend fun persistRequirement(): UUID {
            val inserted = narmestelederDb.insertNlBehov(
                NarmestelederBehovEntity(
                    orgnummer = linemanager.orgNumber,
                    hovedenhetOrgnummer = linemanager.orgNumber,
                    sykmeldtFnr = linemanager.employeeIdentificationNumber,
                    behovReason = BehovReason.NY_LEDER,
                    behovStatus = BehovStatus.BEHOV_CREATED,
                    etternavn = linemanager.lastName,
                ),
            )
            return inserted.id ?: error("Expected requirement id")
        }

        beforeTest {
            TestDB.clearAllData()
            fakeProducer.sentRelasjoner.clear()
            fakeProducer.sentBrudd.clear()
            fakeProducer.shouldFailRelasjon = false
            fakeProducer.shouldFailBrudd = false
        }

        describe("fulfillRequirementAndSendNarmesteLederRelasjon") {
            it("updates requirement status and stores retryable outbox event when kafka send fails") {
                val requirementId = persistRequirement()
                fakeProducer.shouldFailRelasjon = true

                service.fulfillRequirementAndSendNarmesteLederRelasjon(
                    requirementId = requirementId,
                    linemanager = linemanager,
                    linemanagerActors = actors,
                    source = NlResponseSource.LPS,
                )

                narmestelederDb.findBehovById(requirementId)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                repository.findAll().shouldHaveSize(1)
                repository.findAll().single().status shouldBe OutboxEventStatus.RETRY
            }

            it("rolls back outbox persistence when requirement update fails") {
                shouldThrow<IllegalArgumentException> {
                    service.fulfillRequirementAndSendNarmesteLederRelasjon(
                        requirementId = UUID.randomUUID(),
                        linemanager = linemanager,
                        linemanagerActors = actors,
                        source = NlResponseSource.LPS,
                    )
                }

                repository.findAll() shouldHaveSize 0
            }

            it("rolls back requirement update when outbox payload serialization fails inside the transaction") {
                val requirementId = persistRequirement()
                val failingObjectMapper = mockk<ObjectMapper>()
                every { failingObjectMapper.writeValueAsString(any<Any>()) } throws IllegalStateException("serialization failed")
                val failingService = NarmestelederKafkaService(
                    outboxEventRepository = repository,
                    outboxDirectSender = directSender,
                    database = TestDB.exposedDatabase,
                    objectMapper = failingObjectMapper,
                )

                shouldThrow<IllegalStateException> {
                    failingService.fulfillRequirementAndSendNarmesteLederRelasjon(
                        requirementId = requirementId,
                        linemanager = linemanager,
                        linemanagerActors = actors,
                        source = NlResponseSource.LPS,
                    )
                }

                narmestelederDb.findBehovById(requirementId)?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
                repository.findAll() shouldHaveSize 0
            }
        }
    })
