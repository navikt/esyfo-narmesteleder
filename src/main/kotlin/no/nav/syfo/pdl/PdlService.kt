package no.nav.syfo.pdl

import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.util.logger

class PdlService(private val pdlClient: IPdlClient) {

    private val logger = logger()

    suspend fun getPersonFor(fnr: String): Person? {
        val response = try {
            pdlClient.getPerson(fnr)
        } catch (e: Exception) {
            logger.error("Could not fetch person from PDL", e)
            return null
        }
        with(response.data) {
            val navn = response.data.person?.navn?.firstOrNull()
            val fnr = response.data.identer?.identer?.firstOrNull()?.ident
            require(fnr != null) { "Fant ikke fnr" }
            require(navn != null) { "Fant ikke navn" }
            return Person(
                navn = navn, fnr = fnr
            )
        }
    }
}
