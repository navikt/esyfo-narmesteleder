package no.nav.syfo.pdl

import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.exception.PdlPersonMissingPropertiesException
import no.nav.syfo.util.logger

class PdlService(private val pdlClient: IPdlClient) {

    private val logger = logger()

    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        with(response.data) {
            val navn = response.data?.person?.navn?.firstOrNull()
            val fnr = response.data?.identer?.identer?.firstOrNull() { it.gruppe == GRUPPE_IDENT_FNR }?.ident
            if (fnr == null) throw PdlPersonMissingPropertiesException("Fant ikke fnr")
            if (navn == null) throw PdlPersonMissingPropertiesException("Fant ikke navn")
            return Person(
                navn = navn, fnr = fnr
            )
        }
    }
}
