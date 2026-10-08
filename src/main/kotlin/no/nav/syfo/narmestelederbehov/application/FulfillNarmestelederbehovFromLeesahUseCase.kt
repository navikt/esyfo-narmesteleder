package no.nav.syfo.narmestelederbehov.application

import no.nav.esyfo.observability.Event
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.slf4j.event.Level

/**
 * Fulfills the employee's open behov when Leesah reports an active narmesteleder registered outside this service.
 * The status is saved before the Dialogporten dialog is completed, so a dialog failure leaves the behov fulfilled.
 */
class FulfillNarmestelederbehovFromLeesahUseCase(
    private val openBehov: OpenNarmestelederbehovForEmployee,
    private val behovRepository: NarmestelederbehovRepository,
    private val metrics: LeesahFulfillmentMetrics,
    private val dialogCompletion: NarmestelederbehovDialogCompletion,
) {
    suspend fun execute(employee: Employee) {
        openBehov.find(employee).forEach { fulfill(it) }
    }

    private suspend fun fulfill(id: NarmestelederbehovId) {
        when (val marked = behovRepository.markFulfilled(id)) {
            is MarkFulfilledResult.Marked -> {
                metrics.recordFulfilled()
                val completion = dialogCompletion.complete(marked)
                logger.event(fulfilledFromLeesah, FulfilledFromLeesah(id, completion))
            }
            MarkFulfilledResult.Missing -> Unit
        }
    }

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovFromLeesahUseCase::class.java)
    }
}

private data class FulfilledFromLeesah(val id: NarmestelederbehovId, val dialogCompletion: DialogportenCompletionAttempt)

private val fulfilledFromLeesah = Event<FulfilledFromLeesah>(
    name = "narmestelederbehov_fulfilled_from_leesah",
    level = Level.INFO,
    message = "Narmestelederbehov fulfilled after a new narmesteleder was registered on Leesah",
    operation = "fulfill_narmestelederbehov_from_leesah",
    fields = mapOf(
        "behov_id" to { it.id.value.toString() },
        "dialogporten_completion" to { it.dialogCompletion.logValue },
    ),
)
