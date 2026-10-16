package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeePerson
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.LocalDate
import java.util.UUID

class DialogportenNarmestelederbehovDialogCreationTest :
    FunSpec({
        val id = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val dialogId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val employee = Employee(PersonIdent("02039012345"), OrganizationNumber("123456789"))
        val factory = NarmestelederbehovDialogFactory(
            narmestelederbehovApiUrl = "https://api.test.nav.no/api/v1/linemanager/requirement",
            frontendBaseUrl = "https://frontend.test.nav.no",
        )

        fun expectedDialog(person: EmployeePerson?) = factory.create(
            id = id.value,
            organizationNumber = employee.organizationNumber.value,
            employeeIdent = employee.personIdent.value,
            name = person?.name,
            birthDate = person?.birthDate,
        )

        test("sends the dialog for the employee and person to Dialogporten and returns its id") {
            val person = EmployeePerson(BehovPersonName("Kari", "Mellom", "Nordmann"), LocalDate.of(1985, 6, 7))
            val client = RecordingCreateDialogportenClient(dialogId)

            DialogportenNarmestelederbehovDialogCreation(client, factory).create(id, employee, person) shouldBe dialogId

            client.created.single() shouldBe expectedDialog(person)
        }

        test("sends the dialog without person details when none are known") {
            val client = RecordingCreateDialogportenClient(dialogId)

            DialogportenNarmestelederbehovDialogCreation(client, factory).create(id, employee, null) shouldBe dialogId

            client.created.single() shouldBe expectedDialog(null)
        }
    })

private class RecordingCreateDialogportenClient(private val dialogId: UUID) : DialogportenClient {
    val created = mutableListOf<Dialog>()

    override suspend fun createDialog(dialog: Dialog): UUID {
        created += dialog
        return dialogId
    }

    override suspend fun getDialogById(dialogId: UUID): ExtendedDialog = error("Unexpected getDialogById")

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<HttpDialogportenClient.DialogportenPatch>,
    ) = error("Unexpected patchDialog")
}
