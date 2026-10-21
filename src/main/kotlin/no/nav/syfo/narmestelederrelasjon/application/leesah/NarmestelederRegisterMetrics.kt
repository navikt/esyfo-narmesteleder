package no.nav.syfo.narmestelederrelasjon.application.leesah

interface NarmestelederRegisterMetrics {
    fun recordUpserted(count: Int)
    fun recordInvalid()
}
