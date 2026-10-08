package no.nav.syfo.narmestelederrelasjon.application

internal class RecordingDiscardedEmailAddressMetrics : DiscardedEmailAddressMetrics {
    val recorded = mutableListOf<Int>()

    override fun record(count: Int) {
        recorded.add(count)
    }
}
