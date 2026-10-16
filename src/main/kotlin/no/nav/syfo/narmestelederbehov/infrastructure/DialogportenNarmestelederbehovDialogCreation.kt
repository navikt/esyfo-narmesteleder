package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederbehov.application.EmployeePerson
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.upstream.getOrThrow
import java.util.UUID

class DialogportenNarmestelederbehovDialogCreation(
    private val client: DialogportenClient,
    private val dialogFactory: NarmestelederbehovDialogFactory,
) : NarmestelederbehovDialogCreation {
    override suspend fun create(id: NarmestelederbehovId, employee: Employee, person: EmployeePerson?): UUID {
        val dialog = dialogFactory.create(
            id = id.value,
            organizationNumber = employee.organizationNumber.value,
            employeeIdent = employee.personIdent.value,
            name = person?.name,
            birthDate = person?.birthDate,
        )
        dialog.attachments?.firstOrNull()?.let {
            logger.info("Sending behov ${id.value} to dialogporten, with link ${it.urls.firstOrNull()?.url}")
        }
        return client.createDialog(dialog).getOrThrow()
    }

    private companion object {
        val logger = applicationLogger(DialogportenNarmestelederbehovDialogCreation::class.java)
    }
}
