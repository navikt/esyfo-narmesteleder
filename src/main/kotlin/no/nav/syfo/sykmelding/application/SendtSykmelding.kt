package no.nav.syfo.sykmelding.application

import java.time.LocalDate
import java.util.UUID

data class SendtSykmelding(
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val syketilfelleStartDato: LocalDate?,
)
