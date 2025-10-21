package no.nav.syfo.narmesteleder.domain

enum class BehovStatus {
    RECEIVED,
    PENDING,
    COMPLETED,
    ERROR;

    override fun toString(): String {
        return name
    }
}
