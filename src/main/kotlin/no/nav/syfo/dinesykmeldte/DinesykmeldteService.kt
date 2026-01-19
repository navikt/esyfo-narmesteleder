package no.nav.syfo.dinesykmeldte

import no.nav.syfo.dinesykmeldte.client.IDinesykmeldteClient

class DinesykmeldteService(private val dinesykmeldteClient: IDinesykmeldteClient) {
    suspend fun getIsActiveSykmelding(
        perontIdent: String,
        orgnummer: String
    ): Boolean = dinesykmeldteClient.getIsActiveSykmelding(
        fnr = perontIdent,
        orgnummer = orgnummer
    )
}
