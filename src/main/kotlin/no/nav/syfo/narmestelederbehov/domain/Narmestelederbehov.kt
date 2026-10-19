package no.nav.syfo.narmestelederbehov.domain

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.util.UUID

@JvmInline
value class NarmestelederbehovId(val value: UUID)

data class Narmestelederbehov(
    val id: NarmestelederbehovId,
    val employee: Employee,
)

data class Employee(
    val personIdent: PersonalIdentificationNumber,
    val organizationNumber: OrganizationNumber,
)
