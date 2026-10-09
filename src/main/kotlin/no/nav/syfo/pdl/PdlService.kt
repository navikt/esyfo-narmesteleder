package no.nav.syfo.pdl

import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.platform.upstream.getOrThrow

class PdlService(
    private val pdlClient: PdlClient,
) {
    suspend fun getPersonFor(fnr: String): Person? {
        val data = pdlClient.getPerson(fnr).getOrThrow() ?: return null

        with(data) {
            val navn = person.navn?.firstOrNull() ?: return null
            val fnr = identer.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }?.ident ?: return null
            val foedselsdato = person.foedselsdato?.firstOrNull()

            return Person(
                name = navn,
                nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
                dateOfBirth = foedselsdato,
            )
        }
    }
}
