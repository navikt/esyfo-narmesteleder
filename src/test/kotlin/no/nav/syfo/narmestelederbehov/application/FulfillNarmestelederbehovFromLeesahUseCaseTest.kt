package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.util.UUID

class FulfillNarmestelederbehovFromLeesahUseCaseTest :
    FunSpec({
        test("fulfills every open behov and saves the status before completing its dialog") {
            val effects = mutableListOf<String>()
            val repository = LeesahBehovRepository(
                effects,
                openBehov = listOf(WITH_DIALOG, WITHOUT_DIALOG),
                dialogIds = mapOf(WITH_DIALOG to DIALOG_ID, WITHOUT_DIALOG to null),
            )

            useCase(effects, repository).execute(EMPLOYEE)

            effects shouldBe listOf(
                "find",
                "fulfilled:$WITH_DIALOG",
                "metric",
                "dialog",
                "dialog-status:$WITH_DIALOG",
                "fulfilled:$WITHOUT_DIALOG",
                "metric",
            )
        }

        test("looks up open behov for the employee in the message") {
            val repository = LeesahBehovRepository()

            useCase(mutableListOf(), repository).execute(EMPLOYEE)

            repository.requestedEmployee shouldBe EMPLOYEE
        }

        test("does nothing when the employee has no open behov") {
            val effects = mutableListOf<String>()

            useCase(effects, LeesahBehovRepository(effects)).execute(EMPLOYEE)

            effects shouldBe listOf("find")
        }

        test("skips a behov that disappeared before it was fulfilled") {
            val effects = mutableListOf<String>()
            val repository = LeesahBehovRepository(effects, openBehov = listOf(WITH_DIALOG), missing = setOf(WITH_DIALOG))

            useCase(effects, repository).execute(EMPLOYEE)

            effects shouldBe listOf("find", "fulfilled:$WITH_DIALOG")
        }

        test("keeps the behov fulfilled and continues when Dialogporten fails") {
            val effects = mutableListOf<String>()
            val repository = LeesahBehovRepository(
                effects,
                openBehov = listOf(WITH_DIALOG, WITHOUT_DIALOG),
                dialogIds = mapOf(WITH_DIALOG to DIALOG_ID, WITHOUT_DIALOG to null),
            )

            withProductionLogs(FulfillNarmestelederbehovFromLeesahUseCase::class.java, Level.INFO) { logs ->
                val failure = UpstreamFailure(UpstreamName("altinn-authentication"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, errors = listOf(UpstreamError("server_error")))
                useCase(effects, repository, completion = DialogCompletion.Unavailable(failure)).execute(EMPLOYEE)

                effects shouldBe listOf("find", "fulfilled:$WITH_DIALOG", "metric", "dialog", "fulfilled:$WITHOUT_DIALOG", "metric")
                val events = logs.logs.map { it.fields }
                val failed = events.single { it["event_type"] == "narmestelederbehov_dialogporten_completion_failed" }
                failed["upstream"] shouldBe "altinn-authentication"
                failed["failure_stage"] shouldBe "token_exchange"
                failed["upstream_status"] shouldBe 503
                failed["behov_id"] shouldBe WITH_DIALOG.value.toString()
                events.filter { it["event_type"] == "narmestelederbehov_fulfilled_from_leesah" }
                    .map { it["dialogporten_completion"] } shouldBe listOf("FAILED", "NOT_APPLICABLE")
            }
        }

        test("propagates a status save failure so the record is retried") {
            val effects = mutableListOf<String>()
            val repository = LeesahBehovRepository(effects, openBehov = listOf(WITH_DIALOG), fulfillFailure = IllegalStateException("database down"))

            shouldThrow<IllegalStateException> {
                useCase(effects, repository).execute(EMPLOYEE)
            }

            effects shouldBe listOf("find", "fulfilled:$WITH_DIALOG")
        }

        listOf(IllegalStateException("unexpected"), CancellationException("stopping")).forEach { failure ->
            test("propagates ${failure.javaClass.simpleName} from Dialogporten without business logging") {
                val effects = mutableListOf<String>()
                val repository = LeesahBehovRepository(effects, openBehov = listOf(WITH_DIALOG), dialogIds = mapOf(WITH_DIALOG to DIALOG_ID))
                withProductionLogs(FulfillNarmestelederbehovFromLeesahUseCase::class.java, Level.INFO) { logs ->
                    shouldThrow<Exception> {
                        useCase(effects, repository, dialogFailure = failure).execute(EMPLOYEE)
                    } shouldBe failure
                    effects shouldBe listOf("find", "fulfilled:$WITH_DIALOG", "metric", "dialog")
                    logs.logs shouldBe emptyList()
                }
            }
        }
    })

private val EMPLOYEE = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("910000001"))
private val WITH_DIALOG = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
private val WITHOUT_DIALOG = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
private val DIALOG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

private class LeesahBehovRepository(
    private val effects: MutableList<String> = mutableListOf(),
    private val openBehov: List<NarmestelederbehovId> = emptyList(),
    private val dialogIds: Map<NarmestelederbehovId, UUID?> = emptyMap(),
    private val missing: Set<NarmestelederbehovId> = emptySet(),
    private val fulfillFailure: Throwable? = null,
) : FulfillNarmestelederbehovFromLeesahRepository {
    var requestedEmployee: Employee? = null

    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> {
        effects += "find"
        requestedEmployee = employee
        return openBehov
    }

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult {
        effects += "fulfilled:$id"
        fulfillFailure?.let { throw it }
        return if (id in missing) MarkFulfilledResult.Missing else MarkFulfilledResult.Marked(id, dialogIds[id])
    }

    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult {
        effects += "dialog-status:$id"
        return MarkDialogCompletedResult.Marked
    }
}

private fun useCase(
    effects: MutableList<String>,
    repository: FulfillNarmestelederbehovFromLeesahRepository,
    dialogFailure: Throwable? = null,
    completion: DialogCompletion = DialogCompletion.Completed,
) = FulfillNarmestelederbehovFromLeesahUseCase(
    behovRepository = repository,
    metrics = { effects += "metric" },
    dialog = FakeDialog(effects, dialogFailure, completion),
)
