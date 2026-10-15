package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
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

        suspend fun create(person: EmployeePerson?, ident: PersonIdent = employee.personIdent): Dialog {
            val client = RecordingCreateDialogportenClient(dialogId)
            DialogportenNarmestelederbehovDialogCreation(client, factory).create(id, employee.copy(personIdent = ident), person) shouldBe
                dialogId
            return client.created.single()
        }

        fun Dialog.title() = content.title.value.single().value
        fun Dialog.summary() = content.summary?.value?.single()?.value

        test("creates a dialog for the employee's organization and returns its id") {
            val dialog = create(EmployeePerson(BehovPersonName("Kari", "Mellom", "Nordmann"), LocalDate.of(1985, 6, 7)))

            dialog.serviceResource shouldBe "urn:altinn:resource:nav_syfo_oppgi-narmesteleder"
            dialog.status shouldBe DialogStatus.RequiresAttention
            dialog.party shouldBe "urn:altinn:organization:identifier-no:123456789"
            dialog.externalReference shouldBe id.value.toString()
            dialog.isApiOnly shouldBe false
            dialog.title() shouldBe "Oppgi nærmeste leder for Kari Mellom Nordmann (f. 07.06.1985)"
            dialog.summary() shouldBe "Kari Mellom Nordmann ${NarmestelederbehovDialogFactory.DIALOG_SUMMARY}"
            dialog.attachments?.map { attachment -> attachment.urls.single().let { it.consumerType to it.url } } shouldBe listOf(
                AttachmentUrlConsumerType.Api to "https://api.test.nav.no/api/v1/linemanager/requirement/${id.value}",
                AttachmentUrlConsumerType.Gui to "https://frontend.test.nav.no/ansatte/narmesteleder/${id.value}",
            )
        }

        test("reads the birth date from the ident when PDL has none") {
            create(EmployeePerson(BehovPersonName("Kari", null, "Nordmann"), null)).title() shouldBe
                "Oppgi nærmeste leder for Kari Nordmann (f. 02.03.9012)"
        }

        test("shows the d-nummer when the ident is not a valid date") {
            val dNummer = PersonIdent("42039012345")

            create(EmployeePerson(BehovPersonName("Kari", null, "Nordmann"), null), dNummer).title() shouldBe
                "Oppgi nærmeste leder for Kari Nordmann (d-nummer: ${dNummer.value})"
        }

        test("uses generic texts without person details") {
            val dialog = create(null)

            dialog.title() shouldBe NarmestelederbehovDialogFactory.DIALOG_TITLE_NO_NAME
            dialog.summary() shouldBe "En ansatt ${NarmestelederbehovDialogFactory.DIALOG_SUMMARY}"
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
