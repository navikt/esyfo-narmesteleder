package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class FulfillmentLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(fulfillmentCompleted, fulfillmentRejected)
        val logger = LoggerFactory.getLogger(FulfillNarmestelederbehovUseCase::class.java) as Logger
        val originalSettings = logger.level to logger.isAdditive
        val productionLogging = LoggerContext()
        lateinit var productionAppender: Appender<ILoggingEvent>
        lateinit var capture: LogCapture
        val privacyCanaries = listOf(
            employeeIdent.value, managerIdent.value, organizationNumber.value,
            employee.name.firstName, requireNotNull(employee.name.middleName), employee.name.lastName,
            manager.name.firstName, requireNotNull(manager.name.middleName), manager.name.lastName,
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

        beforeSpec {
            productionLogging.putProperty("NAIS_CLUSTER_NAME", "test")
            JoranConfigurator().apply {
                context = productionLogging
                doConfigure("src/main/resources/logback.xml")
            }
            productionAppender = requireNotNull(productionLogging.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
            logger.level = Level.TRACE
            logger.isAdditive = false
            logger.addAppender(productionAppender)
        }
        afterSpec {
            logger.detachAppender(productionAppender)
            logger.level = originalSettings.first
            logger.isAdditive = originalSettings.second
            productionLogging.stop()
        }
        beforeTest {
            capture = captureLogs(logger, "stdout_json")
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
                        failure = IllegalStateException("private-exception-canary").takeIf {
                            completion == DialogportenCompletionAttempt.Failed
                        }
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
                        failureRecord.has("dialog_id") shouldBe false
                        capture.records.size shouldBe 2
                    } else {
                        capture.records.size shouldBe 1
                    }
                }
            }
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
            Triple("NO_ACTIVE_SYKMELDING", { createUseCase(sykmelding = FakeActiveSykmeldingLookup(false)) }, command()),
            Triple("NO_EMPLOYMENT", { createUseCase(employment = FakeEmploymentLookup(EmploymentResult.NONE)) }, command()),
            Triple("PERSON_NOT_FOUND", { createUseCase(personLookup = FakePersonLookup(emptyMap())) }, command()),
            Triple(
                "MANAGER_NAME_MISMATCH",
                { createUseCase() },
                command().let { it.copy(manager = it.manager.copy(lastName = "private-name-canary")) },
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
                        (issue["field"].asText() in setOf("MOBILE", "EMAIL")) shouldBe true
                        issue["reason"].asText().matches(Regex("[A-Z_]+")) shouldBe true
                    }
                } else {
                    record.has("validation_issues") shouldBe false
                }
            }
        }

        listOf("sykmelding", "establish", "fulfilled", "dialog").forEach { failingEffect ->
            val failures = if (failingEffect == "dialog") {
                listOf(CancellationException("private-exception-canary"))
            } else {
                listOf(IllegalStateException("private-exception-canary"), CancellationException("private-exception-canary"))
            }
            failures.forEach { failure ->
                test("does not log a business outcome for ${failure::class.simpleName} at $failingEffect") {
                    val useCase = createUseCase(
                        repository = FakeBehovRepository(behov, updateFailure = failure.takeIf { failingEffect == "fulfilled" }),
                        sykmelding = FakeActiveSykmeldingLookup(failure = failure.takeIf { failingEffect == "sykmelding" }),
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
