package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.platform.database.PostgresNow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object NarmestelederTable : IntIdTable("narmeste_leder") {
    val narmestelederId = javaUUID("narmeste_leder_id").uniqueIndex()
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykmeldt_fnr", 11)
    val narmestelederFnr = varchar("narmeste_leder_fnr", 11)
    val narmestelederTelefonnummer = varchar("narmeste_leder_telefonnummer", 255)
    val narmestelederEpost = varchar("narmeste_leder_epost", 255)
    val arbeidsgiverForskutterer = bool("arbeidsgiver_forskutterer").nullable()
    val aktivFom = timestampWithTimeZone("aktiv_fom")
    val aktivTom = timestampWithTimeZone("aktiv_tom").nullable()
    val created = timestampWithTimeZone("created").defaultExpression(PostgresNow)
    val updated = timestampWithTimeZone("updated").defaultExpression(PostgresNow)

    init {
        index("narmeste_leder_fnr_idx", false, sykmeldtFnr)
        index("narmeste_leder_nlfnr_idx", false, narmestelederFnr)
        index("narmeste_leder_orgnr_idx", false, orgnummer)
        index("narmeste_leder_tom_idx", false, aktivTom)
        index("narmeste_leder_aktiv_orgnummer_idx", false, orgnummer, id, filterCondition = { aktivTom.isNull() })
    }
}
