package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import org.slf4j.LoggerFactory
import java.util.UUID

class FulfillNarmestelederbehovUseCaseTest :
    FunSpec({
        test("fulfills in preserved side-effect order and passes normalized contact to establish") {
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
        }

        test("derives personnel manager relation source") {
            val relation = FakeRelationEstablisher()

            createUseCase(relation = relation).execute(command(accessSubject = personnelManager)) shouldBe fulfilledResult(
                relationSource = RelationSource.PERSONNEL_MANAGER,
            )

            requireNotNull(relation.command).source shouldBe RelationSource.PERSONNEL_MANAGER
        }

        test("returns combined invalid contact issues without loading the behov") {
            val effects = mutableListOf<String>()

            createUseCase(effects = effects).execute(command(email = "invalid", mobile = "+47-99999999")) shouldBe
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
        }

        test("invalid contact takes precedence over missing behov and denied access") {
            val effects = mutableListOf<String>()
            val invalidCommand = command(email = "invalid", mobile = "+47-99999999")
            listOf(
                FakeBehovRepository(null, effects) to FakeOrganizationAccess(effects = effects),
                FakeBehovRepository(behov, effects) to
                    FakeOrganizationAccess(OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS), effects),
            ).forEach { (repository, access) ->
                createUseCase(repository = repository, access = access, effects = effects)
                    .execute(invalidCommand) shouldBe FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS),
                        ManagerContactValidationIssue(ManagerContactField.EMAIL, ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID),
                    ),
                )
                effects shouldBe emptyList()
            }
        }

        test("returns expected failures before later effects") {
            val cases = listOf(
                Case(
                    result = FulfillNarmestelederbehovResult.NotFound,
                    behovForFulfillment = null,
                    expectedEffects = listOf("load"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, organizationNumber),
                    accessResult = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS),
                    expectedEffects = listOf("load", "access"),
                ),
            )

            cases.forEach { case ->
                val effects = mutableListOf<String>()
                createUseCase(
                    repository = FakeBehovRepository(case.behovForFulfillment, effects),
                    access = FakeOrganizationAccess(case.accessResult, effects),
                    effects = effects,
                ).execute(command()) shouldBe case.result

                effects shouldBe case.expectedEffects
            }
        }

        test("maps each establish rejection without fulfilling or completing dialog") {
            listOf(
                EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organizationNumber) to
                    FulfillNarmestelederbehovResult.NoActiveSykmelding(organizationNumber),
                EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.None) to
                    FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.None),
                EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NotInOrganization) to
                    FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.NotInOrganization),
                EstablishNarmestelederrelasjonResult.PersonNotFound to FulfillNarmestelederbehovResult.PersonNotFound,
                EstablishNarmestelederrelasjonResult.ManagerNameMismatch to
                    FulfillNarmestelederbehovResult.ManagerNameMismatch,
            ).forEach { (rejection, expected) ->
                val effects = mutableListOf<String>()
                createUseCase(relation = FakeRelationEstablisher(effects, result = rejection), effects = effects)
                    .execute(command()) shouldBe expected
                effects shouldBe listOf("load", "access", "establish")
            }
        }

        test("propagates upstream unavailable without marking the behov or completing dialog") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.REQUEST, null, IllegalStateException())
            val relation = FakeRelationEstablisher(effects, result = EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure))
            createUseCase(relation = relation, effects = effects).execute(command()) shouldBe
                FulfillNarmestelederbehovResult.UpstreamUnavailable(failure)
            effects shouldBe listOf("load", "access", "establish")
        }

        test("keeps fulfillment successful when Dialogporten completion fails") {
            val effects = mutableListOf<String>()
            val failure = IllegalStateException("private-exception-canary")
            val logger = LoggerFactory.getLogger(FulfillNarmestelederbehovUseCase::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previousLevel = logger.level
            logger.level = Level.WARN
            logger.addAppender(appender)
            try {
                createUseCase(
                    dialog = FakeDialog(effects = effects, failure = failure),
                    effects = effects,
                ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Failed)

                effects shouldBe listOf(
                    "load",
                    "access",
                    "establish",
                    "fulfilled",
                    "dialog",
                )
                val failureEvents = appender.list.filter { event ->
                    event.keyValuePairs.any { it.key == "event_type" && it.value == "narmestelederbehov_dialogporten_completion_failed" }
                }
                failureEvents.size shouldBe 1
                val event = failureEvents.single()
                val fields = event.keyValuePairs.associate { it.key to it.value }
                event.level shouldBe Level.WARN
                fields["behov_id"] shouldBe behovId.value.toString()
                fields.containsKey("dialog_id") shouldBe false
                fields["operation"] shouldBe "fulfill_narmestelederbehov"
                fields["upstream"] shouldBe "dialogporten"
                event.throwableProxy.message shouldBe "java.lang.IllegalStateException"
                (event.formattedMessage + fields.toString() + event.throwableProxy.message)
                    .contains("private-exception-canary") shouldBe false
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("returns a distinct missing result after publication without dialog completion") {
            val effects = mutableListOf<String>()
            createUseCase(
                repository = FakeBehovRepository(behov, effects, markResult = MarkFulfilledResult.Missing),
                effects = effects,
            ).execute(command()) shouldBe FulfillNarmestelederbehovResult.BehovMissingAfterPublication
            effects shouldBe listOf("load", "access", "establish", "fulfilled")
        }

        test("skips dialog and status update when no dialog id was returned") {
            val effects = mutableListOf<String>()
            createUseCase(
                repository = FakeBehovRepository(behov, effects, markResult = MarkFulfilledResult.Marked(behovId, null)),
                effects = effects,
            ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.NotApplicable)
            effects.takeLast(2) shouldBe listOf("establish", "fulfilled")
        }

        test("status persistence failure leaves the fulfilled request successful") {
            val effects = mutableListOf<String>()
            val logger = LoggerFactory.getLogger(FulfillNarmestelederbehovUseCase::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previousLevel = logger.level
            logger.level = Level.WARN
            logger.addAppender(appender)
            try {
                createUseCase(
                    repository = FakeBehovRepository(behov, effects, dialogStatusFailure = IllegalStateException("private-exception-canary")),
                    effects = effects,
                ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Failed)
                effects.takeLast(2) shouldBe listOf("dialog", "dialog-status")
                val failureEvents = appender.list.filter { event ->
                    event.keyValuePairs.any { it.key == "event_type" && it.value == "narmestelederbehov_dialog_status_persistence_failed" }
                }
                failureEvents.size shouldBe 1
                val event = failureEvents.single()
                val fields = event.keyValuePairs.associate { it.key to it.value }
                fields["behov_id"] shouldBe behovId.value.toString()
                event.throwableProxy.message shouldBe "java.lang.IllegalStateException"
                (event.formattedMessage + fields.toString() + event.throwableProxy.message)
                    .contains("private-exception-canary") shouldBe false
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("reports Completed without logging when another writer changed the status") {
            val effects = mutableListOf<String>()
            val logger = LoggerFactory.getLogger(FulfillNarmestelederbehovUseCase::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previousLevel = logger.level
            logger.level = Level.WARN
            logger.addAppender(appender)
            try {
                createUseCase(
                    repository = FakeBehovRepository(behov, effects, dialogStatusResult = MarkDialogCompletedResult.NotFulfilled),
                    effects = effects,
                ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Completed)
                effects.takeLast(2) shouldBe listOf("dialog", "dialog-status")
                appender.list.filter { it.level == Level.WARN } shouldBe emptyList()
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("status persistence cancellation propagates") {
            val failure = CancellationException("cancelled")
            shouldThrow<CancellationException> {
                createUseCase(repository = FakeBehovRepository(behov, dialogStatusFailure = failure)).execute(command())
            } shouldBe failure
        }

        listOf("establish", "fulfilled", "dialog").forEach { failingEffect ->
            val failures = if (failingEffect == "dialog") {
                listOf(CancellationException("cancelled"))
            } else {
                listOf(IllegalStateException("upstream failed"), CancellationException("cancelled"))
            }
            failures.forEach { failure ->
                test("propagates ${failure::class.simpleName} at $failingEffect without later effects") {
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
                }
            }
        }

        test("propagates cancellation without later effects") {
            val effects = mutableListOf<String>()
            val useCase = createUseCase(
                relation = FakeRelationEstablisher(effects, CancellationException("cancelled")),
                effects = effects,
            )

            shouldThrow<CancellationException> { useCase.execute(command()) }

            effects shouldBe listOf("load", "access", "establish")
        }
    })

internal val employeeIdent = PersonIdent("12345678901")
internal val managerIdent = PersonIdent("10987654321")
internal val organizationNumber = OrganizationNumber("123456789")
internal val behovId = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
internal val behov = Narmestelederbehov(behovId, Employee(employeeIdent, organizationNumber))
internal val personnelManager = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonIdent("11223344556"),
    accessToken = no.nav.syfo.organisasjonstilgang.application.AccessToken("test-token"),
)
internal val lpsSystemUser = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = "system-user",
    systemUserOrganizationNumber = organizationNumber,
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
    organizationAccess = access ?: FakeOrganizationAccess(effects = effects),
    establishRelation = relation ?: FakeRelationEstablisher(effects),
    dialog = dialog ?: FakeDialog(effects = effects),
)

private data class Case(
    val result: FulfillNarmestelederbehovResult,
    val behovForFulfillment: Narmestelederbehov? = behov,
    val accessResult: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
    val expectedEffects: List<String>,
)
