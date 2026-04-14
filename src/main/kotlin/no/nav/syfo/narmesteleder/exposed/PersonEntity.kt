package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class PersonEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<PersonEntity>(PersonTable)

    var fnr by PersonTable.fnr
    var fornavn by PersonTable.fornavn
    var mellomnavn by PersonTable.mellomnavn
    var etternavn by PersonTable.etternavn
    var status by PersonTable.status
    var created by PersonTable.created
    var updated by PersonTable.updated
}
