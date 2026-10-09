package no.nav.syfo.pdl

import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PdlResourceNotFoundException
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber

class PdlService(
    private val pdlClient: PdlClient,
) {
    suspend fun getPersonFor(fnr: String): Person {
        val response = pdlClient.getPerson(fnr)
        val data = response.data ?: throw PdlRequestException("Unexpected response from upstream service")

        with(data) {
            val navn = person?.navn?.firstOrNull()
                ?: throw PdlResourceNotFoundException("Could not find name for person")
            val fnr = identer?.identer?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }?.ident
                ?: throw PdlResourceNotFoundException("Could not find national identification number for person")
            val foedselsdato = person.foedselsdato?.firstOrNull()

            return Person(
                name = navn,
                nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
                dateOfBirth = foedselsdato,
            )
        }
    }
}
