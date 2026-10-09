package no.nav.syfo.narmestelederrelasjon.infrastructure

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import java.util.UUID

// The legacy PersonTable/PersonEntity remain for person enrichment and PDL updates until #619.
internal object PersonTable : IdTable<UUID>("person") {
    override val id = javaUUID("id").databaseGenerated().entityId()
    val fnr = varchar("fnr", 11).uniqueIndex()
    val fornavn = varchar("fornavn", 255).nullable()
    val mellomnavn = varchar("mellomnavn", 255).nullable()
    val etternavn = varchar("etternavn", 255).nullable()
    val foedselsdato = date("foedselsdato").nullable()
    val status = varchar("status", 255)

    override val primaryKey = PrimaryKey(id)
}
