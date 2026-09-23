package no.nav.syfo.organisasjonstilgang.api

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

fun Principal.toOrganizationAccessSubject(): OrganizationAccessSubject = when (this) {
    is UserPrincipal -> OrganizationAccessSubject.PersonnelManager(
        personIdent = PersonIdent(ident),
        accessToken = AccessToken(token),
    )
    is SystemPrincipal -> OrganizationAccessSubject.LpsSystemUser(
        systemUserId = systemUserId,
        systemUserOrganizationNumber = OrganizationNumber(getSystemUserOrgNumber()),
    )
}
