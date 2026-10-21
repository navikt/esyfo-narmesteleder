package no.nav.syfo.narmestelederbehov.application

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonResult
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import org.slf4j.MDC

class FulfillmentLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(fulfillmentCompleted, fulfillmentRejected)
        val productionLogs = extension(ProductionLogs(FulfillNarmestelederbehovUseCase::class.java))
        lateinit var capture: LogCapture
        val privacyCanaries = listOf(
            employeeIdent.value, managerIdent.value, organizationNumber.value,
            "Employee", "EmployeeMiddle", "Employee",
            "Manager", "ManagerMiddle", "Manager",
            "manager@example.test", "+4799999999", "system-user", "test-token", "11223344556",
            "private-name-canary", "private-email-canary", "private-phone-canary", "private-exception-canary",
        )
        val failureEventsWithBehovId = setOf(
            "narmestelederbehov_dialogporten_completion_failed",
            "narmestelederbehov_dialog_status_persistence_failed",
        )

        fun checkEvent(name: String, level: String) = mapper.readTree(
            capture.records.single { mapper.readTree(it)["event_type"].asText() == name },
        ).also {
            contract.assertValid(
                capture.records.filter { line ->
                    mapper.readTree(line)["event_type"].asText() in setOf(fulfillmentCompleted.name, fulfillmentRejected.name)
                },
                expectedCount = 1
            )
            it["event_type"].asText() shouldBe name
            it["level"].asText() shouldBe level
            it["operation"].asText() shouldBe "fulfill_narmestelederbehov"
            it["logger_name"].asText() shouldBe FulfillNarmestelederbehovUseCase::class.java.name
        }

        beforeTest {
            capture = productionLogs.capture()
        }
        afterTest {
            try {
                capture.records.forEach { line ->
                    privacyCanaries.forEach { line shouldNotContain it }
                    val record = mapper.readTree(line)
                    if (record["event_type"].asText() in failureEventsWithBehovId) {
                        record["behov_id"].asText() shouldBe behovId.value.toString()
                        (record.deepCopy<ObjectNode>().apply { remove("behov_id") }.toString())
                            .shouldNotContain(behovId.value.toString())
                    } else {
                        record.has("behov_id") shouldBe false
                        line shouldNotContain behovId.value.toString()
                    }
                }
            } finally {
                capture.close()
            }
        }

        listOf(lpsSystemUser to "LPS", personnelManager to "PERSONNEL_MANAGER").forEach { (subject, source) ->
            listOf(
                DialogportenCompletionAttempt.Completed to "COMPLETED",
                DialogportenCompletionAttempt.Failed to "FAILED",
                DialogportenCompletionAttempt.NotApplicable to "NOT_APPLICABLE",
            ).forEach { (completion, code) ->
                test("logs one successful $source fulfillment with $code dialog completion") {
                    val dialog = FakeDialog(
                        completion = if (completion == DialogportenCompletionAttempt.Failed) {
                            DialogCompletion.Unavailable(UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IllegalStateException("private-exception-canary")))
                        } else {
                            DialogCompletion.Completed
                        },
                    )
                    val repository = FakeBehovRepository(
                        behov,
                        markResult = MarkFulfilledResult.Marked(behovId, null).takeIf {
                            completion == DialogportenCompletionAttempt.NotApplicable
                        },
                    )
                    createUseCase(dialog = dialog, repository = repository).execute(command(accessSubject = subject))

                    val record = checkEvent("narmestelederbehov_fulfillment_completed", "INFO")
                    record["relation_source"].asText() shouldBe source
                    record["dialogporten_completion"].asText() shouldBe code
                    record.has("outcome_code") shouldBe false
                    if (completion == DialogportenCompletionAttempt.Failed) {
                        val failureRecord = mapper.readTree(
                            capture.records.single {
                                mapper.readTree(it)["event_type"].asText() == "narmestelederbehov_dialogporten_completion_failed"
                            }
                        )
                        failureRecord["level"].asText() shouldBe "WARN"
                        failureRecord["behov_id"].asText() shouldBe behovId.value.toString()
                        failureRecord["upstream"].asText() shouldBe "texas"
                        failureRecord["failure_stage"].asText() shouldBe "token_exchange"
                        failureRecord["upstream_status"].asInt() shouldBe 503
                        failureRecord.has("dialog_id") shouldBe false
                        capture.records.size shouldBe 2
                    } else {
                        capture.records.size shouldBe 1
                    }
                }
            }
        }

        test("leaves upstream unavailable logging to the HTTP edge without a fulfillment rejection") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-exception-canary"))
            val relation = FakeRelationEstablisher(result = EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure))
            createUseCase(relation = relation).execute(command()) shouldBe FulfillNarmestelederbehovResult.UpstreamUnavailable(failure)
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("unavailable access leaves logging to the HTTP edge without a fulfillment rejection") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-exception-canary"))
            createUseCase(access = FakeOrganizationAccess(OrganizationAccessResult.Unavailable(failure))).execute(command()) shouldBe
                FulfillNarmestelederbehovResult.UpstreamUnavailable(failure)
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("logs a bounded status persistence failure with behov id only in its field") {
            createUseCase(
                repository = FakeBehovRepository(
                    behov,
                    dialogStatusFailure = IllegalStateException("private-exception-canary"),
                ),
            ).execute(command())

            val record = checkEvent("narmestelederbehov_fulfillment_completed", "INFO")
            record["dialogporten_completion"].asText() shouldBe "FAILED"
            val failureRecord = mapper.readTree(
                capture.records.single {
                    mapper.readTree(it)["event_type"].asText() == "narmestelederbehov_dialog_status_persistence_failed"
                },
            )
            failureRecord["level"].asText() shouldBe "WARN"
            failureRecord["behov_id"].asText() shouldBe behovId.value.toString()
            capture.records.size shouldBe 2
        }

        val rejectionCases: List<Triple<String, () -> FulfillNarmestelederbehovUseCase, FulfillNarmestelederbehovCommand>> = listOf(
            Triple("INVALID_MANAGER_CONTACT_DETAILS", { createUseCase() }, command("private-email-canary", "private-phone-canary")),
            Triple("NOT_FOUND", { createUseCase(repository = FakeBehovRepository(null)) }, command()),
            Triple(
                "BEHOV_MISSING_AFTER_PUBLICATION",
                { createUseCase(repository = FakeBehovRepository(behov, markResult = MarkFulfilledResult.Missing)) },
                command(),
            ),
            Triple("ACCESS_DENIED", { createUseCase(access = FakeOrganizationAccess(OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS))) }, command()),
            Triple(
                "NO_ACTIVE_SYKMELDING",
                { createUseCase(relation = FakeRelationEstablisher(result = EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organizationNumber))) },
                command(),
            ),
            Triple(
                "NO_EMPLOYMENT",
                { createUseCase(relation = FakeRelationEstablisher(result = EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.None))) },
                command(),
            ),
            Triple(
                "PERSON_NOT_FOUND",
                { createUseCase(relation = FakeRelationEstablisher(result = EstablishNarmestelederrelasjonResult.PersonNotFound)) },
                command(),
            ),
            Triple(
                "MANAGER_NAME_MISMATCH",
                {
                    createUseCase(
                        relation = FakeRelationEstablisher(
                            result = EstablishNarmestelederrelasjonResult.ManagerNameMismatch,
                        ),
                    )
                },
                command(),
            ),
        )
        rejectionCases.forEach { (code, useCase, input) ->
            test("logs one bounded rejection for $code") {
                useCase().execute(input)

                val record = checkEvent("narmestelederbehov_fulfillment_rejected", "WARN")
                record["outcome_code"].asText() shouldBe code
                record.has("relation_source") shouldBe false
                record.has("dialogporten_completion") shouldBe false
                if (code == "INVALID_MANAGER_CONTACT_DETAILS") {
                    val issues = record["validation_issues"]
                    issues.size() shouldBe record["issue_count"].asInt()
                    issues.forEach { issue ->
                        issue.size() shouldBe 2
                        issue["field"].asText() shouldBeIn setOf("MOBILE", "EMAIL")
                        issue["reason"].asText() shouldMatch Regex("[A-Z_]+")
                    }
                } else {
                    record.has("validation_issues") shouldBe false
                }
            }
        }

        listOf("establish", "fulfilled", "dialog").forEach { failingEffect ->
            val failures = listOf(IllegalStateException("private-exception-canary"), CancellationException("private-exception-canary"))
            failures.forEach { failure ->
                test("does not log a business outcome for ${failure::class.simpleName} at $failingEffect") {
                    val useCase = createUseCase(
                        repository = FakeBehovRepository(behov, updateFailure = failure.takeIf { failingEffect == "fulfilled" }),
                        relation = FakeRelationEstablisher(failure = failure.takeIf { failingEffect == "establish" }),
                        dialog = FakeDialog(failure = failure.takeIf { failingEffect == "dialog" }),
                    )

                    shouldThrow<Exception> { useCase.execute(command()) } shouldBe failure

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

            checkEvent("narmestelederbehov_fulfillment_completed", "INFO")["trace_id"].asText() shouldBe traceId
        }
    })
