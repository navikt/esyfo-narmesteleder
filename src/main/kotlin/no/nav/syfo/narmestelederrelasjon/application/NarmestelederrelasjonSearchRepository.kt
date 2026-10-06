package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

fun interface NarmestelederrelasjonSearchRepository {
    suspend fun search(query: NarmestelederrelasjonSearchQuery): List<NarmestelederrelasjonSearchRow>
}

data class NarmestelederrelasjonSearchQuery(
    val orgNumber: OrganizationNumber,
    val managerNationalIdentificationNumber: PersonIdent? = null,
    val employeeNationalIdentificationNumber: PersonIdent? = null,
    val nationalIdentificationNumber: PersonIdent? = null,
    val text: String? = null,
    val hasActiveSickLeave: Boolean? = null,
    val pageSize: Int,
    val cursor: LinemanagerSearchCursor? = null,
)

data class NarmestelederrelasjonSearchRow(
    val cursor: LinemanagerSearchCursor,
    val linemanager: SearchNarmestelederrelasjon,
)

data class SearchNarmestelederrelasjon(
    val id: UUID,
    val orgNumber: OrganizationNumber,
    val activeFrom: Instant,
    val employee: SearchPerson,
    val manager: SearchManager,
)

data class SearchPerson(val nationalIdentificationNumber: PersonIdent, val name: SearchName?)

data class SearchManager(
    val nationalIdentificationNumber: PersonIdent,
    val name: SearchName?,
    val email: String,
    val mobile: String,
)

data class SearchName(val firstName: String, val lastName: String, val middleName: String?)
