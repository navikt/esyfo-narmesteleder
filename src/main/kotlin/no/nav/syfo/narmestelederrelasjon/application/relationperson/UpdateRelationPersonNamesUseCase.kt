package no.nav.syfo.narmestelederrelasjon.application.relationperson

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.relationperson.BulkPersonLookupResult.Found
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RelationPersonNameUpdateResult.Completed
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RelationPersonNameUpdateResult.LookupIncomplete
import org.slf4j.LoggerFactory

/**
 * Updates the registered details of persons in the `RelationPerson` projection after a name change in PDL.
 * Persons that are not in the projection are ignored.
 *
 * Returns [LookupIncomplete] without updating anyone when some persons could not be looked up, so the caller can
 * retry the batch.
 */
class UpdateRelationPersonNamesUseCase(
    private val repository: RelationPersonRepository,
    private val personLookup: BulkPersonLookup,
    private val metrics: RelationPersonNameUpdateMetrics,
) {
    suspend fun execute(personidenter: List<String>): RelationPersonNameUpdateResult {
        val relevantIdents = relevantIdents(personidenter)
        if (relevantIdents.isEmpty()) {
            logger.info("Skipping NAVN_V1 batch without relevant personidenter")
            return Completed()
        }

        val existingIdents = repository.findExisting(relevantIdents)
        if (existingIdents.isEmpty()) {
            return completeWithoutExistingPersons(relevantIdents)
        }
        return updateExistingPersons(relevantIdents, existingIdents)
    }

    private fun relevantIdents(personidenter: List<String>): List<PersonalIdentificationNumber> {
        return personidenter.filter(PersonalIdentificationNumber::isValid).distinct().map(::PersonalIdentificationNumber)
    }

    private fun completeWithoutExistingPersons(relevantIdents: List<PersonalIdentificationNumber>): Completed {
        logger.info(
            "Processed NAVN_V1 batch without existing persons. relevantFnrCount={}, notFoundInRegisterCount={}",
            relevantIdents.size,
            relevantIdents.size,
        )
        return Completed(notFoundInRegisterCount = relevantIdents.size)
    }

    private suspend fun updateExistingPersons(
        relevantIdents: List<PersonalIdentificationNumber>,
        existingIdents: List<PersonalIdentificationNumber>,
    ): RelationPersonNameUpdateResult {
        val lookupResults = personLookup.findAll(existingIdents)
        val missingCount = existingIdents.count { it !in lookupResults }
        if (missingCount > 0) {
            metrics.recordLookupFailed(missingCount)
            return LookupIncomplete(requestedCount = existingIdents.size, missingCount = missingCount)
        }

        val foundPersons = foundPersons(existingIdents, lookupResults)
        val updatedIdents = if (foundPersons.isEmpty()) emptySet() else repository.updateRegisteredDetails(foundPersons)
        return completedResult(relevantIdents, existingIdents, foundPersons, updatedIdents).also { result ->
            logger.info(
                "Processed NAVN_V1 batch. relevantFnrCount={}, existingFnrCount={}, updatedCount={}, notFoundInRegisterCount={}, pdlNotFoundCount={}",
                relevantIdents.size,
                existingIdents.size,
                result.updatedCount,
                result.notFoundInRegisterCount,
                result.pdlNotFoundCount,
            )
        }
    }

    private fun foundPersons(
        existingIdents: List<PersonalIdentificationNumber>,
        lookupResults: Map<PersonalIdentificationNumber, BulkPersonLookupResult>,
    ) = existingIdents.mapNotNull { ident ->
        (lookupResults.getValue(ident) as? Found)?.let { ident to it.person }
    }.toMap()

    private fun completedResult(
        relevantIdents: List<PersonalIdentificationNumber>,
        existingIdents: List<PersonalIdentificationNumber>,
        foundPersons: Map<PersonalIdentificationNumber, RegisteredPerson>,
        updatedIdents: Set<PersonalIdentificationNumber>,
    ): Completed {
        // Each relevant person is either updated, not found in PDL, or not (or no longer) in the projection.
        val pdlNotFoundCount = existingIdents.size - foundPersons.size
        return Completed(
            updatedCount = updatedIdents.size,
            notFoundInRegisterCount = relevantIdents.size - pdlNotFoundCount - updatedIdents.size,
            pdlNotFoundCount = pdlNotFoundCount,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UpdateRelationPersonNamesUseCase::class.java)
    }
}

sealed interface RelationPersonNameUpdateResult {
    data class Completed(
        val updatedCount: Int = 0,
        val notFoundInRegisterCount: Int = 0,
        val pdlNotFoundCount: Int = 0,
    ) : RelationPersonNameUpdateResult

    /** Some persons were missing from the lookup result, so nothing was updated. */
    data class LookupIncomplete(
        val requestedCount: Int,
        val missingCount: Int,
    ) : RelationPersonNameUpdateResult
}
