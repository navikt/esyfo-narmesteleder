package no.nav.syfo.pdl

import java.lang.IllegalStateException
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident.Companion.FNR_GRUPPE
import no.nav.syfo.util.logger

class PdlService(private val pdlClient: IPdlClient) {

    private val logger = logger()

    suspend fun getPersonFor(fnr: String): Person {
        val response = try {
            pdlClient.getPerson(fnr)
        } catch (e: Exception) {
            val message = "Could not fetch person from PDL"
            logger.error(message, e)
            throw IllegalStateException(message, e)
        }
        with(response.data) {
            val navn = response.data?.person?.navn?.firstOrNull()
            val fnr = response.data?.identer?.identer?.firstOrNull() { it.gruppe == FNR_GRUPPE }?.ident
            check(fnr != null) { "Fant ikke fnr" }
            check(navn != null) { "Fant ikke navn" }
            return Person(
                navn = navn, fnr = fnr
            )
        }
    }
}
