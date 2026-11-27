package no.nav.syfo.organisasjonstilgang.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class McpDevOrganizationAccess(private val delegate: OrganizationAccess) : OrganizationAccess {
    override suspend fun evaluate(subject: OrganizationAccessSubject, organizationNumber: OrganizationNumber): OrganizationAccessResult = when (subject) {
        is OrganizationAccessSubject.McpDevTool -> OrganizationAccessResult.Granted(organizationName = null)
        is OrganizationAccessSubject.LpsSystemUser,
        is OrganizationAccessSubject.PersonnelManager,
        -> delegate.evaluate(subject, organizationNumber)
    }
}
