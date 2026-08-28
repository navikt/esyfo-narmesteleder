package no.nav.syfo.narmesteleder.domain

/**
 * Who a revocation is attributed to when it is resolved from an existing relation.
 *
 * Kept separate from [RevokedBy], which is part of the public API contract and only covers the
 * parties a linemanager requirement can be revoked by.
 */
enum class RevokeInitiator {
    EMPLOYEE,
    LINEMANAGER,
    PERSONNEL_MANAGER,
    LPS,
}
