package no.nav.syfo.dinesykmeldte

fun interface IDinesykmeldteService {
    suspend fun getIsActiveSykmelding(personIdent: String, orgnummer: String): Boolean
}
