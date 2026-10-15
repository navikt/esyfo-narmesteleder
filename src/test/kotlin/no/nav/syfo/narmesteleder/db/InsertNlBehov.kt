package no.nav.syfo.narmesteleder.db

import no.nav.syfo.TestDB
import no.nav.syfo.narmestelederbehov.infrastructure.insertNarmestelederbehov

fun insertNlBehov(entity: NarmestelederBehovEntity): NarmestelederBehovEntity {
    val stored = insertNarmestelederbehov(
        sykmeldtFnr = entity.sykmeldtFnr,
        orgnummer = entity.orgnummer,
        hovedenhetOrgnummer = entity.hovedenhetOrgnummer,
        narmestelederFnr = entity.narmestelederFnr,
        behovReason = entity.behovReason,
        behovStatus = entity.behovStatus,
        fornavn = entity.fornavn,
        mellomnavn = entity.mellomnavn,
        etternavn = entity.etternavn,
        dialogId = entity.dialogId,
        avbruttNarmestelederId = entity.avbruttNarmesteLederId,
    )
    return requireNotNull(TestDB.database.findBehovById(stored.id))
}
