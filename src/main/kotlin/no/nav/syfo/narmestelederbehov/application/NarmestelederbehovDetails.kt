package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant

data class NarmestelederbehovDetails(
    val id: NarmestelederbehovId,
    val employeeIdent: PersonalIdentificationNumber,
    val organizationNumber: OrganizationNumber,
    val mainOrganizationNumber: String,
    val managerIdent: PersonalIdentificationNumber?,
    val firstName: String?,
    val middleName: String?,
    val lastName: String?,
    val created: Instant,
    val updated: Instant,
    val status: BehovStatus,
    val reason: BehovReason,
)
