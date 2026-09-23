package no.nav.syfo.sykmelding.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.model.RiktigNarmesteLeder
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.sykmelding.service.NarmestelederBruddService
import no.nav.syfo.sykmelding.service.SykmeldingService
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

class SendtSykmeldingHandlerTest :
    DescribeSpec({

        val narmesteLederService = mockk<NarmestelederService>()
        val sykmeldingService = mockk<SykmeldingService>()
        val narmestelederBruddService = mockk<NarmestelederBruddService>()
        val handler = SendtSykmeldingHandler(narmesteLederService, sykmeldingService, narmestelederBruddService)

        beforeEach {
            clearAllMocks(currentThreadOnly = true)
            coEvery { sykmeldingService.processBatch(any()) } just Runs
            coEvery { narmesteLederService.createNewNlBehov(any(), any(), any(), any()) } returns null
            coEvery { narmestelederBruddService.revokeFromSendtSykmelding(any(), any(), any(), any(), any()) } just Runs
        }

        it("logs only the validated sykmelding UUID when employer information is missing") {
            val logger = LoggerFactory.getLogger(SendtSykmeldingHandler::class.java) as Logger
            val originalLevel = logger.level
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.level = Level.ERROR
            logger.addAppender(appender)
            try {
                val id = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(sykmeldingId = id.toString())
                    .let { it.copy(event = it.event.copy(arbeidsgiver = null)) }

                handler.handleNarmestelederbehov(message)

                val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "sick_leave_employer_missing"
                fields["action"] shouldBe "CREATE_BEHOV"
                fields["sykmelding_id"] shouldBe id.toString()
                fields.toString().contains(message.kafkaMetadata.fnr) shouldBe false
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                logger.level = originalLevel
            }
        }

        it("keeps the skipped message action and reason bounded without logging invalid identifiers") {
            val logger = LoggerFactory.getLogger(SendtSykmeldingHandler::class.java) as Logger
            val previousLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.WARN
            logger.addAppender(appender)
            try {
                val id = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(sykmeldingId = id.toString())
                    .let { it.copy(kafkaMetadata = it.kafkaMetadata.copy(fnr = "private-canary")) }
                handler.handleNarmestelederbehov(message)

                val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "sick_leave_message_skipped"
                fields["action"] shouldBe "CREATE_BEHOV"
                fields["reason"] shouldBe "PERSON_ID_INVALID"
                fields["sykmelding_id"] shouldBe id.toString()
                fields.toString().contains("private-canary") shouldBe false
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                logger.level = previousLevel
            }
        }

        describe("skipSykmeldingCheck parameter tests") {

            it("should set skipSykmeldingCheck to true when period includes today") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to true when today is the first day of period") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to true when today is the last day of period") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when all periods are in the past") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(20), tom = today.minusDays(10))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    val createNewNlBehov = narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when all periods are in the future") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(10), tom = today.plusDays(20))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when period ended yesterday") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(1))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when period starts tomorrow") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(1), tom = today.plusDays(10))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to true when at least one of multiple periods includes today") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20)),
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5)),
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(10), tom = today.plusDays(20))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when multiple periods exist but none include today") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20)),
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(15), tom = today.minusDays(10)),
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(10), tom = today.plusDays(20))
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }
        }

        describe("handleSendtSykmelding general behavior") {

            it("should not create NL behov when riktigNarmesteLeder is answered") {
                val message = defaultSendtSykmeldingMessage(
                    riktigNarmesteLeder = RiktigNarmesteLeder(
                        sporsmaltekst = "Er dette riktig leder?",
                        svar = "JA"
                    )
                )

                handler.handleNarmestelederbehov(message)

                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any())
                }
            }

            it("should revoke NL relation and track Kafka metadata when riktigNarmesteLeder is answered NEI") {
                val message = defaultSendtSykmeldingMessage(
                    riktigNarmesteLeder = RiktigNarmesteLeder(
                        sporsmaltekst = "Er dette riktig leder?",
                        svar = "NEI",
                    )
                )

                handler.handleNarmestelederbehov(message, kafkaPartition = 3, kafkaOffset = 42)

                coVerify {
                    narmestelederBruddService.revokeFromSendtSykmelding(
                        sykmeldingId = UUID.fromString(message.event.sykmeldingId),
                        fnr = message.kafkaMetadata.fnr,
                        orgnummer = requireNotNull(message.event.arbeidsgiver).orgnummer,
                        kafkaPartition = 3,
                        kafkaOffset = 42,
                    )
                }
                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any(), any())
                }
            }

            it("should create NL behov with correct parameters when riktigNarmesteLeder is null") {
                val today = LocalDate.now()
                val fnr = "12345678901"
                val orgnummer = "999888777"
                val juridiskOrgnummer = "111222333"

                val message = defaultSendtSykmeldingMessage(
                    fnr = fnr,
                    orgnummer = orgnummer,
                    juridiskOrgnummer = juridiskOrgnummer,
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    ),
                    riktigNarmesteLeder = null,
                )

                val nlBehovSlot = slot<LinemanagerRequirementWrite>()
                val skipCheckSlot = slot<Boolean>()

                handler.handleNarmestelederbehov(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = capture(nlBehovSlot),
                        skipSykmeldingCheck = capture(skipCheckSlot),
                        behovSource = BehovSource(message.kafkaMetadata.sykmeldingId, source = SENDT_SYKMELDING_TOPIC),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }

                assert(nlBehovSlot.captured.employeeIdentificationNumber.value == fnr)
                assert(nlBehovSlot.captured.orgNumber.value == orgnummer)
                assert(skipCheckSlot.captured)
            }

            it("should not create NL behov when arbeidsgiver is null") {
                val message = defaultSendtSykmeldingMessage()
                    .copy(event = defaultSendtSykmeldingMessage().event.copy(arbeidsgiver = null))

                handler.handleNarmestelederbehov(message)

                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any())
                }
            }

            it("should not create NL behov when fnr is invalid") {
                val message = defaultSendtSykmeldingMessage(fnr = "123")

                handler.handleNarmestelederbehov(message)

                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any(), any())
                }
            }

            it("should not create NL behov when orgnummer is invalid") {
                val message = defaultSendtSykmeldingMessage(orgnummer = "123")

                handler.handleNarmestelederbehov(message)

                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any(), any())
                }
            }
        }
    })
