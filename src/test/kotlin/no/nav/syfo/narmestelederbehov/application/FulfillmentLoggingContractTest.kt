package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
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
            employeeIdent.value, managerIdent.value, organizationNumber.value, behovId.value.toString(),
            employee.name.firstName, requireNotNull(employee.name.middleName), employee.name.primaryLastName,
            manager.name.firstName, requireNotNull(manager.name.middleName), manager.name.primaryLastName,
            "manager@example.test", "+4799999999", "system-user", "test-token", "11223344556",
            "private-name-canary", "private-email-canary", "private-phone-canary", "private-exception-canary",
        )

        fun checkEvent(name: String, level: String) = mapper.readTree(capture.records.single()).also {
            contract.assertValid(capture.records, expectedCount = 1)
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
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            } finally {
                capture.close()
            }
        }

        listOf(lpsSystemUser to "LPS", personnelManager to "PERSONNEL_MANAGER").forEach { (subject, source) ->
            listOf(
                DialogportenCompletionAttempt.Completed to "COMPLETED",
                DialogportenCompletionAttempt.Failed to "FAILED",
            ).forEach { (completion, code) ->
                test("logs one successful $source fulfillment with $code dialog completion") {
                    createUseCase(dialog = FakeDialog(completion)).execute(command(accessSubject = subject))

                    val record = checkEvent("narmestelederbehov_fulfillment_completed", "INFO")
                    record["relation_source"].asText() shouldBe source
                    record["dialogporten_completion"].asText() shouldBe code
                    record.has("outcome_code") shouldBe false
                }
            }
        }

        val rejectionCases: List<Triple<String, () -> FulfillNarmestelederbehovUseCase, FulfillNarmestelederbehovCommand>> = listOf(
            Triple("INVALID_MANAGER_CONTACT_DETAILS", { createUseCase() }, command("private-email-canary", "private-phone-canary")),
            Triple("NOT_FOUND", { createUseCase(repository = FakeBehovRepository(null)) }, command()),
            Triple("ACCESS_DENIED", { createUseCase(access = FakeOrganizationAccess(OrganizationAccessResult.Denied)) }, command()),
            Triple("NO_ACTIVE_SYKMELDING", { createUseCase(sykmelding = FakeActiveSykmeldingLookup(false)) }, command()),
            Triple("NO_EMPLOYMENT", { createUseCase(employment = FakeEmploymentLookup(false)) }, command()),
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
            }
        }

        listOf("sykmelding", "establish", "fulfilled", "dialog").forEach { failingEffect ->
            listOf(IllegalStateException("private-exception-canary"), CancellationException("private-exception-canary")).forEach { failure ->
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
