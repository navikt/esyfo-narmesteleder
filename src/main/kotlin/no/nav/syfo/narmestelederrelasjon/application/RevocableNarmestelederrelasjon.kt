package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.util.UUID

data class RevocableNarmestelederrelasjon(
    val id: UUID,
    val employeeIdent: PersonIdent,
    val managerIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val isActive: Boolean,
)
