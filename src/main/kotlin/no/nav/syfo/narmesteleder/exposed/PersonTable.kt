package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PersonTable : Table("person") {
    val id = javaUUID("id").databaseGenerated()
    val fnr = varchar("fnr", 11).uniqueIndex()
    val fornavn = varchar("fornavn", 255).nullable()
    val mellomnavn = varchar("mellomnavn", 255).nullable()
    val etternavn = varchar("etternavn", 255).nullable()
    val status = varchar("status", 255)
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}
