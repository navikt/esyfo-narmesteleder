package no.nav.syfo.organisasjonstilgang.infrastructure

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AuthorizationDecision
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.System
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.COUNT_HAS_ALTINN3_RESOURCE
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse

class AltinnOrganizationAccess(
    private val altinnTilgangerService: AltinnTilgangerService,
    private val client: AltinnAuthorizationClient,
    private val eregClient: EregClient,
) : OrganizationAccess {
    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult = when (subject) {
        is OrganizationAccessSubject.PersonnelManager -> evaluatePersonnelManager(subject, organizationNumber)
        is OrganizationAccessSubject.LpsSystemUser -> evaluateSystemUser(subject, organizationNumber)
    }

    companion object {
        private val logger = applicationLogger(AltinnOrganizationAccess::class.java)
    }

    private suspend fun evaluatePersonnelManager(
        subject: OrganizationAccessSubject.PersonnelManager,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        val altinnTilgang = altinnTilgangerService.getAltinnTilgangForOrgnr(
            userPrincipal = UserPrincipal(subject.personIdent.value, subject.accessToken.value()),
            orgnummer = organizationNumber.value,
        ).getOrElse { return OrganizationAccessResult.Unavailable(it) }
            ?: return OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)

        if (OPPGI_NARMESTELEDER_RESOURCE !in altinnTilgang.altinn3Tilganger) {
            return OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
        }
        COUNT_HAS_ALTINN3_RESOURCE.increment()
        return OrganizationAccessResult.Granted(organizationName = altinnTilgang.navn.trim())
    }

    private suspend fun evaluateSystemUser(
        subject: OrganizationAccessSubject.LpsSystemUser,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        val directDecision = decisionFor(subject, organizationNumber).getOrElse { return OrganizationAccessResult.Unavailable(it) }
        if (directDecision == AuthorizationDecision.Permit) {
            return OrganizationAccessResult.Granted(organizationName = null)
        }

        val organization = eregClient.getOrganisasjon(organizationNumber.value).getOrElse { return OrganizationAccessResult.Unavailable(it) }
            ?: throw ApiErrorException.BadRequestException(
                "Unable to look up the organization",
                type = ErrorType.ORGANIZATION_NOT_FOUND,
            )
        val hierarchy = organization.aggregerOrgnummereFraHierarki()
        val fallbackDecision = if (subject.systemUserOrganizationNumber.value in hierarchy) {
            decisionFor(subject, subject.systemUserOrganizationNumber).getOrElse { return OrganizationAccessResult.Unavailable(it) }
        } else {
            null
        }
        if (fallbackDecision == AuthorizationDecision.Permit) {
            return OrganizationAccessResult.Granted(organizationName = null)
        }

        logger.event(systemUserAccessRejected, SystemUserAccessRejection(directDecision, fallbackDecision))
        return OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
    }

    private suspend fun decisionFor(
        subject: OrganizationAccessSubject.LpsSystemUser,
        organizationNumber: OrganizationNumber,
    ): UpstreamResult<AuthorizationDecision> = client.authorize(
        user = System(subject.systemUserId),
        orgNumberSet = setOf(organizationNumber.value),
        resource = OPPGI_NARMESTELEDER_RESOURCE,
    )
}
