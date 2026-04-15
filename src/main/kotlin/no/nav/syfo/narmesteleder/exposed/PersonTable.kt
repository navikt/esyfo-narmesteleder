package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.util.UUID

object PersonTable : IdTable<UUID>("person") {
    override val id = javaUUID("id").databaseGenerated().entityId()
    val fnr = varchar("fnr", 11).uniqueIndex()
    val fornavn = varchar("fornavn", 255).nullable()
    val mellomnavn = varchar("mellomnavn", 255).nullable()
    val etternavn = varchar("etternavn", 255).nullable()
    val foedselsdato = date("foedselsdato").nullable()
    val status = varchar("status", 255)
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}
