package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

internal class RecordingLeesahNarmestelederrelasjonRepository : LeesahNarmestelederrelasjonRepository {
    data class Call(val relasjoner: List<NarmestelederrelasjonUpsert>, val persons: List<PersonIdent>)

    val calls = mutableListOf<Call>()

    override fun upsertAll(relasjoner: List<NarmestelederrelasjonUpsert>, persons: List<PersonIdent>) {
        calls.add(Call(relasjoner, persons))
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

internal fun LeesahNarmestelederrelasjon.validated() = NarmestelederrelasjonUpsert(
    narmestelederId = narmestelederId,
    sykmeldtFnr = PersonIdent(sykmeldtFnr),
    orgnummer = OrganizationNumber(orgnummer),
    narmestelederFnr = PersonIdent(narmestelederFnr),
    narmestelederTelefonnummer = narmestelederTelefonnummer,
    narmestelederEpost = narmestelederEpost,
    aktivFom = aktivFom,
    aktivTom = aktivTom,
    arbeidsgiverForskutterer = arbeidsgiverForskutterer,
)
