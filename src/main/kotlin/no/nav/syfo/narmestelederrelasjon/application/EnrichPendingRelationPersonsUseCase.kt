package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult.Found
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookupResult.NotFound
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val personEnrichmentStalled = applicationEvent<Unit>(
    name = "person_enrichment_stalled",
    level = Level.WARN,
    message = "Person enrichment stopped because the batch made no progress",
)

/**
 * Enriches pending persons in the `RelationPerson` projection from the person register, one batch at a time,
 * until no pending persons remain or a batch makes no progress.
 */
class EnrichPendingRelationPersonsUseCase(
    private val repository: RelationPersonRepository,
    private val personLookup: BulkPersonLookup,
) {
    suspend fun execute() {
        do {
            val batch = repository.findPending(BATCH_SIZE)
            val madeProgress = batch.isNotEmpty() && enrich(batch)
        } while (madeProgress && batch.size >= BATCH_SIZE)
    }

    /** Enriches one batch and returns whether any person in it was updated. */
    private suspend fun enrich(pending: List<PersonIdent>): Boolean {
        logger.info("Enriching ${pending.size} pending persons from PDL")

        val results = personLookup.findAll(pending)
        val enriched = pending.mapNotNull { ident -> (results[ident] as? Found)?.let { ident to it.person } }.toMap()
        val notFound = pending.filter { results[it] is NotFound }
        repository.saveEnrichment(enriched, notFound)

        val updatedCount = enriched.size + notFound.size
        val unchangedCount = pending.size - updatedCount
        logger.info("Enrichment done: ${enriched.size} enriched, ${notFound.size} not found, $unchangedCount unchanged")

        if (updatedCount == 0) logger.logEvent(personEnrichmentStalled, Unit)
        return updatedCount > 0
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EnrichPendingRelationPersonsUseCase::class.java)
        internal const val BATCH_SIZE = 500
    }
}
