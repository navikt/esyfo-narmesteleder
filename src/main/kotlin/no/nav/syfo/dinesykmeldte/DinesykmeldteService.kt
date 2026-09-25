package no.nav.syfo.dinesykmeldte

fun interface DinesykmeldteService {
    suspend fun getIsActiveSykmelding(personIdent: String, orgnummer: String): Boolean
}
