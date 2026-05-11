package no.nav.syfo.dinesykmeldte

interface IDinesykmeldteService {
    suspend fun getIsActiveSykmelding(personIdent: String, orgnummer: String): Boolean
}
