package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.Event
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmestelederbehov.application.DialogportenCompletionAttempt
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.slf4j.event.Level

private val dialogportenCompletionFailed = Event<Unit>(
    name = "narmestelederbehov_dialogporten_completion_failed",
    level = Level.WARN,
    message = "Dialogporten completion failed; pending behov remains retryable",
    operation = "fulfill_narmestelederbehov",
)

class DialogportenNarmestelederbehovDialog(
    private val db: INarmestelederDb,
    private val service: DialogportenService,
) : NarmestelederbehovDialog {
    override suspend fun attemptCompletion(id: NarmestelederbehovId): DialogportenCompletionAttempt {
        try {
            val behov = db.findBehovById(id.value)
                ?: return DialogportenCompletionAttempt.NotApplicable
            if (behov.dialogId == null) return DialogportenCompletionAttempt.NotApplicable
            service.completeFulfilledDialog(behov)
            return DialogportenCompletionAttempt.Completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.event(dialogportenCompletionFailed, Unit)
            return DialogportenCompletionAttempt.Failed
        }
    }

    private companion object {
        val logger = applicationLogger(DialogportenNarmestelederbehovDialog::class.java)
    }
}
