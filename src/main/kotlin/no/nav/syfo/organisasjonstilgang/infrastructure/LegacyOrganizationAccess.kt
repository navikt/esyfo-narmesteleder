package no.nav.syfo.organisasjonstilgang.infrastructure

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class LegacyOrganizationAccess(private val validator: PrincipalAccessValidator) : OrganizationAccess {
    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        val principal = when (subject) {
            is OrganizationAccessSubject.PersonnelManager ->
                UserPrincipal(subject.personIdent.value, subject.accessToken.value())
            is OrganizationAccessSubject.LpsSystemUser ->
                SystemPrincipal("0192:${subject.systemUserOrganizationNumber.value}", "", "", subject.systemUserId)
        }
        try {
            validator.validatePrincipalAccessToOrgnumber(principal, organizationNumber.value)
            return OrganizationAccessResult.Granted
        } catch (e: ApiErrorException.ForbiddenException) {
            val reason = when {
                subject is OrganizationAccessSubject.LpsSystemUser &&
                    e.type == ErrorType.MISSING_ALITINN_RESOURCE_ACCESS -> DenialReason.SYSTEM_USER_REJECTED
                e.type == ErrorType.MISSING_ORG_ACCESS -> DenialReason.MISSING_ORGANIZATION_ACCESS
                e.type == ErrorType.MISSING_ALITINN_RESOURCE_ACCESS -> DenialReason.MISSING_RESOURCE_ACCESS
                else -> throw e
            }
            return OrganizationAccessResult.Denied(reason)
        }
    }
}
