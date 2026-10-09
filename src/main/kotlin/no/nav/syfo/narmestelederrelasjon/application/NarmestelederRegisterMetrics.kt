package no.nav.syfo.narmestelederrelasjon.application

interface NarmestelederRegisterMetrics {
    fun recordUpserted(count: Int)
    fun recordInvalid()
}
