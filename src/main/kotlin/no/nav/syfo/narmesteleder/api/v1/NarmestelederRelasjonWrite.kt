package no.nav.syfo.narmesteleder.api.v1

import java.util.UUID
import no.nav.syfo.narmesteleder.domain.NlBehovUpdate
import no.nav.syfo.narmesteleder.domain.NlBehovWrite
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

data class NarmesteLederRelasjonerWrite(
    val sykmeldtFnr: String,
    val organisasjonsnummer: String,
    val leder: Leder,
) {
    fun toNlResponse(): NlResponse = NlResponse(
        orgnummer = organisasjonsnummer, leder = leder, sykmeldt = Sykmeldt(
            fnr = sykmeldtFnr, navn = "PLACEHOLDER"
        )
    )

    fun toNlbehovUpdate(id: UUID) = NlBehovUpdate(
        sykmeldtFnr = sykmeldtFnr,
        orgnummer = organisasjonsnummer,
        narmesteLederFnr = leder.fnr,
        id = id
    )
}
