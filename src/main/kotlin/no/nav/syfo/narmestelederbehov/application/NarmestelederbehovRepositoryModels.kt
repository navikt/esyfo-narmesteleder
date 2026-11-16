package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

data class NewNarmestelederbehov(
    val employee: Employee,
    val mainOrganizationNumber: String,
    val manager: PersonalIdentificationNumber?,
    val reason: BehovReason,
    val status: BehovStatus,
    val revokedRelationId: UUID?,
)

sealed interface CreateBehovResult {
    data class Created(val id: NarmestelederbehovId) : CreateBehovResult
    data object AlreadyExists : CreateBehovResult
}

sealed interface MarkFulfilledResult {
    data class Marked(val id: NarmestelederbehovId, val dialogId: UUID?) : MarkFulfilledResult
    data object Missing : MarkFulfilledResult
}
