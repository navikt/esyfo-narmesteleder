package no.nav.syfo.sykmelding.model

import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SendtSykmeldingDto(
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String?,
    val fom: LocalDate,
    val tom: LocalDate,
    val syketilfelleStartDato: LocalDate?,
    val created: Instant,
    val updated: Instant = Instant.now(),
)

fun SendtSykmeldingDto.toDbEntity(): SendtSykmeldingEntity = SendtSykmeldingEntity(
    sykmeldingId = this.sykmeldingId,
    fnr = this.fnr,
    orgnummer = requireNotNull(this.orgnummer) { "orgnummer must not be null in db entities" },
    fom = this.fom,
    tom = this.tom,
    syketilfelleStartDato = this.syketilfelleStartDato,
    created = this.created,
    updated = this.updated,
)
