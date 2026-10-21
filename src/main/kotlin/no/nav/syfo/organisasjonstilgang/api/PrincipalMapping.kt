package no.nav.syfo.organisasjonstilgang.api

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.auth.Principal
import no.nav.syfo.platform.auth.SystemPrincipal
import no.nav.syfo.platform.auth.UserPrincipal

fun Principal.toOrganizationAccessSubject(): OrganizationAccessSubject = when (this) {
    is UserPrincipal -> toPersonnelManager()
    is SystemPrincipal -> OrganizationAccessSubject.LpsSystemUser(
        systemUserId = systemUserId,
        systemUserOrganizationNumber = OrganizationNumber(getSystemUserOrgNumber()),
    )
}

fun UserPrincipal.toPersonnelManager(): OrganizationAccessSubject.PersonnelManager = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonalIdentificationNumber(ident),
    accessToken = AccessToken(token),
)
