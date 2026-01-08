package no.nav.syfo.narmesteleder.domain

enum class BehovStatus {
    BEHOV_CREATED,
    DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
    BEHOV_FULFILLED,
    DIALOGPORTEN_STATUS_SET_COMPLETED,
    ERROR,
    BEHOV_EXPIRED;

    override fun toString(): String {
        return name
    }
}
