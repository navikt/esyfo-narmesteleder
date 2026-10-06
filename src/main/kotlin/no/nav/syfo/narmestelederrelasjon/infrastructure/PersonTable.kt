package no.nav.syfo.narmestelederrelasjon.infrastructure

import org.jetbrains.exposed.v1.core.Table

// Read-only projection; legacy person writers own the complete table definition.
internal object PersonTable : Table("person") {
    val fnr = varchar("fnr", 11)
    val fornavn = varchar("fornavn", 255).nullable()
    val mellomnavn = varchar("mellomnavn", 255).nullable()
    val etternavn = varchar("etternavn", 255).nullable()
}
