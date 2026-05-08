package no.nav.syfo.dinesykmeldte

interface IDinesykmeldteService {
    suspend fun getIsActiveSykmelding(perontIdent: String, orgnummer: String): Boolean
}
