package no.nav.syfo.sykmelding.application

import java.time.LocalDate

data class ArbeidsgiverSykmelding(
    val sykmeldingsperioder: List<SykmeldingsperiodeAGDTO>,
    val syketilfelleStartDato: LocalDate?
)

data class SykmeldingsperiodeAGDTO(
    val fom: LocalDate,
    val tom: LocalDate,
)
