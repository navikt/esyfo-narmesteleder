package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.EmployeePerson
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreationResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogCreateResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.upstream.UpstreamResult
import org.slf4j.event.Level

private val existingDialogReused = applicationEvent<String>(
    name = "narmestelederbehov_dialog_already_exists",
    level = Level.INFO,
    message = "Dialogporten already had the dialog for the behov; reusing its id",
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it }),
)

class DialogportenNarmestelederbehovDialogCreation(
    private val client: DialogportenClient,
    private val dialogFactory: NarmestelederbehovDialogFactory,
) : NarmestelederbehovDialogCreation {
    override suspend fun create(id: NarmestelederbehovId, employee: Employee, person: EmployeePerson?): NarmestelederbehovDialogCreationResult {
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
        return when (val result = client.createDialog(dialog)) {
            is UpstreamResult.Success -> {
                if (result.value is DialogCreateResult.AlreadyExists) logger.logEvent(existingDialogReused, id.value.toString())
                NarmestelederbehovDialogCreationResult.Created(result.value.dialogId)
            }
            is UpstreamResult.Failure -> NarmestelederbehovDialogCreationResult.Unavailable(result.failure)
        }
    }

    private companion object {
        val logger = applicationLogger(DialogportenNarmestelederbehovDialogCreation::class.java)
    }
}
