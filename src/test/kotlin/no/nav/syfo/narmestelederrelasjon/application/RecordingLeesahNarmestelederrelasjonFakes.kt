package no.nav.syfo.narmestelederrelasjon.application

internal class RecordingLeesahNarmestelederrelasjonStore : LeesahNarmestelederrelasjonStore {
    data class Call(val relasjoner: List<LeesahNarmestelederrelasjon>, val personFnrs: List<String>)

    val calls = mutableListOf<Call>()

    override fun store(relasjoner: List<LeesahNarmestelederrelasjon>, personFnrs: List<String>) {
        calls.add(Call(relasjoner, personFnrs))
    }
}

internal class RecordingNarmestelederRegisterMetrics : NarmestelederRegisterMetrics {
    val upserted = mutableListOf<Int>()
    var invalid = 0

    override fun recordUpserted(count: Int) {
        upserted.add(count)
    }

    override fun recordInvalid() {
        invalid++
    }
}
