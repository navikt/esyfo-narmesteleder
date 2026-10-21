package no.nav.syfo.narmestelederrelasjon.application.leesah

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

internal class RecordingLeesahNarmestelederrelasjonRepository : LeesahNarmestelederrelasjonRepository {
    data class Call(val relasjoner: List<NarmestelederrelasjonUpsert>, val persons: List<PersonalIdentificationNumber>)

    val calls = mutableListOf<Call>()

    override fun upsertAll(relasjoner: List<NarmestelederrelasjonUpsert>, persons: List<PersonalIdentificationNumber>) {
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
    sykmeldtFnr = PersonalIdentificationNumber(sykmeldtFnr),
    orgnummer = OrganizationNumber(orgnummer),
    narmestelederFnr = PersonalIdentificationNumber(narmestelederFnr),
    narmestelederTelefonnummer = narmestelederTelefonnummer,
    narmestelederEpost = narmestelederEpost,
    aktivFom = aktivFom,
    aktivTom = aktivTom,
    arbeidsgiverForskutterer = arbeidsgiverForskutterer,
)
