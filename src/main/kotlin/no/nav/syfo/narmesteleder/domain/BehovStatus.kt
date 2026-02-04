package no.nav.syfo.narmesteleder.domain

enum class BehovStatus {
    BEHOV_CREATED,
    DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
    BEHOV_FULFILLED,
    DIALOGPORTEN_STATUS_SET_COMPLETED,
    ERROR,
    ARBEIDSFORHOLD_NOT_FOUND,
    HOVEDENHET_NOT_FOUND;

    companion object {
        fun errorStatusList(): List<BehovStatus> = listOf(
            ERROR,
            ARBEIDSFORHOLD_NOT_FOUND,
            HOVEDENHET_NOT_FOUND,
        )
    }

    override fun toString(): String = name
}
