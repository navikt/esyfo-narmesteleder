package no.nav.syfo.narmesteleder.domain

enum class LineManagerRequirementStatus {
    CREATED,
    REQUIRES_ATTENTION,
    COMPLETED,
    ERROR;

    companion object {
        fun from(behovStatus: BehovStatus): LineManagerRequirementStatus = when (behovStatus) {
            BehovStatus.BEHOV_CREATED -> CREATED

            BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION -> REQUIRES_ATTENTION

            BehovStatus.BEHOV_FULFILLED,
            BehovStatus.BEHOV_EXPIRED,
            BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED -> COMPLETED

            BehovStatus.ERROR,
            BehovStatus.ARBEIDSFORHOLD_NOT_FOUND,
            BehovStatus.HOVEDENHET_NOT_FOUND -> throw IllegalArgumentException(
                "Cannot map ERROR, ARBEIDSFORHOLD_NOT_FOUND or HOVEDENHET_NOT_FOUND to LineManagerRequirementStatus"
            )
        }
    }
}
