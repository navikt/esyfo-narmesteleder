package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

/** Creates the Dialogporten dialog for a stored behov and returns the dialog id. */
fun interface NarmestelederbehovDialogCreation {
    suspend fun create(id: NarmestelederbehovId, employee: Employee, person: EmployeePerson?): UUID
}
