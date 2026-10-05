package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant

data class NarmestelederbehovRead(
    val id: NarmestelederbehovId,
    val employeeIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val mainOrganizationNumber: String,
    val managerIdent: PersonIdent?,
    val firstName: String?,
    val middleName: String?,
    val lastName: String?,
    val created: Instant,
    val updated: Instant,
    val status: BehovStatus,
    val reason: BehovReason,
)
