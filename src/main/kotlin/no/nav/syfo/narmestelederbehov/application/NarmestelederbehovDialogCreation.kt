package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.upstream.UpstreamFailure
import java.util.UUID

/** Creates the Dialogporten dialog for a stored behov. */
fun interface NarmestelederbehovDialogCreation {
    suspend fun create(id: NarmestelederbehovId, employee: Employee, person: EmployeePerson?): NarmestelederbehovDialogCreationResult
}

sealed interface NarmestelederbehovDialogCreationResult {
    data class Created(val dialogId: UUID) : NarmestelederbehovDialogCreationResult
    data class Unavailable(val failure: UpstreamFailure) : NarmestelederbehovDialogCreationResult
}
