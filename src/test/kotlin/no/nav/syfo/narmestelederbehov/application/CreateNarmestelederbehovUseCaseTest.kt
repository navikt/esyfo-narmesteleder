package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.kafka.TEAMSYKMELDING_NL_LEESAH_TOPIC
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.sykmelding.kafka.SENDT_SYKMELDING_TOPIC
import org.slf4j.LoggerFactory
import java.util.UUID

class CreateNarmestelederbehovUseCaseTest :
    FunSpec({
        val employee = Employee(PersonIdent("12345678910"), OrganizationNumber("123456789"))
        val manager = PersonIdent("01987654321")
        val relationId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val sykmeldingId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val createdId = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000033"))

        fun leesahCommand(sykmeldingKnownActive: Boolean = false) = CreateNarmestelederbehovCommand(
            employee = employee,
            manager = manager,
            reason = BehovReason.DEAKTIVERT_LEDER,
            revokedRelationId = relationId,
            sykmeldingKnownActive = sykmeldingKnownActive,
            mainOrganization = MainOrganizationSource.FromEmployment,
            source = NarmestelederbehovSource.NarmestelederLeesah(relationId),
        )

        fun sykmeldingCommand(mainOrganizationNumber: String?) = CreateNarmestelederbehovCommand(
            employee = employee,
            manager = null,
            reason = BehovReason.INGEN_LEDER_REGISTRERT,
            revokedRelationId = null,
            sykmeldingKnownActive = true,
            mainOrganization = MainOrganizationSource.FromSykmelding(mainOrganizationNumber),
            source = NarmestelederbehovSource.SendtSykmelding(sykmeldingId.toString()),
        )

        class Fixture(
            persistenceEnabled: Boolean = true,
            val openBehov: List<NarmestelederbehovId> = emptyList(),
            val hasActiveSykmelding: Boolean = true,
            val mainOrganizationResult: MainOrganizationResult = MainOrganizationResult.Found("987654321"),
            val createResult: CreateBehovResult = CreateBehovResult.Created(createdId),
        ) {
            val effects = mutableListOf<String>()
            val created = mutableListOf<NewNarmestelederbehov>()
            val dialogs = mutableListOf<Pair<NarmestelederbehovId, NewNarmestelederbehov>>()
            val repository = object : NarmestelederbehovRepository {
                override suspend fun findDetails(id: NarmestelederbehovId) = error("Unexpected findDetails")
                override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) = error("Unexpected saveEmployeeName")
                override suspend fun findForFulfillment(id: NarmestelederbehovId) = error("Unexpected findForFulfillment")
                override suspend fun markFulfilled(id: NarmestelederbehovId) = error("Unexpected markFulfilled")
                override suspend fun markDialogCompleted(id: NarmestelederbehovId) = error("Unexpected markDialogCompleted")

                override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = openBehov.also { effects += "find-open" }

                override suspend fun create(behov: NewNarmestelederbehov): CreateBehovResult {
                    effects += "create"
                    created += behov
                    return createResult
                }
            }
            val metrics = RecordingMetrics(effects)
            val useCase = CreateNarmestelederbehovUseCase(
                settings = NarmestelederbehovCreationSettings(persistenceEnabled),
                repository = repository,
                activeSykmelding = { hasActiveSykmelding.also { effects += "active-sykmelding" } },
                mainOrganization = { mainOrganizationResult.also { effects += "main-organization" } },
                dialog = { id, behov ->
                    effects += "dialog"
                    dialogs += id to behov
                },
                metrics = metrics,
            )
        }

        test("stores a behov with the main organization from employment and creates the dialog last") {
            val fixture = Fixture()

            fixture.useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.Created(createdId)

            val expected = NewNarmestelederbehov(
                employee = employee,
                mainOrganizationNumber = "987654321",
                manager = manager,
                reason = BehovReason.DEAKTIVERT_LEDER,
                status = BehovStatus.BEHOV_CREATED,
                revokedRelationId = relationId,
            )
            fixture.created shouldBe listOf(expected)
            fixture.dialogs shouldBe listOf(createdId to expected)
            fixture.effects shouldBe listOf("find-open", "active-sykmelding", "main-organization", "create", "dialog")
        }

        test("uses the main organization reported on the sykmelding without asking Aareg or Dinesykmeldte") {
            val fixture = Fixture()

            fixture.useCase.execute(sykmeldingCommand("111222333")) shouldBe CreateNarmestelederbehovResult.Created(createdId)

            fixture.created.single().mainOrganizationNumber shouldBe "111222333"
            fixture.created.single().status shouldBe BehovStatus.BEHOV_CREATED
            fixture.effects shouldBe listOf("find-open", "create", "dialog")
        }

        test("does nothing when persistence is disabled") {
            val fixture = Fixture(persistenceEnabled = false)

            fixture.useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.Disabled

            fixture.effects shouldBe emptyList()
        }

        test("skips with a metric when an open behov already exists") {
            val fixture = Fixture(openBehov = listOf(createdId))

            val logs = captureLogs { fixture.useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.AlreadyExists }

            fixture.effects shouldBe listOf("find-open", "metric:already-exists")
            logs.map { it.formattedMessage } shouldBe listOf("Not inserting NarmestelederBehovEntity since one already for employee and org")
        }

        test("skips with a metric when the employee has no active sykmelding") {
            val fixture = Fixture(hasActiveSykmelding = false)

            fixture.useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.NoActiveSykmelding

            fixture.effects shouldBe listOf("find-open", "active-sykmelding", "metric:no-active-sykmelding")
        }

        test("does not ask Dinesykmeldte when the sykmelding is known to be active") {
            val fixture = Fixture(hasActiveSykmelding = false)

            fixture.useCase.execute(leesahCommand(sykmeldingKnownActive = true)) shouldBe CreateNarmestelederbehovResult.Created(createdId)

            fixture.effects shouldBe listOf("find-open", "main-organization", "create", "dialog")
        }

        test("stores nothing when Aareg is unavailable") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val fixture = Fixture(mainOrganizationResult = MainOrganizationResult.Unavailable(failure))

            fixture.useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.UpstreamUnavailable(failure)

            fixture.effects shouldBe listOf("find-open", "active-sykmelding", "main-organization")
        }

        test("skips with a metric and no dialog when storing conflicts with an open behov") {
            val fixture = Fixture(createResult = CreateBehovResult.AlreadyExists)

            fixture.useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.AlreadyExists

            fixture.effects shouldBe listOf("find-open", "active-sykmelding", "main-organization", "create", "metric:already-exists")
        }

        data class DegradedCase(
            val name: String,
            val command: CreateNarmestelederbehovCommand,
            val mainOrganizationResult: MainOrganizationResult,
            val status: BehovStatus,
            val metric: String?,
            val reason: String,
        )
        listOf(
            DegradedCase(
                "employment is missing",
                leesahCommand(),
                MainOrganizationResult.EmploymentMissing,
                BehovStatus.ARBEIDSFORHOLD_NOT_FOUND,
                "metric:stored-without-employment",
                "EMPLOYMENT_MISSING",
            ),
            DegradedCase(
                "employment has no main organization",
                leesahCommand(),
                MainOrganizationResult.MainOrganizationMissing,
                BehovStatus.HOVEDENHET_NOT_FOUND,
                null,
                "EMPLOYMENT_MAIN_ORG_MISSING",
            ),
            DegradedCase(
                "the sykmelding has no main organization",
                sykmeldingCommand(null),
                MainOrganizationResult.Found("unused"),
                BehovStatus.HOVEDENHET_NOT_FOUND,
                "metric:stored-without-main-organization",
                "SICK_LEAVE_MAIN_ORG_MISSING",
            ),
        ).forEach { case ->
            test("stores an error behov without a dialog when ${case.name}") {
                val fixture = Fixture(mainOrganizationResult = case.mainOrganizationResult)

                val logs = captureLogs { fixture.useCase.execute(case.command) shouldBe CreateNarmestelederbehovResult.Created(createdId) }

                fixture.created.single().status shouldBe case.status
                fixture.created.single().mainOrganizationNumber shouldBe "UNKNOWN"
                fixture.dialogs shouldBe emptyList()
                fixture.metrics.recorded shouldBe listOfNotNull(case.metric)
                val event = logs.single { it.level == Level.WARN }.keyValuePairs.associate { it.key to it.value }
                event["event_type"] shouldBe "narmestelederbehov_stored_degraded"
                event["reason"] shouldBe case.reason
                event.toString().contains(employee.personIdent.value) shouldBe false
            }
        }

        test("correlates degraded events with identifiers from their own source only") {
            val logs = captureLogs {
                Fixture(mainOrganizationResult = MainOrganizationResult.EmploymentMissing).useCase.execute(leesahCommand())
                Fixture().useCase.execute(sykmeldingCommand(null))
                Fixture().useCase.execute(
                    sykmeldingCommand(null).copy(source = NarmestelederbehovSource.SendtSykmelding("not-a-uuid")),
                )
            }

            val events = logs.filter { it.level == Level.WARN }.map { event -> event.keyValuePairs.associate { it.key to it.value } }
            events.map { it["behov_source"] } shouldBe listOf(TEAMSYKMELDING_NL_LEESAH_TOPIC, SENDT_SYKMELDING_TOPIC, SENDT_SYKMELDING_TOPIC)
            events.map { it["narmesteleder_id"] } shouldBe listOf(relationId.toString(), null, null)
            events.map { it["sykmelding_id"] } shouldBe listOf(null, sykmeldingId.toString(), null)
        }
    })

private class RecordingMetrics(private val effects: MutableList<String>) : NarmestelederbehovCreationMetrics {
    val recorded = mutableListOf<String>()

    private fun record(name: String) {
        recorded += name
        effects += name
    }

    override fun recordSkippedNoActiveSykmelding() = record("metric:no-active-sykmelding")
    override fun recordSkippedAlreadyExists() = record("metric:already-exists")
    override fun recordStoredWithoutMainOrganization() = record("metric:stored-without-main-organization")
    override fun recordStoredWithoutEmployment() = record("metric:stored-without-employment")
}

private suspend fun captureLogs(block: suspend () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(CreateNarmestelederbehovUseCase::class.java) as Logger
    val previousLevel = logger.level
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    logger.level = Level.INFO
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
    return appender.list.toList()
}
