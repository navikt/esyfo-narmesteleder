package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonResult.NotFound
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonResult.Reason
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.util.UUID

class RevokeNarmestelederrelasjon(
    private val repository: NarmestelederrelasjonRepository,
    private val organizationAccess: OrganizationAccess,
    private val publisher: PublishNarmestelederrelasjonRevocation,
) {
    suspend fun execute(
        id: UUID,
        accessSubject: OrganizationAccessSubject,
    ): RevokeNarmestelederrelasjonResult {
        val relation = repository.findRevocableById(id)
            ?: return NotFound(Reason.RELATION_NOT_FOUND)

        val initiator = accessSubject.partyInRelation(relation)
            ?: when (val access = organizationAccess.evaluate(accessSubject, relation.organizationNumber)) {
                OrganizationAccessResult.Granted -> accessSubject.employerInitiator()
                is OrganizationAccessResult.Denied -> return accessDenied(id, accessSubject, access.reason)
            }

        if (!relation.isActive) {
            return RevokeNarmestelederrelasjonResult.AlreadyRevoked
        }

        publisher.publish(
            PublishNarmestelederrelasjonRevocationCommand(
                employeeIdent = relation.employeeIdent,
                organizationNumber = relation.organizationNumber,
                initiator = initiator,
            ),
        )
        return RevokeNarmestelederrelasjonResult.Revoked(initiator)
    }

    private fun accessDenied(
        id: UUID,
        accessSubject: OrganizationAccessSubject,
        reason: DenialReason,
    ): NotFound {
        if (accessSubject is OrganizationAccessSubject.PersonnelManager) {
            reason.rejectionReason()?.let { rejectionReason ->
                logger.event(
                    revokeAccessRejected,
                    RevokeAccessRejectedDetails(
                        rejectionReason = rejectionReason,
                        narmestelederId = id,
                        principalType = USER_PRINCIPAL_TYPE,
                    ),
                )
            }
        }
        return NotFound(Reason.ACCESS_DENIED, reason)
    }

    private companion object {
        val logger = applicationLogger(RevokeNarmestelederrelasjon::class.java)

        // Legacy principal_type value. System user rejections are logged by OrganizationAccess.
        const val USER_PRINCIPAL_TYPE = "UserPrincipal"
    }
}

private fun OrganizationAccessSubject.partyInRelation(relation: RevocableNarmestelederrelasjon): RevocationInitiator? = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> null
    is OrganizationAccessSubject.PersonnelManager -> when (personIdent) {
        relation.employeeIdent -> RevocationInitiator.EMPLOYEE
        relation.managerIdent -> RevocationInitiator.LINEMANAGER
        else -> null
    }
}

private fun OrganizationAccessSubject.employerInitiator(): RevocationInitiator = when (this) {
    is OrganizationAccessSubject.PersonnelManager -> RevocationInitiator.PERSONNEL_MANAGER
    is OrganizationAccessSubject.LpsSystemUser -> RevocationInitiator.LPS
}

/** Keeps the legacy rejection_reason values. */
private fun DenialReason.rejectionReason(): String? = when (this) {
    DenialReason.MISSING_ORGANIZATION_ACCESS -> "MISSING_ORG_ACCESS"
    DenialReason.MISSING_RESOURCE_ACCESS -> "MISSING_ALITINN_RESOURCE_ACCESS"
    DenialReason.SYSTEM_USER_REJECTED -> null
}
