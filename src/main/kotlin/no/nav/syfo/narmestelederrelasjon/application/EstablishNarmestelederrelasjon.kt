package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface EstablishNarmestelederrelasjon {
    suspend fun establish(command: EstablishNarmestelederrelasjonCommand)
}

data class EstablishNarmestelederrelasjonCommand(
    val employee: RelationPerson,
    val manager: RelationManager,
    val organizationNumber: OrganizationNumber,
    val source: RelationSource,
)

enum class RelationSource {
    LPS,
    PERSONNEL_MANAGER,
}

data class RelationPerson(
    val personIdent: PersonIdent,
    val firstName: String,
    val middleName: String?,
    val lastName: String,
)

data class RelationManager(
    val personIdent: PersonIdent,
    val firstName: String,
    val middleName: String?,
    val lastName: String,
    val email: String,
    val mobile: String,
)
