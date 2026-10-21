package no.nav.syfo.narmestelederrelasjon.application.relationperson

fun interface RelationPersonNameUpdateMetrics {
    fun recordLookupFailed(count: Int)
}
