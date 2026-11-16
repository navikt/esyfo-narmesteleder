package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.ProductionLogRecord
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import java.util.UUID

class CreateNarmestelederbehovUseCaseTest :
    FunSpec({
        val employee = Employee(PersonalIdentificationNumber("12345678910"), OrganizationNumber("123456789"))
        val manager = PersonalIdentificationNumber("01987654321")
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
            val openBehov: List<NarmestelederbehovId> = emptyList(),
            val hasActiveSykmelding: Boolean = true,
            val mainOrganizationResult: MainOrganizationResult = MainOrganizationResult.Found("987654321"),
            val createResult: CreateBehovResult = CreateBehovResult.Created(createdId),
        ) {
            val effects = mutableListOf<String>()
            val created = mutableListOf<NewNarmestelederbehov>()
            val repository = object : CreateNarmestelederbehovRepository {
                override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = openBehov.also { effects += "find-open" }

                override suspend fun create(behov: NewNarmestelederbehov): CreateBehovResult {
                    effects += "create"
                    created += behov
                    return createResult
                }
            }
            val metrics = RecordingMetrics(effects)
            val useCase = CreateNarmestelederbehovUseCase(
                repository = repository,
                activeSykmelding = { hasActiveSykmelding.also { effects += "active-sykmelding" } },
                mainOrganization = { mainOrganizationResult.also { effects += "main-organization" } },
                metrics = metrics,
            )
        }

        test("stores a behov with the main organization without performing dialog delivery") {
            with(Fixture()) {
                val result = useCase.execute(leesahCommand())

                val expected = NewNarmestelederbehov(
                    employee = employee,
                    mainOrganizationNumber = "987654321",
                    manager = manager,
                    reason = BehovReason.DEAKTIVERT_LEDER,
                    status = BehovStatus.BEHOV_CREATED,
                    revokedRelationId = relationId,
                )
                assertSoftly {
                    result shouldBe CreateNarmestelederbehovResult.Created(createdId)
                    created shouldBe listOf(expected)
                    effects shouldBe listOf(
                        "find-open",
                        "active-sykmelding",
                        "main-organization",
                        "create",
                    )
                }
            }
        }

        test("uses the main organization reported on the sykmelding without asking Aareg or Dinesykmeldte") {
            with(Fixture()) {
                val result = useCase.execute(sykmeldingCommand("111222333"))

                val stored = created.single()
                assertSoftly {
                    result shouldBe CreateNarmestelederbehovResult.Created(createdId)
                    stored.mainOrganizationNumber shouldBe "111222333"
                    stored.status shouldBe BehovStatus.BEHOV_CREATED
                    effects shouldBe listOf("find-open", "create")
                }
            }
        }

        test("skips with a metric when an open behov already exists") {
            with(Fixture(openBehov = listOf(createdId))) {
                val logs = captureLogs { useCase.execute(leesahCommand()) shouldBe CreateNarmestelederbehovResult.AlreadyExists }

                assertSoftly {
                    effects shouldBe listOf("find-open", "metric:already-exists")
                    logs.map { it.message } shouldBe listOf("Not inserting narmestelederbehov since one already for employee and org")
                }
            }
        }

        test("skips with a metric when the employee has no active sykmelding") {
            with(Fixture(hasActiveSykmelding = false)) {
                val result = useCase.execute(leesahCommand())
                assertSoftly {
                    result shouldBe CreateNarmestelederbehovResult.NoActiveSykmelding
                    effects shouldBe listOf("find-open", "active-sykmelding", "metric:no-active-sykmelding")
                }
            }
        }

        test("does not ask Dinesykmeldte when the sykmelding is known to be active") {
            with(Fixture(hasActiveSykmelding = false)) {
                val result = useCase.execute(leesahCommand(sykmeldingKnownActive = true))
                assertSoftly {
                    result shouldBe CreateNarmestelederbehovResult.Created(createdId)
                    effects shouldBe listOf("find-open", "main-organization", "create")
                }
            }
        }

        test("stores nothing when Aareg is unavailable") {
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            with(Fixture(mainOrganizationResult = MainOrganizationResult.Unavailable(failure))) {
                val result = useCase.execute(leesahCommand())
                assertSoftly {
                    result shouldBe CreateNarmestelederbehovResult.UpstreamUnavailable(failure)
                    effects shouldBe listOf("find-open", "active-sykmelding", "main-organization")
                }
            }
        }

        test("skips with a metric and no dialog when storing conflicts with an open behov") {
            with(Fixture(createResult = CreateBehovResult.AlreadyExists)) {
                val result = useCase.execute(leesahCommand())
                assertSoftly {
                    result shouldBe CreateNarmestelederbehovResult.AlreadyExists
                    effects shouldBe listOf("find-open", "active-sykmelding", "main-organization", "create", "metric:already-exists")
                }
            }
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
                with(Fixture(mainOrganizationResult = case.mainOrganizationResult)) {
                    val logs = captureLogs { useCase.execute(case.command) shouldBe CreateNarmestelederbehovResult.Created(createdId) }

                    val stored = created.single()
                    val record = logs.single { it.level == Level.WARN }
                    val event = record.fields
                    assertSoftly {
                        stored.status shouldBe case.status
                        stored.mainOrganizationNumber shouldBe "UNKNOWN"
                        metrics.recorded shouldBe listOfNotNull(case.metric)
                        event["event_type"] shouldBe "narmestelederbehov_stored_degraded"
                        event["reason"] shouldBe case.reason
                        record.serialized shouldNotContain employee.personIdent.value
                    }
                }
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

            val events = logs.filter { it.level == Level.WARN }.map { it.fields }
            assertSoftly {
                events.map { it["behov_source"] } shouldBe listOf(NARMESTELEDER_LEESAH_SOURCE_NAME, SENDT_SYKMELDING_SOURCE_NAME, SENDT_SYKMELDING_SOURCE_NAME)
                events.map { it["narmesteleder_id"] } shouldBe listOf(relationId.toString(), null, null)
                events.map { it["sykmelding_id"] } shouldBe listOf(null, sykmeldingId.toString(), null)
            }
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

private suspend fun captureLogs(block: suspend () -> Unit): List<ProductionLogRecord> = withProductionLogs(CreateNarmestelederbehovUseCase::class.java, Level.INFO) { capture ->
    block()
    capture.logs
}
