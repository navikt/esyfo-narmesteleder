package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PersonTable : IntIdTable("person") {
    val fnr = varchar("fnr", 11).uniqueIndex()
    val fornavn = varchar("fornavn", 255).nullable()
    val etternavn = varchar("etternavn", 255).nullable()
    val status = varchar("status", 255)
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)
}
