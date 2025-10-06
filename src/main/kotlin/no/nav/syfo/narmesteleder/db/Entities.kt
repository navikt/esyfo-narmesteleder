package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.util.*

data class NarmesteLederBehovEntity(
    val id: UUID? = null,
    val orgnummer: String,
    val sykmeldtFnr: String,
    val narmesteLederFnr: String,
    val status: String
)

fun ResultSet.toNarmesteLederBehovEntity(): NarmesteLederBehovEntity =
    NarmesteLederBehovEntity(
        id = this.getObject("id") as UUID,
        orgnummer = this.getString("orgnummer"),
        sykmeldtFnr = this.getString("sykmeldt_fnr"),
        narmesteLederFnr = this.getString("narmeste_leder_fnr"),
        status = this.getString("status")
    )
