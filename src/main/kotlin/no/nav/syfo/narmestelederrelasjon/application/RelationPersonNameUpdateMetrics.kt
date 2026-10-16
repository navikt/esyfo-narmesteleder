package no.nav.syfo.narmestelederrelasjon.application

fun interface RelationPersonNameUpdateMetrics {
    fun recordLookupFailed(count: Int)
}
