package no.nav.syfo.narmestelederrelasjon.application.relationperson

import no.nav.esyfo.observability.Event
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.relationperson.BulkPersonLookupResult.Found
import no.nav.syfo.narmestelederrelasjon.application.relationperson.BulkPersonLookupResult.NotFound
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import org.slf4j.event.Level

private const val OPERATION = "enrich_relation_persons"

private data class EnrichmentBatchOutcome(
    val pendingCount: Int,
    val enrichedCount: Int,
    val notFoundCount: Int,
) {
    val unchangedCount: Int get() = pendingCount - enrichedCount - notFoundCount
}

private val personEnrichmentBatchCompleted = Event<EnrichmentBatchOutcome>(
    name = "person_enrichment_batch_completed",
    level = Level.INFO,
    message = "Person enrichment batch completed",
    operation = OPERATION,
    fields = mapOf(
        "pending_count" to { it.pendingCount },
        "enriched_count" to { it.enrichedCount },
        "not_found_count" to { it.notFoundCount },
        "unchanged_count" to { it.unchangedCount },
    ),
)

private val personEnrichmentStalled = applicationEvent<Int>(
    name = "person_enrichment_stalled",
    level = Level.WARN,
    message = "Person enrichment stopped because the batch made no progress",
    operation = OPERATION,
    fields = mapOf("pending_count" to { it }),
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
    private suspend fun enrich(pending: List<PersonalIdentificationNumber>): Boolean {
        val results = personLookup.findAll(pending)
        val enriched = pending.mapNotNull { ident -> (results[ident] as? Found)?.let { ident to it.person } }.toMap()
        val notFound = pending.filter { results[it] is NotFound }
        repository.saveEnrichment(enriched, notFound)

        logger.event(personEnrichmentBatchCompleted, EnrichmentBatchOutcome(pending.size, enriched.size, notFound.size))

        val madeProgress = enriched.isNotEmpty() || notFound.isNotEmpty()
        if (!madeProgress) logger.logEvent(personEnrichmentStalled, pending.size)
        return madeProgress
    }

    companion object {
        private val logger = applicationLogger(EnrichPendingRelationPersonsUseCase::class.java)
        internal const val BATCH_SIZE = 500
    }
}
