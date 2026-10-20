package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.logging.applicationLogger
import java.time.Clock

class SendPendingNarmestelederbehovDialogsUseCase(
    private val pendingRepository: PendingNarmestelederbehovRepository,
    private val repository: NarmestelederbehovRepository,
    private val personLookup: EmployeePersonLookup,
    private val dialog: NarmestelederbehovDialogCreation,
    private val clock: Clock,
) {
    private val dialogSender = NarmestelederbehovDialogSender(repository, personLookup, dialog, logger)

    suspend fun execute() {
        var batchNum = 0
        do {
            val batch = pendingRepository.findPendingDialogs(clock.instant().minusSeconds(10), BATCH_SIZE)
            logger.info("Batch: ${++batchNum}: Found ${batch.size} behov to send to dialogporten. First created at ${batch.firstOrNull()?.created ?: "N/A"}")
            for (behov in batch) {
                dialogSender.send(behov.id, behov.employee, behov.dialogId)
            }
            // Preserve legacy refetching, including failed behov, until #711 is addressed.
        } while (batch.size >= BATCH_SIZE)
    }

    private companion object {
        const val BATCH_SIZE = 100
        val logger = applicationLogger(SendPendingNarmestelederbehovDialogsUseCase::class.java)
    }
}
