package no.nav.syfo.pdl

import io.ktor.server.plugins.BadRequestException
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException

class PdlService(private val pdlClient: IPdlClient) {

    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        with(response.data) {
            val navn = response.data?.person?.navn?.firstOrNull()
                ?: throw PdlResourceNotFoundException("Fant ikke navn")
            val fnr = response.data.identer?.identer?.firstOrNull() { it.gruppe == GRUPPE_IDENT_FNR }?.ident
                ?: throw PdlResourceNotFoundException("Fant ikke fnr")
            return Person(
                navn = navn, fnr = fnr
            )
        }
    }

    suspend fun getPersonOrThrowApiError(fnr: String): Person {
        return try {
            getPersonFor(fnr)
        } catch (e: PdlResourceNotFoundException) {
            throw BadRequestException("Fant ikke person i PDL", e)
        } catch (e: PdlRequestException) {
            throw ApiErrorException.InternalServerErrorException("Kunne ikke hente person fra PDL", e)
        }
    }

}
