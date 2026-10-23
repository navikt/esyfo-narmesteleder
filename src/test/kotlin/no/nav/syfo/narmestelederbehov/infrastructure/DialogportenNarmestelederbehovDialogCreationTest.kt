package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeePerson
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreationResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.RecordingDialogportenClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.LocalDate
import java.util.UUID

class DialogportenNarmestelederbehovDialogCreationTest :
    FunSpec({
        val id = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val dialogId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val employee = Employee(PersonalIdentificationNumber("02039012345"), OrganizationNumber("123456789"))
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
            val client = creatingClient(dialogId)

            DialogportenNarmestelederbehovDialogCreation(client, factory).create(id, employee, person) shouldBe
                NarmestelederbehovDialogCreationResult.Created(dialogId)

            client.created.single() shouldBe expectedDialog(person)
        }

        test("sends the dialog without person details when none are known") {
            val client = creatingClient(dialogId)

            DialogportenNarmestelederbehovDialogCreation(client, factory).create(id, employee, null) shouldBe
                NarmestelederbehovDialogCreationResult.Created(dialogId)

            client.created.single() shouldBe expectedDialog(null)
        }

        test("returns the Dialogporten failure so the caller can log it and leave the behov for retry") {
            val failure = UpstreamFailure(UpstreamName("dialogporten"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error")))
            val client = creatingClient(dialogId, UpstreamResult.Failure(failure))

            DialogportenNarmestelederbehovDialogCreation(client, factory).create(id, employee, null) shouldBe
                NarmestelederbehovDialogCreationResult.Unavailable(failure)
        }
    })

private fun creatingClient(
    dialogId: UUID,
    result: UpstreamResult<UUID> = UpstreamResult.Success(dialogId),
) = RecordingDialogportenClient(create = { result })
