package no.nav.syfo.pdl

import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.exception.PdlPersonMissingPropertiesException

class PdlService(private val pdlClient: IPdlClient) {

    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        with(response.data) {
            val navn = response.data?.person?.navn?.firstOrNull()
                ?: throw PdlPersonMissingPropertiesException("Fant ikke navn")
            val fnr = response.data.identer?.identer?.firstOrNull() { it.gruppe == GRUPPE_IDENT_FNR }?.ident
                ?: throw PdlPersonMissingPropertiesException("Fant ikke fnr")
            return Person(
                navn = navn, fnr = fnr
            )
        }
    }
}
