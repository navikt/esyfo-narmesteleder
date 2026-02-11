package no.nav.syfo.pdl

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.COUNT_CACHE_MISS_DINE_SYKMELDTE
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.util.logger

class PdlService(
    private val pdlClient: IPdlClient,
    private val pdlCache: PdlCache
) {

    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        with(response.data) {
            val navn = response.data?.person?.navn?.firstOrNull()
                ?: throw PdlResourceNotFoundException("Fant ikke navn")
            val fnr = response.data.identer?.identer?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }?.ident
                ?: throw PdlResourceNotFoundException("Fant ikke fnr")
            return Person(
                name = navn,
                nationalIdentificationNumber = fnr
            )
        }
    }

    suspend fun getPersonOrThrowApiError(fnr: String): Person {
        pdlCache.getPerson(fnr).let { cachedPerson ->
            // TODO remove logs after test
            logger.info("Fant følgende info i cache {}", cachedPerson)
            if (cachedPerson != null) {
                return cachedPerson
            }
        }
        COUNT_CACHE_MISS_DINE_SYKMELDTE.increment()
        return try {
            val person: Person = getPersonFor(fnr)
            pdlCache.putPerson(fnr, person)
            person
        } catch (e: PdlResourceNotFoundException) {
            throw ApiErrorException.BadRequestException("Fant ikke person i PDL", e)
        } catch (e: PdlRequestException) {
            throw ApiErrorException.InternalServerErrorException("Kunne ikke hente person fra PDL", e)
        }
    }
    companion object {
        private val logger = logger()
    }
}
