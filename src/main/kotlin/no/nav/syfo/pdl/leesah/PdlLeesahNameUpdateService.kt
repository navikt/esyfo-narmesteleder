package no.nav.syfo.pdl.leesah

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.util.logger
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class PdlLeesahNameUpdateService(
    private val database: Database,
    private val pdlService: PdlService,
) {
    suspend fun processNameChange(personidenter: List<String>): PdlLeesahNameUpdateResult {
        val relevantFnrs = personidenter
            .filter(::isRelevantFnr)
            .distinct()

        if (relevantFnrs.isEmpty()) {
            logger.info("Skipping NAVN_V1 event without relevant personidenter")
            return PdlLeesahNameUpdateResult()
        }

        val existingFnrs = withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                personTable.findExistingFnrs(relevantFnrs)
            }
        }

        val fnrsToUpdate = relevantFnrs.filter(existingFnrs::contains)
        val notFoundInRegisterCount = relevantFnrs.size - fnrsToUpdate.size
        countPdlLeesahPersonUpdate(RESULT_NOT_FOUND_IN_REGISTER, notFoundInRegisterCount)

        var updatedCount = 0
        var pdlNotFoundCount = 0
        var skippedAfterLookupCount = 0

        fnrsToUpdate.forEach { fnr ->
            try {
                val person = pdlService.getPersonFor(fnr)
                val rowsUpdated = withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        personTable.updatePersonFromPdl(fnr, person)
                    }
                }

                if (rowsUpdated == 1) {
                    updatedCount += 1
                } else {
                    skippedAfterLookupCount += 1
                }
            } catch (_: PdlResourceNotFoundException) {
                pdlNotFoundCount += 1
                logger.warn("PDL did not return persondata for existing NAVN_V1 update. outcome={}", RESULT_PDL_NOT_FOUND)
            } catch (exception: PdlRequestException) {
                countPdlLeesahPersonUpdate(RESULT_PDL_ERROR)
                logger.error(
                    "PDL request failed during NAVN_V1 update. outcome={}, exceptionType={}",
                    RESULT_PDL_ERROR,
                    exception::class.simpleName,
                )
                throw exception
            }
        }

        val totalNotFoundInRegisterCount = notFoundInRegisterCount + skippedAfterLookupCount
        countPdlLeesahPersonUpdate(RESULT_UPDATED, updatedCount)
        countPdlLeesahPersonUpdate(RESULT_PDL_NOT_FOUND, pdlNotFoundCount)
        countPdlLeesahPersonUpdate(RESULT_NOT_FOUND_IN_REGISTER, skippedAfterLookupCount)

        logger.info(
            "Processed NAVN_V1 name change. relevantFnrCount={}, existingFnrCount={}, updatedCount={}, notFoundInRegisterCount={}, pdlNotFoundCount={}",
            relevantFnrs.size,
            fnrsToUpdate.size,
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
