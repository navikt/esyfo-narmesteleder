package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class NarmestelederEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<NarmestelederEntity>(NarmestelederTable)

    var narmesteLederId by NarmestelederTable.narmesteLederId
    var orgnummer by NarmestelederTable.orgnummer
    var brukerFnr by NarmestelederTable.brukerFnr
    var brukerNavn by NarmestelederTable.brukerNavn
    var narmestelederNavn by NarmestelederTable.narmestelederNavn
    var narmestelederFnr by NarmestelederTable.narmestelederFnr
    var narmestelederTelefonnummer by NarmestelederTable.narmestelederTelefonnummer
    var narmestelederEpost by NarmestelederTable.narmestelederEpost
    var arbeidsgiverForskutterer by NarmestelederTable.arbeidsgiverForskutterer
    var aktivFom by NarmestelederTable.aktivFom
    var aktivTom by NarmestelederTable.aktivTom
    var created by NarmestelederTable.created
    var updated by NarmestelederTable.updated
}
