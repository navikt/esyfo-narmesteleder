package no.nav.syfo.pdl.leesah

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.util.logger
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PdlLeesahNameUpdateService(
    private val database: Database,
    private val pdlService: PdlService,
) {
    suspend fun processNameChanges(personidenter: List<String>): PdlLeesahNameUpdateResult {
        val relevantFnrs = personidenter
            .filter(::isRelevantFnr)
            .distinct()

        if (relevantFnrs.isEmpty()) {
            logger.info("Skipping NAVN_V1 batch without relevant personidenter")
            return PdlLeesahNameUpdateResult()
        }

        val existingFnrs = withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                PersonEntity.find { PersonTable.fnr inList relevantFnrs }
                    .map { it.fnr }
            }
        }

        val notFoundInRegisterCount = relevantFnrs.size - existingFnrs.size

        if (existingFnrs.isEmpty()) {
            logger.info(
                "Processed NAVN_V1 batch without existing persons. relevantFnrCount={}, notFoundInRegisterCount={}",
                relevantFnrs.size,
                notFoundInRegisterCount,
            )
            return PdlLeesahNameUpdateResult(
                updatedCount = 0,
                notFoundInRegisterCount = notFoundInRegisterCount,
                pdlNotFoundCount = 0,
            )
        }

        val pdlPersons = pdlService.getPersonsBolk(existingFnrs)
        val missingFnrsCount = existingFnrs.count { fnr -> !pdlPersons.containsKey(fnr) }
        if (missingFnrsCount > 0) {
            countPdlLeesahPersonUpdate(RESULT_PDL_ERROR, missingFnrsCount)
            logger.error(
                "PDL bulk response was incomplete for NAVN_V1 batch. requestedExistingFnrCount={}, missingResponseCount={}, outcome={}",
                existingFnrs.size,
                missingFnrsCount,
                RESULT_PDL_ERROR,
            )
            throw PdlRequestException("Incomplete response from getPersonsBolk")
        }

        var pdlNotFoundCount = 0
        val personsToUpdate = buildMap {
            existingFnrs.forEach { fnr ->
                val pdlPerson = pdlPersons.getValue(fnr)
                if (pdlPerson == null) {
                    pdlNotFoundCount++
                } else {
                    put(fnr, pdlPerson)
                }
            }
        }

        val updatedFnrs = withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                if (personsToUpdate.isEmpty()) {
                    emptySet()
                } else {
                    val updatedAt = OffsetDateTime.now(ZoneOffset.UTC)
                    PersonEntity.find { PersonTable.fnr inList personsToUpdate.keys.toList() }
                        .mapNotNull { personEntity ->
                            personsToUpdate[personEntity.fnr]?.let { person ->
                                personEntity.fornavn = person.name.fornavn
                                personEntity.mellomnavn = person.name.mellomnavn
                                personEntity.etternavn = person.name.etternavn
                                personEntity.foedselsdato = person.foedselsdato?.foedselsdato
                                personEntity.updated = updatedAt
                                personEntity.fnr
                            }
                        }
                        .toSet()
                }
            }
        }
        val updatedCount = updatedFnrs.size
        val skippedAfterLookupCount = personsToUpdate.size - updatedCount

        val totalNotFoundInRegisterCount = notFoundInRegisterCount + skippedAfterLookupCount

        logger.info(
            "Processed NAVN_V1 batch. relevantFnrCount={}, existingFnrCount={}, updatedCount={}, notFoundInRegisterCount={}, pdlNotFoundCount={}",
            relevantFnrs.size,
            existingFnrs.size,
            updatedCount,
            totalNotFoundInRegisterCount,
            pdlNotFoundCount,
        )

        return PdlLeesahNameUpdateResult(
            updatedCount = updatedCount,
            notFoundInRegisterCount = totalNotFoundInRegisterCount,
            pdlNotFoundCount = pdlNotFoundCount,
        )
    }

    private fun isRelevantFnr(personident: String): Boolean = personident.length == FNR_LENGTH && personident.all(Char::isDigit)

    companion object {
        private val logger = logger("no.nav.syfo.pdl.leesah.PdlLeesahNameUpdateService")
        internal const val RESULT_UPDATED = "updated"
        internal const val RESULT_NOT_FOUND_IN_REGISTER = "not_found_in_register"
        internal const val RESULT_PDL_NOT_FOUND = "pdl_not_found"
        internal const val RESULT_PDL_ERROR = "pdl_error"
        private const val FNR_LENGTH = 11
    }
}

data class PdlLeesahNameUpdateResult(
    val updatedCount: Int = 0,
    val notFoundInRegisterCount: Int = 0,
    val pdlNotFoundCount: Int = 0,
)
