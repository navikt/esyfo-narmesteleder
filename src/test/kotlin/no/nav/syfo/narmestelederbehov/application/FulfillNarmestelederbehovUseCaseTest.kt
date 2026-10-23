package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import org.slf4j.MDC
import java.util.UUID

class FulfillNarmestelederbehovUseCaseTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(fulfillmentCompleted, fulfillmentRejected)
        val productionLogs = extension(ProductionLogs(FulfillNarmestelederbehovUseCase::class.java))
        lateinit var capture: ProductionLogCapture
        val privacyCanaries = listOf(
            employeeIdent.value, managerIdent.value, organizationNumber.value,
            "Employee", "EmployeeMiddle",
            "Manager", "ManagerMiddle",
            "manager@example.test", "+4799999999", "+47-99999999", "system-user", "test-token", "11223344556",
            "private-name-canary", "private-email-canary", "private-phone-canary", "private-exception-canary",
        )
        val failureEventsWithBehovId = setOf(
            "narmestelederbehov_dialogporten_completion_failed",
            "narmestelederbehov_dialog_status_persistence_failed",
        )

        fun outcomeEvent(name: String, level: String): JsonNode {
            contract.assertValid(
                capture.records.filter { line ->
                    mapper.readTree(line).path("event_type").asText() in setOf(fulfillmentCompleted.name, fulfillmentRejected.name)
                },
                expectedCount = 1,
            )
            return mapper.readTree(capture.records.single { mapper.readTree(it).path("event_type").asText() == name }).also {
                it["level"].asText() shouldBe level
                it["operation"].asText() shouldBe "fulfill_narmestelederbehov"
                it["logger_name"].asText() shouldBe FulfillNarmestelederbehovUseCase::class.java.name
            }
        }

        fun checkCompletedEvent(relationSource: String, dialogCompletion: String, recordCount: Int = 1) {
            val record = outcomeEvent("narmestelederbehov_fulfillment_completed", "INFO")
            record["relation_source"].asText() shouldBe relationSource
            record["dialogporten_completion"].asText() shouldBe dialogCompletion
            record.has("outcome_code") shouldBe false
            capture.records.size shouldBe recordCount
        }

        fun rejectedEvent(outcomeCode: String): JsonNode = outcomeEvent("narmestelederbehov_fulfillment_rejected", "WARN").also {
            it["outcome_code"].asText() shouldBe outcomeCode
            it.has("relation_source") shouldBe false
            it.has("dialogporten_completion") shouldBe false
            if (outcomeCode != "INVALID_MANAGER_CONTACT_DETAILS") it.has("validation_issues") shouldBe false
            capture.records.size shouldBe 1
        }

        beforeTest {
            capture = productionLogs.capture()
        }
        afterTest {
            capture.use { capture ->
                capture.records.forEach { line ->
                    privacyCanaries.forEach { line shouldNotContain it }
                    val record = mapper.readTree(line)
                    if (record.path("event_type").asText() in failureEventsWithBehovId) {
                        record["behov_id"].asText() shouldBe behovId.value.toString()
                        (record.deepCopy<ObjectNode>().apply { remove("behov_id") }.toString())
                            .shouldNotContain(behovId.value.toString())
                    } else {
                        record.has("behov_id") shouldBe false
                        line shouldNotContain behovId.value.toString()
                    }
                }
            }
        }

        test("unavailable access stops before establishment writes and dialog completion and leaves logging to the HTTP edge") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-exception-canary"))
            val result = createUseCase(
                access = FakeOrganizationAccess(organizationNumber, OrganizationAccessResult.Unavailable(failure), effects),
                effects = effects,
            ).execute(command())
            result shouldBe FulfillNarmestelederbehovResult.UpstreamUnavailable(failure)
            result.shouldBeInstanceOf<FulfillNarmestelederbehovResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
            effects shouldBe listOf("load", "access")
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("fulfills in preserved side-effect order, passes normalized contact to establish and logs one completion") {
            val effects = mutableListOf<String>()
            val relation = FakeRelationEstablisher(effects)

            createUseCase(relation = relation, effects = effects).execute(command()) shouldBe fulfilledResult()

            effects shouldBe listOf(
                "load",
                "access",
                "establish",
                "fulfilled",
                "dialog",
                "dialog-status",
            )
            requireNotNull(relation.command).manager.let {
                it.email.value shouldBe "manager@example.test"
                it.mobile.value shouldBe "+4799999999"
                it.personIdent shouldBe managerIdent
            }
            requireNotNull(relation.command).employeeIdent shouldBe employeeIdent
            requireNotNull(relation.command).organizationNumber shouldBe organizationNumber
            requireNotNull(relation.command).source shouldBe RelationSource.LPS
            checkCompletedEvent("LPS", "COMPLETED")
        }

        test("derives personnel manager relation source") {
            val relation = FakeRelationEstablisher()

            createUseCase(relation = relation).execute(command(accessSubject = personnelManager)) shouldBe fulfilledResult(
                relationSource = RelationSource.PERSONNEL_MANAGER,
            )

            requireNotNull(relation.command).source shouldBe RelationSource.PERSONNEL_MANAGER
            checkCompletedEvent("PERSONNEL_MANAGER", "COMPLETED")
        }

        test("returns combined invalid contact issues without loading the behov and logs only bounded issues") {
            val effects = mutableListOf<String>()

            createUseCase(effects = effects).execute(command(email = "private-email-canary", mobile = "+47-99999999")) shouldBe
                FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(
                            ManagerContactField.MOBILE,
                            ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS,
                        ),
                        ManagerContactValidationIssue(
                            ManagerContactField.EMAIL,
                            ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
                        ),
                    ),
                )

            effects shouldBe emptyList()
            val record = rejectedEvent("INVALID_MANAGER_CONTACT_DETAILS")
            val issues = record["validation_issues"]
            issues.size() shouldBe record["issue_count"].asInt()
            issues.forEach { issue ->
                issue.size() shouldBe 2
                issue["field"].asText() shouldBeIn setOf("MOBILE", "EMAIL")
                issue["reason"].asText() shouldMatch Regex("[A-Z_]+")
            }
        }

        listOf(
            Triple("missing behov", null, OrganizationAccessResult.Granted(organizationName = null)),
            Triple("denied access", behov, OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)),
        ).forEach { (case, behovForFulfillment, accessResult) ->
            test("invalid contact takes precedence over $case") {
                val effects = mutableListOf<String>()
                val invalidCommand = command(email = "invalid", mobile = "+47-99999999")
                createUseCase(
                    repository = FakeBehovRepository(behovForFulfillment, effects),
                    access = FakeOrganizationAccess(organizationNumber, accessResult, effects),
                    effects = effects,
                ).execute(invalidCommand) shouldBe FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS),
                        ManagerContactValidationIssue(ManagerContactField.EMAIL, ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID),
                    ),
                )
                effects shouldBe emptyList()
            }
        }

        listOf(
            Case(
                outcomeCode = "NOT_FOUND",
                result = FulfillNarmestelederbehovResult.NotFound,
                behovForFulfillment = null,
                expectedEffects = listOf("load"),
            ),
            Case(
                outcomeCode = "ACCESS_DENIED",
                result = FulfillNarmestelederbehovResult.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, organizationNumber),
                accessResult = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS),
                expectedEffects = listOf("load", "access"),
            ),
        ).forEach { case ->
            test("returns ${case.outcomeCode} before later effects and logs one bounded rejection") {
                val effects = mutableListOf<String>()
                createUseCase(
                    repository = FakeBehovRepository(case.behovForFulfillment, effects),
                    access = FakeOrganizationAccess(organizationNumber, case.accessResult, effects),
                    effects = effects,
                ).execute(command()) shouldBe case.result

                effects shouldBe case.expectedEffects
                rejectedEvent(case.outcomeCode)
            }
        }

        listOf(
            Triple(
                "NO_ACTIVE_SYKMELDING",
                EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organizationNumber),
                FulfillNarmestelederbehovResult.NoActiveSykmelding(organizationNumber),
            ),
            Triple(
                "NO_EMPLOYMENT",
                EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.None),
                FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.None),
            ),
            Triple(
                "NO_EMPLOYMENT",
                EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NotInOrganization),
                FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.NotInOrganization),
            ),
            Triple("PERSON_NOT_FOUND", EstablishNarmestelederrelasjonResult.PersonNotFound, FulfillNarmestelederbehovResult.PersonNotFound),
            Triple(
                "MANAGER_NAME_MISMATCH",
                EstablishNarmestelederrelasjonResult.ManagerNameMismatch,
                FulfillNarmestelederbehovResult.ManagerNameMismatch,
            ),
        ).forEach { (outcomeCode, rejection, expected) ->
            test("maps establish rejection $rejection to $outcomeCode without fulfilling or completing dialog") {
                val effects = mutableListOf<String>()
                createUseCase(relation = FakeRelationEstablisher(effects, result = rejection), effects = effects)
                    .execute(command()) shouldBe expected
                effects shouldBe listOf("load", "access", "establish")
                rejectedEvent(outcomeCode)
            }
        }

        test("propagates upstream unavailable without marking the behov, completing dialog or logging a rejection") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.REQUEST, null, IllegalStateException("private-exception-canary"))
            val relation = FakeRelationEstablisher(effects, result = EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure))
            createUseCase(relation = relation, effects = effects).execute(command()) shouldBe
                FulfillNarmestelederbehovResult.UpstreamUnavailable(failure)
            effects shouldBe listOf("load", "access", "establish")
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("keeps fulfillment successful when Dialogporten completion fails") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("dialogporten"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-exception-canary"))
            createUseCase(
                dialog = FakeDialog(effects = effects, completion = DialogCompletion.Unavailable(failure)),
                effects = effects,
            ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Failed)

            effects shouldBe listOf(
                "load",
                "access",
                "establish",
                "fulfilled",
                "dialog",
            )
            checkCompletedEvent("LPS", "FAILED", recordCount = 2)
            val event = capture.logs.single { it.eventType == "narmestelederbehov_dialogporten_completion_failed" }
            val fields = event.fields
            event.level shouldBe Level.WARN
            fields["behov_id"] shouldBe behovId.value.toString()
            fields.keys shouldNotContain "dialog_id"
            fields["operation"] shouldBe "fulfill_narmestelederbehov"
            fields["upstream"] shouldBe "dialogporten"
            fields["failure_stage"] shouldBe "response"
            fields["upstream_status"] shouldBe 503
            event.throwableMessage shouldBe "java.lang.IllegalStateException"
        }

        test("returns a distinct missing result after publication without dialog completion") {
            val effects = mutableListOf<String>()
            createUseCase(
                repository = FakeBehovRepository(behov, effects, markResult = MarkFulfilledResult.Missing),
                effects = effects,
            ).execute(command()) shouldBe FulfillNarmestelederbehovResult.BehovMissingAfterPublication
            effects shouldBe listOf("load", "access", "establish", "fulfilled")
            rejectedEvent("BEHOV_MISSING_AFTER_PUBLICATION")
        }

        test("skips dialog and status update when no dialog id was returned") {
            val effects = mutableListOf<String>()
            createUseCase(
                repository = FakeBehovRepository(behov, effects, markResult = MarkFulfilledResult.Marked(behovId, null)),
                effects = effects,
            ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.NotApplicable)
            effects.takeLast(2) shouldBe listOf("establish", "fulfilled")
            checkCompletedEvent("LPS", "NOT_APPLICABLE")
        }

        test("status persistence failure leaves the fulfilled request successful and logs a bounded failure") {
            val effects = mutableListOf<String>()
            createUseCase(
                repository = FakeBehovRepository(behov, effects, dialogStatusFailure = IllegalStateException("private-exception-canary")),
                effects = effects,
            ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Failed)
            effects.takeLast(2) shouldBe listOf("dialog", "dialog-status")
            checkCompletedEvent("LPS", "FAILED", recordCount = 2)
            val event = capture.logs.single { it.eventType == "narmestelederbehov_dialog_status_persistence_failed" }
            event.level shouldBe Level.WARN
            event.fields["behov_id"] shouldBe behovId.value.toString()
            event.throwableMessage shouldBe "java.lang.IllegalStateException"
        }

        test("reports Completed without a warning when another writer changed the status") {
            val effects = mutableListOf<String>()
            createUseCase(
                repository = FakeBehovRepository(behov, effects, dialogStatusResult = MarkDialogCompletedResult.NotFulfilled),
                effects = effects,
            ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Completed)
            effects.takeLast(2) shouldBe listOf("dialog", "dialog-status")
            checkCompletedEvent("LPS", "COMPLETED")
        }

        test("status persistence cancellation propagates") {
            val failure = CancellationException("cancelled")
            shouldThrow<CancellationException> {
                createUseCase(repository = FakeBehovRepository(behov, dialogStatusFailure = failure)).execute(command())
            } shouldBe failure
        }

        listOf("establish", "fulfilled", "dialog").forEach { failingEffect ->
            val failures = listOf(IllegalStateException("private-exception-canary"), CancellationException("private-exception-canary"))
            failures.forEach { failure ->
                test("propagates ${failure::class.simpleName} at $failingEffect without later effects or a business outcome log") {
                    val effects = mutableListOf<String>()
                    val useCase = createUseCase(
                        repository = FakeBehovRepository(behov, effects, failure.takeIf { failingEffect == "fulfilled" }),
                        relation = FakeRelationEstablisher(effects, failure.takeIf { failingEffect == "establish" }),
                        dialog = FakeDialog(effects = effects, failure = failure.takeIf { failingEffect == "dialog" }),
                        effects = effects,
                    )

                    shouldThrow<Exception> { useCase.execute(command()) } shouldBe failure

                    val allEffects = listOf(
                        "load",
                        "access",
                        "establish",
                        "fulfilled",
                        "dialog",
                    )
                    effects shouldBe allEffects.take(allEffects.indexOf(failingEffect) + 1)
                    capture.records shouldBe emptyList()
                }
            }
        }

        test("preserves an existing trace id") {
            val traceId = "abcdefabcdefabcdefabcdefabcdefab"
            MDC.put("trace_id", traceId)
            try {
                createUseCase().execute(command())
            } finally {
                MDC.remove("trace_id")
            }

            outcomeEvent("narmestelederbehov_fulfillment_completed", "INFO")["trace_id"].asText() shouldBe traceId
        }
    })

internal val employeeIdent = PersonalIdentificationNumber("12345678901")
internal val managerIdent = PersonalIdentificationNumber("10987654321")
internal val organizationNumber = OrganizationNumber("123456789")
internal val behovId = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
internal val behov = Narmestelederbehov(behovId, Employee(employeeIdent, organizationNumber))
internal val personnelManager = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonalIdentificationNumber("11223344556"),
    accessToken = no.nav.syfo.organisasjonstilgang.application.AccessToken("test-token"),
)
internal val lpsSystemUser = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = "system-user",
    systemUserOrganizationNumber = OrganizationNumber("999777555"),
)

internal fun command(
    email: String = " manager@example.test ",
    mobile: String = "+47 99999999",
    accessSubject: OrganizationAccessSubject = lpsSystemUser,
) = FulfillNarmestelederbehovCommand(
    behovId = behovId,
    manager = ManagerContactInput(managerIdent, "Manager", email, mobile),
    accessSubject = accessSubject,
)

internal fun fulfilledResult(
    relationSource: RelationSource = RelationSource.LPS,
    dialogCompletion: DialogportenCompletionAttempt = DialogportenCompletionAttempt.Completed,
) = FulfillNarmestelederbehovResult.Fulfilled(
    relationSource = relationSource,
    dialogCompletion = dialogCompletion,
)

internal fun createUseCase(
    repository: FakeBehovRepository? = null,
    access: FakeOrganizationAccess? = null,
    relation: FakeRelationEstablisher? = null,
    dialog: FakeDialog? = null,
    effects: MutableList<String> = mutableListOf(),
) = FulfillNarmestelederbehovUseCase(
    behovRepository = repository ?: FakeBehovRepository(behov, effects),
    organizationAccess = access ?: FakeOrganizationAccess(organizationNumber, effects = effects),
    establishRelation = relation ?: FakeRelationEstablisher(effects),
    dialog = dialog ?: FakeDialog(effects = effects),
)

private data class Case(
    val outcomeCode: String,
    val result: FulfillNarmestelederbehovResult,
    val behovForFulfillment: Narmestelederbehov? = behov,
    val accessResult: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
    val expectedEffects: List<String>,
)
