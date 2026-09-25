package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.organisasjonstilgang.application.DenialReason

enum class RevocationInitiator {
    EMPLOYEE,
    LINEMANAGER,
    PERSONNEL_MANAGER,
    LPS,
}

sealed interface RevokeNarmestelederrelasjonResult {
    data class Revoked(val initiator: RevocationInitiator) : RevokeNarmestelederrelasjonResult

    data object AlreadyRevoked : RevokeNarmestelederrelasjonResult

    data class NotFound(
        val reason: Reason,
        val denialReason: DenialReason? = null,
    ) : RevokeNarmestelederrelasjonResult

    enum class Reason {
        RELATION_NOT_FOUND,
        ACCESS_DENIED,
    }
}
