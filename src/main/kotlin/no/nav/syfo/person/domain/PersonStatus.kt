package no.nav.syfo.person.domain

enum class PersonStatus {
    PENDING,
    ENRICHED,
    NOT_FOUND;

    override fun toString(): String = name
}
