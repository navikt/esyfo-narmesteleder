package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

/** Delegates to the legacy Dialogporten flow, which logs and swallows failures so the stored behov is retried by SendDialogTask. */
class DialogportenNarmestelederbehovDialogCreation(
    private val dialogportenService: DialogportenService,
) : NarmestelederbehovDialogCreation {
    override suspend fun create(id: NarmestelederbehovId, behov: NewNarmestelederbehov) {
        dialogportenService.sendToDialogporten(
            NarmestelederBehovEntity(
                id = id.value,
                orgnummer = behov.employee.organizationNumber.value,
                hovedenhetOrgnummer = behov.mainOrganizationNumber,
                sykmeldtFnr = behov.employee.personIdent.value,
                narmestelederFnr = behov.manager?.value,
                behovReason = behov.reason,
                behovStatus = behov.status,
                avbruttNarmesteLederId = behov.revokedRelationId,
            ),
        )
    }
}
