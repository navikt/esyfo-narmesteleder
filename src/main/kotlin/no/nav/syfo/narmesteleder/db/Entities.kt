package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class NarmesteLederAvbruttEntity(
    val id: UUID? = null,
    val orgnummer: String,
    val sykmeldtFnr: String,
    val narmesteLederFnr: String,
    val status: String,
    val aktivFom: OffsetDateTime,
    val aktivTom: OffsetDateTime,
)

fun ResultSet.toNarmesteLederAvbruttEntity(): NarmesteLederAvbruttEntity =
    NarmesteLederAvbruttEntity(
        id = this.getObject("id") as UUID,
        orgnummer = this.getString("orgnummer"),
        sykmeldtFnr = this.getString("sykmeldt_fnr"),
        narmesteLederFnr = this.getString("narmeste_leder_fnr"),
        status = this.getString("status"),
        aktivFom = this.getObject("aktiv_fom", OffsetDateTime::class.java),
        aktivTom = this.getObject("aktiv_tom", OffsetDateTime::class.java),
    )
