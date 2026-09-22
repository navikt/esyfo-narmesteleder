package no.nav.syfo.narmestelederrelasjon.application

interface NarmestelederrelasjonOrganization {
    suspend fun findName(orgNumber: String): String?
}
