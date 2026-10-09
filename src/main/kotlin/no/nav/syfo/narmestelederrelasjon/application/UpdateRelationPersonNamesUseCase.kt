package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent
import org.slf4j.LoggerFactory

/**
 * Updates the registered details of persons in the `RelationPerson` projection after a name change in PDL.
 * Persons that are not in the projection are ignored.
 *
 * Throws [IncompleteBulkPersonLookupException] when some persons could not be looked up, so the batch can be retried.
 */
class UpdateRelationPersonNamesUseCase(
    private val repository: RelationPersonRepository,
    private val personLookup: BulkPersonLookup,
    private val metrics: RelationPersonNameUpdateMetrics,
) {
    suspend fun execute(personidenter: List<String>): RelationPersonNameUpdateResult {
        val relevantIdents = personidenter
            .filter(PersonIdent::isValid)
            .distinct()
            .map(::PersonIdent)

        if (relevantIdents.isEmpty()) {
            logger.info("Skipping NAVN_V1 batch without relevant personidenter")
            return RelationPersonNameUpdateResult()
        }

        val existingIdents = repository.findExisting(relevantIdents)
        val notFoundInRegisterCount = relevantIdents.size - existingIdents.size

        if (existingIdents.isEmpty()) {
            logger.info(
                "Processed NAVN_V1 batch without existing persons. relevantFnrCount={}, notFoundInRegisterCount={}",
                relevantIdents.size,
                notFoundInRegisterCount,
            )
            return RelationPersonNameUpdateResult(notFoundInRegisterCount = notFoundInRegisterCount)
        }

        val lookedUp = personLookup.findAll(existingIdents)
        val missingCount = existingIdents.count { !lookedUp.containsKey(it) }
        if (missingCount > 0) {
            metrics.recordLookupFailed(missingCount)
            throw IncompleteBulkPersonLookupException(existingIdents.size, missingCount)
        }

        val personsToUpdate = existingIdents
            .mapNotNull { personIdent ->
                (lookedUp.getValue(personIdent) as? BulkPersonLookupResult.Found)?.let { personIdent to it.person }
            }
            .toMap()
        val pdlNotFoundCount = existingIdents.size - personsToUpdate.size

        val updatedCount = if (personsToUpdate.isEmpty()) 0 else repository.updateRegisteredDetails(personsToUpdate).size
        val skippedAfterLookupCount = personsToUpdate.size - updatedCount
        val totalNotFoundInRegisterCount = notFoundInRegisterCount + skippedAfterLookupCount

        logger.info(
            "Processed NAVN_V1 batch. relevantFnrCount={}, existingFnrCount={}, updatedCount={}, notFoundInRegisterCount={}, pdlNotFoundCount={}",
            relevantIdents.size,
            existingIdents.size,
            updatedCount,
            totalNotFoundInRegisterCount,
            pdlNotFoundCount,
        )

        return RelationPersonNameUpdateResult(
            updatedCount = updatedCount,
            notFoundInRegisterCount = totalNotFoundInRegisterCount,
            pdlNotFoundCount = pdlNotFoundCount,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UpdateRelationPersonNamesUseCase::class.java)
    }
}

data class RelationPersonNameUpdateResult(
    val updatedCount: Int = 0,
    val notFoundInRegisterCount: Int = 0,
    val pdlNotFoundCount: Int = 0,
)
