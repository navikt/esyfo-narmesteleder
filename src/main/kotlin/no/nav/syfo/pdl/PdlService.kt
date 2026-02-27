package no.nav.syfo.pdl

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException

class PdlService(
    private val pdlClient: IPdlClient,
    private val pdlCache: PdlCache
) {

    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        with(response.data) {
            val navn = response.data?.person?.navn?.firstOrNull()
                ?: throw PdlResourceNotFoundException("Could not find name for person")
            val fnr = response.data.identer?.identer?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }?.ident
                ?: throw PdlResourceNotFoundException("Could not find national identification number for person")
            return Person(
                name = navn,
                nationalIdentificationNumber = fnr
            )
        }
    }

    suspend fun getPersonOrThrowApiError(fnr: String): Person {
        pdlCache.getPerson(fnr).let { cachedPerson ->
            if (cachedPerson != null) {
                return cachedPerson
            }
        }
        return try {
            val person: Person = getPersonFor(fnr)
            pdlCache.putPerson(fnr, person)
            person
        } catch (e: PdlResourceNotFoundException) {
            throw ApiErrorException.BadRequestException("Could not find person in PDL", e)
        } catch (e: PdlRequestException) {
            throw ApiErrorException.InternalServerErrorException("Error when fetching person from PDL", e)
        }
    }
}
