package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.util.UUID

data class NarmestelederrelasjonLookup(
    val id: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonIdent,
    val employeeFirstName: String?,
    val employeeMiddleName: String?,
    val employeeLastName: String?,
)
