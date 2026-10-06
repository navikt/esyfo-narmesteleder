package no.nav.syfo.narmestelederrelasjon.application

fun interface DiscardedEmailAddressMetrics {
    fun record(count: Int)
}
