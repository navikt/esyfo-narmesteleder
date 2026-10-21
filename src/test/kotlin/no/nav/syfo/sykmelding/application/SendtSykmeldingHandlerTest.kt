package no.nav.syfo.sykmelding.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import no.nav.syfo.defaultSendtSykmeldingMessage
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovResult
import no.nav.syfo.narmestelederbehov.application.MainOrganizationSource
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovSource
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederrelasjon.application.HasActiveNarmestelederrelasjon
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

class SendtSykmeldingHandlerTest :
    FunSpec({

        val createNarmestelederbehov = RecordingCreateNarmestelederbehov()
        val sykmeldingService = SykmeldingService(UnusedSykmeldingDb)
        val narmestelederBruddService = mockk<NarmestelederBruddService>()
        var hasActiveRelation = false
        var relationLookupFailure: IllegalStateException? = null
        val relationLookups = mutableListOf<Pair<PersonalIdentificationNumber, OrganizationNumber>>()
        val hasActiveNarmestelederrelasjon = HasActiveNarmestelederrelasjon { employeeIdent, organizationNumber ->
            relationLookups.add(employeeIdent to organizationNumber)
            relationLookupFailure?.let { throw it }
            hasActiveRelation
        }
        val handler = SendtSykmeldingHandler(
            createNarmestelederbehov,
            sykmeldingService,
            narmestelederBruddService,
            hasActiveNarmestelederrelasjon,
        )

        beforeEach {
            clearAllMocks(currentThreadOnly = true)
            hasActiveRelation = false
            relationLookupFailure = null
            relationLookups.clear()
            createNarmestelederbehov.commands.clear()
            createNarmestelederbehov.result = CreateNarmestelederbehovResult.Disabled
            coEvery { narmestelederBruddService.revokeFromSendtSykmelding(any(), any(), any(), any(), any()) } just Runs
        }

        test("logs only the validated sykmelding UUID when employer information is missing") {
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

        test("keeps the skipped message action and reason bounded without logging invalid identifiers") {
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

        test("logs an active relation skip at info without employee or organization identifiers") {
            hasActiveRelation = true
            val logger = LoggerFactory.getLogger(SendtSykmeldingHandler::class.java) as Logger
            val previousLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.INFO
            logger.addAppender(appender)
            try {
                val message = defaultSendtSykmeldingMessage(sykmeldingId = UUID.randomUUID().toString())

                handler.handleNarmestelederbehov(message)

                val event = appender.list.single {
                    it.keyValuePairs?.any { field -> field.key == "event_type" && field.value == "sick_leave_behov_skipped_active_relation" } == true
                }
                event.level shouldBe Level.INFO
                val fields = event.keyValuePairs.associate { it.key to it.value }
                fields["sykmelding_id"] shouldBe message.event.sykmeldingId
                fields["action"] shouldBe "CREATE_BEHOV"
                val logOutput = appender.list.joinToString { "${it.formattedMessage} ${it.keyValuePairs}" }
                logOutput.contains(message.kafkaMetadata.fnr) shouldBe false
                logOutput.contains(requireNotNull(message.event.arbeidsgiver).orgnummer) shouldBe false
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                logger.level = previousLevel
            }
        }

        context("sykmeldingKnownActive") {
            val today = LocalDate.now()
            listOf(
                "period includes today" to listOf(today.minusDays(5) to today.plusDays(5)),
                "today is the first day of the period" to listOf(today to today.plusDays(10)),
                "today is the last day of the period" to listOf(today.minusDays(10) to today),
                "at least one of multiple periods includes today" to listOf(
                    today.minusDays(30) to today.minusDays(20),
                    today.minusDays(5) to today.plusDays(5),
                    today.plusDays(10) to today.plusDays(20),
                ),
            ).forEach { (name, periods) ->
                test("is true when $name") {
                    handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage(sykmeldingsperioder = periods.toPeriods()))

                    createNarmestelederbehov.commands.single().sykmeldingKnownActive shouldBe true
                }
            }

            listOf(
                "all periods are in the past" to listOf(today.minusDays(20) to today.minusDays(10)),
                "all periods are in the future" to listOf(today.plusDays(10) to today.plusDays(20)),
                "the period ended yesterday" to listOf(today.minusDays(10) to today.minusDays(1)),
                "the period starts tomorrow" to listOf(today.plusDays(1) to today.plusDays(10)),
                "multiple periods exist but none include today" to listOf(
                    today.minusDays(30) to today.minusDays(20),
                    today.minusDays(15) to today.minusDays(10),
                    today.plusDays(10) to today.plusDays(20),
                ),
            ).forEach { (name, periods) ->
                test("is false when $name") {
                    handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage(sykmeldingsperioder = periods.toPeriods()))

                    createNarmestelederbehov.commands.single().sykmeldingKnownActive shouldBe false
                }
            }
        }

        context("handleSendtSykmelding general behavior") {

            test("does not create NL behov when an active relation exists in the same organization") {
                hasActiveRelation = true
                val message = defaultSendtSykmeldingMessage(riktigNarmesteLeder = null)
                val countBefore = COUNT_CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER.count()

                handler.handleNarmestelederbehov(message)

                createNarmestelederbehov.commands.shouldBeEmpty()
                relationLookups shouldBe listOf(
                    PersonalIdentificationNumber(message.kafkaMetadata.fnr) to OrganizationNumber(requireNotNull(message.event.arbeidsgiver).orgnummer),
                )
                COUNT_CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER.count() shouldBe countBefore + 1
            }

            test("propagates an active relation lookup failure without creating NL behov") {
                relationLookupFailure = IllegalStateException("Relation lookup unavailable")
                val countBefore = COUNT_CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER.count()

                shouldThrow<IllegalStateException> {
                    handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage())
                } shouldBe relationLookupFailure

                createNarmestelederbehov.commands.shouldBeEmpty()
                COUNT_CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER.count() shouldBe countBefore
            }

            test("should not create NL behov when riktigNarmesteLeder is answered") {
                val message = defaultSendtSykmeldingMessage(
                    riktigNarmesteLeder = RiktigNarmesteLeder(
                        sporsmaltekst = "Er dette riktig leder?",
                        svar = "JA"
                    )
                )

                handler.handleNarmestelederbehov(message)

                createNarmestelederbehov.commands.shouldBeEmpty()
                relationLookups shouldBe emptyList()
            }

            test("should revoke NL relation and track Kafka metadata when riktigNarmesteLeder is answered NEI") {
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
                createNarmestelederbehov.commands.shouldBeEmpty()
                relationLookups shouldBe emptyList()
            }

            test("should create NL behov with correct command when riktigNarmesteLeder is null") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    fnr = "12345678901",
                    orgnummer = "999888777",
                    juridiskOrgnummer = "111222333",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    ),
                    riktigNarmesteLeder = null,
                )

                handler.handleNarmestelederbehov(message)

                createNarmestelederbehov.commands.single() shouldBe CreateNarmestelederbehovCommand(
                    employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("999888777")),
                    manager = null,
                    reason = BehovReason.INGEN_LEDER_REGISTRERT,
                    revokedRelationId = null,
                    sykmeldingKnownActive = true,
                    mainOrganization = MainOrganizationSource.FromSykmelding("111222333"),
                    source = NarmestelederbehovSource.SendtSykmelding(message.kafkaMetadata.sykmeldingId),
                )
                relationLookups shouldBe listOf(PersonalIdentificationNumber("12345678901") to OrganizationNumber("999888777"))
            }

            test("passes a missing juridiskOrgnummer on to the use case") {
                handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage(juridiskOrgnummer = null))

                createNarmestelederbehov.commands.single().mainOrganization shouldBe MainOrganizationSource.FromSykmelding(null)
            }

            test("throws so the record is retried when an upstream is unavailable") {
                createNarmestelederbehov.result = CreateNarmestelederbehovResult.UpstreamUnavailable(
                    UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException()),
                )

                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage())
                }
            }

            listOf(
                CreateNarmestelederbehovResult.AlreadyExists,
                CreateNarmestelederbehovResult.NoActiveSykmelding,
                CreateNarmestelederbehovResult.Disabled,
            ).forEach { result ->
                test("completes without error when the use case returns $result") {
                    createNarmestelederbehov.result = result

                    handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage())

                    createNarmestelederbehov.commands.size shouldBe 1
                }
            }

            test("should not create NL behov when arbeidsgiver is null") {
                val message = defaultSendtSykmeldingMessage()
                    .copy(event = defaultSendtSykmeldingMessage().event.copy(arbeidsgiver = null))

                handler.handleNarmestelederbehov(message)

                createNarmestelederbehov.commands.shouldBeEmpty()
                relationLookups shouldBe emptyList()
            }

            test("should not create NL behov when fnr is invalid") {
                handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage(fnr = "123"))

                createNarmestelederbehov.commands.shouldBeEmpty()
                relationLookups shouldBe emptyList()
            }

            test("should not create NL behov when orgnummer is invalid") {
                handler.handleNarmestelederbehov(defaultSendtSykmeldingMessage(orgnummer = "123"))

                createNarmestelederbehov.commands.shouldBeEmpty()
                relationLookups shouldBe emptyList()
            }
        }
    })

private class RecordingCreateNarmestelederbehov : CreateNarmestelederbehov {
    val commands = mutableListOf<CreateNarmestelederbehovCommand>()
    var result: CreateNarmestelederbehovResult = CreateNarmestelederbehovResult.Disabled

    override suspend fun execute(command: CreateNarmestelederbehovCommand): CreateNarmestelederbehovResult {
        commands += command
        return result
    }
}

private fun List<Pair<LocalDate, LocalDate>>.toPeriods() = map { (fom, tom) -> SykmeldingsperiodeAGDTO(fom = fom, tom = tom) }

private object UnusedSykmeldingDb : SykmeldingDb {
    override suspend fun transaction(block: suspend SykmeldingTransaction.() -> Unit) = error("Unexpected sykmelding transaction")
}
