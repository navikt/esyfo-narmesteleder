package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object NarmestelederTable : IntIdTable("narmeste_leder") {
    val narmestelederId = javaUUID("narmeste_leder_id").uniqueIndex()
    val orgnummer = varchar("orgnummer", 9)
    val brukerFnr = varchar("bruker_fnr", 11)
    val brukerNavn = varchar("bruker_navn", 255).nullable()
    val narmestelederNavn = varchar("narmeste_leder_navn", 255).nullable()
    val narmestelederFnr = varchar("narmeste_leder_fnr", 11)
    val narmestelederTelefonnummer = varchar("narmeste_leder_telefonnummer", 255)
    val narmestelederEpost = varchar("narmeste_leder_epost", 255)
    val arbeidsgiverForskutterer = bool("arbeidsgiver_forskutterer").nullable()
    val aktivFom = timestampWithTimeZone("aktiv_fom")
    val aktivTom = timestampWithTimeZone("aktiv_tom").nullable()
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)
}
