package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.db.INarmestelederRevokeDb
import no.nav.syfo.narmesteleder.db.RevokableNarmestelederEntity
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.util.logger
import java.util.UUID

sealed interface RevokeOutcome {
    data class Revoked(val revokedBy: RevokedBy) : RevokeOutcome
    data object AlreadyRevoked : RevokeOutcome
}

class LinemanagerRevokeService(
    private val narmestelederRevokeDb: INarmestelederRevokeDb,
    private val narmestelederKafkaService: NarmestelederKafkaService,
) {
    private val logger = logger()

    /**
     * Revokes the relation identified by [narmestelederId] on behalf of the logged in person.
     *
     * Only the employee or the line manager of the relation may revoke it. To avoid disclosing
     * whether a given narmesteleder id exists, callers outside the relation get the same
     * NotFoundException as callers using an unknown id. The distinction is only visible in the logs.
     */
    suspend fun revoke(
        narmestelederId: UUID,
        principal: Principal,
        context: String,
    ): RevokeOutcome {
        if (principal !is UserPrincipal) {
            logger.warn("Rejecting revoke request from non-personal principal. {}", context)
            throw ApiErrorException.ForbiddenException(
                errorMessage = "Forbidden",
                type = ErrorType.AUTHORIZATION_ERROR,
            )
        }

        val relation = narmestelederRevokeDb.findByNarmestelederId(narmestelederId)
        if (relation == null) {
            logger.info("Revoke request for unknown linemanager relation. {}", context)
            throw notFound()
        }

        val revokedBy = relation.revokedByOrNull(principal)
        if (revokedBy == null) {
            logger.warn("Rejecting revoke request from a person outside the linemanager relation. {}", context)
            throw notFound()
        }

        if (!relation.isActive) {
            logger.info("Revoke request for an already revoked linemanager relation. {}", context)
            return RevokeOutcome.AlreadyRevoked
        }

        narmestelederKafkaService.avbrytNarmesteLederRelation(
            employeeIdentificationNumber = relation.employeeIdentificationNumber,
            orgNumber = relation.orgNumber,
            source = NlResponseSource.getRevokeSourceFrom(revokedBy),
        )

        return RevokeOutcome.Revoked(revokedBy)
    }

    private fun notFound() = ApiErrorException.NotFoundException(
        errorMessage = "Linemanager relation not found",
        type = ErrorType.NOT_FOUND,
    )
}

private fun RevokableNarmestelederEntity.revokedByOrNull(principal: UserPrincipal): RevokedBy? = when (principal.ident) {
    employeeIdentificationNumber.value -> RevokedBy.EMPLOYEE
    managerIdentificationNumber.value -> RevokedBy.LINEMANAGER
    else -> null
}
