package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.db.INarmestelederRevokeDb
import no.nav.syfo.narmesteleder.db.RevokableNarmestelederEntity
import no.nav.syfo.narmesteleder.domain.RevokeInitiator
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.util.logger
import java.util.UUID

sealed interface RevokeOutcome {
    data class Revoked(val initiator: RevokeInitiator) : RevokeOutcome
    data object AlreadyRevoked : RevokeOutcome
}

class LinemanagerRevokeService(
    private val narmestelederRevokeDb: INarmestelederRevokeDb,
    private val narmestelederKafkaService: NarmestelederKafkaService,
    private val validationService: ValidationService,
) {
    private val logger = logger()

    /**
     * Revokes the relation identified by [narmestelederId].
     *
     * The employee and the line manager of the relation may always revoke it. Other callers may
     * revoke it on behalf of the employer if they hold the normal Altinn access to the
     * organization the relation belongs to.
     *
     * To avoid disclosing whether a given narmesteleder id exists, callers without access get the
     * same NotFoundException as callers using an unknown id. The distinction is only visible in
     * the logs.
     */
    suspend fun revoke(
        narmestelederId: UUID,
        principal: Principal,
        context: String,
    ): RevokeOutcome {
        val relation = narmestelederRevokeDb.findByNarmestelederId(narmestelederId)
        if (relation == null) {
            logger.info("Revoke request for unknown linemanager relation. {}", context)
            throw notFound()
        }

        val initiator = relation.partyInRelationOrNull(principal)
            ?: resolveEmployerInitiator(relation, principal, context)

        if (!relation.isActive) {
            logger.info("Revoke request for an already revoked linemanager relation. {}", context)
            return RevokeOutcome.AlreadyRevoked
        }

        narmestelederKafkaService.avbrytNarmesteLederRelation(
            employeeIdentificationNumber = relation.employeeIdentificationNumber,
            orgNumber = relation.orgNumber,
            source = NlResponseSource.getRevokeSourceFrom(initiator),
        )

        return RevokeOutcome.Revoked(initiator)
    }

    private suspend fun resolveEmployerInitiator(
        relation: RevokableNarmestelederEntity,
        principal: Principal,
        context: String,
    ): RevokeInitiator {
        try {
            validationService.validatePrincipalAccessToOrgnumber(principal, relation.orgNumber)
        } catch (e: ApiErrorException.ForbiddenException) {
            logger.warn(
                "Rejecting revoke request from a caller outside the relation without access to the organization. {}",
                context,
            )
            throw notFound(cause = e)
        }
        return when (principal) {
            is SystemPrincipal -> RevokeInitiator.LPS
            is UserPrincipal -> RevokeInitiator.PERSONNEL_MANAGER
        }
    }

    private fun notFound(cause: Throwable? = null) = ApiErrorException.NotFoundException(
        errorMessage = "Linemanager relation not found",
        cause = cause,
        type = ErrorType.NOT_FOUND,
    )
}

private fun RevokableNarmestelederEntity.partyInRelationOrNull(principal: Principal): RevokeInitiator? = when (principal) {
    is SystemPrincipal -> null
    is UserPrincipal -> when (principal.ident) {
        employeeIdentificationNumber.value -> RevokeInitiator.EMPLOYEE
        managerIdentificationNumber.value -> RevokeInitiator.LINEMANAGER
        else -> null
    }
}
