package no.nav.syfo.person.task

import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.person.service.PersonEnrichmentService
import kotlin.time.Duration

class PersonEnrichmentTask(
    private val personEnrichmentService: PersonEnrichmentService,
    pollingInterval: Duration,
) : ScheduledLeaderTask(
    name = PersonEnrichmentTask::class.qualifiedName!!,
    interval = pollingInterval,
) {
    override suspend fun execute() {
        personEnrichmentService.enrichPendingPersons()
    }
}
