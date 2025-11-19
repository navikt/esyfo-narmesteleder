package no.nav.syfo.narmesteleder.domain

enum class RevokedBy {
    LINEMANAGER,
    EMPLOYEE,
    LPS;

    companion object {
        fun from(reason: BehovReason) = when (reason) {
            BehovReason.DEAKTIVERT_LEDER -> LINEMANAGER
            BehovReason.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING -> EMPLOYEE
            BehovReason.DEAKTIVERT_ARBEIDSTAKER -> EMPLOYEE
            else -> null
        }
    }
}
