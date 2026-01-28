package no.nav.syfo.sykmelding.db

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SendtSykmeldingEntity(
    val id: Long? = null,
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val revokedDate: LocalDate? = null,
    val syketilfelleStartDato: LocalDate?,
    val created: Instant? = null,
    val updated: Instant = Instant.now(),
)
