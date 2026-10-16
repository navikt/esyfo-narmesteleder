package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.narmestelederrelasjon.application.EnrichPendingRelationPersonsUseCase
import kotlin.time.Duration

class PersonEnrichmentTask(
    private val enrichPendingPersons: EnrichPendingRelationPersonsUseCase,
    pollingInterval: Duration,
) : ScheduledLeaderTask(
    name = PersonEnrichmentTask::class.java.name,
    interval = pollingInterval,
) {
    override suspend fun execute() {
        enrichPendingPersons.execute()
    }
}
