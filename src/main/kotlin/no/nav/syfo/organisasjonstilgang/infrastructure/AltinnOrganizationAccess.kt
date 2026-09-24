package no.nav.syfo.organisasjonstilgang.infrastructure

import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.altinntilganger.COUNT_HAS_ALTINN3_RESOURCE
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmesteleder.service.validators.SystemUserAccessRejection
import no.nav.syfo.narmesteleder.service.validators.systemUserAccessRejected
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class AltinnOrganizationAccess(
    private val altinnTilgangerService: AltinnTilgangerService,
    private val pdpService: PdpService,
    private val eregService: EregService,
) : OrganizationAccess {
    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult = when (subject) {
        is OrganizationAccessSubject.PersonnelManager -> evaluatePersonnelManager(subject, organizationNumber)
        is OrganizationAccessSubject.LpsSystemUser -> evaluateSystemUser(subject, organizationNumber)
    }

    private suspend fun evaluatePersonnelManager(
        subject: OrganizationAccessSubject.PersonnelManager,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        val altinnTilgang = altinnTilgangerService.getAltinnTilgangForOrgnr(
            userPrincipal = UserPrincipal(subject.personIdent.value, subject.accessToken.value()),
            orgnummer = organizationNumber.value,
        ) ?: return OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)

        if (OPPGI_NARMESTELEDER_RESOURCE !in altinnTilgang.altinn3Tilganger) {
            return OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
        }
        COUNT_HAS_ALTINN3_RESOURCE.increment()
        return OrganizationAccessResult.Granted
    }

    private suspend fun evaluateSystemUser(
        subject: OrganizationAccessSubject.LpsSystemUser,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        val directDecision = decisionFor(subject, organizationNumber)
        if (directDecision == Decision.Permit) {
            return OrganizationAccessResult.Granted
        }

        val fallbackDecision = decisionThroughSystemUserOrganization(subject, organizationNumber)
        if (fallbackDecision == Decision.Permit) {
            return OrganizationAccessResult.Granted
        }

        logger.event(systemUserAccessRejected, SystemUserAccessRejection(directDecision, fallbackDecision))
        return OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
    }

    private suspend fun decisionThroughSystemUserOrganization(
        subject: OrganizationAccessSubject.LpsSystemUser,
        organizationNumber: OrganizationNumber,
    ): Decision? {
        val hierarchy = eregService.getOrganization(organizationNumber.value).aggregerOrgnummereFraHierarki()
        return if (subject.systemUserOrganizationNumber.value in hierarchy) {
            decisionFor(subject, subject.systemUserOrganizationNumber)
        } else {
            null
        }
    }

    private suspend fun decisionFor(
        subject: OrganizationAccessSubject.LpsSystemUser,
        organizationNumber: OrganizationNumber,
    ): Decision = pdpService.accessDecisionForResource(
        user = System(subject.systemUserId),
        orgNumberSet = setOf(organizationNumber.value),
        resource = OPPGI_NARMESTELEDER_RESOURCE,
    )

    companion object {
        private val logger = applicationLogger(AltinnOrganizationAccess::class.java)
    }
}
