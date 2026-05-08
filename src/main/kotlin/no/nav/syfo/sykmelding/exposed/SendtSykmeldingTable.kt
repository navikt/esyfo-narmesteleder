package no.nav.syfo.sykmelding.exposed

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object SendtSykmeldingTable : IntIdTable("sendt_sykmelding") {
    val sykmeldingId = javaUUID("sykmelding_id").uniqueIndex()
    val orgnummer = varchar("orgnummer", 9)
    val syketilfelleStartDato = date("syketilfelle_startdato").nullable()
    val fnr = text("fnr")
    val fom = date("fom")
    val tom = date("tom")
    val revokedDate = date("revoked_date").nullable()
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)
}
