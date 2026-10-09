package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

class ExposedNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearAllData()
        }

        test("reads every response field without changing the row") {
            val row = insertNarmestelederbehov(
                behovReason = BehovReason.DEAKTIVERT_LEDER,
                fornavn = "First",
                mellomnavn = "Middle",
                etternavn = "Last",
                narmestelederFnr = null,
            )
            val id = NarmestelederbehovId(row.id)
            val result = requireNotNull(repository.findDetails(id))
            result.id shouldBe id
            result.employeeIdent shouldBe PersonIdent(row.sykmeldtFnr)
            result.organizationNumber shouldBe OrganizationNumber(row.orgnummer)
            result.mainOrganizationNumber shouldBe row.hovedenhetOrgnummer
            result.managerIdent.shouldBeNull()
            result.firstName shouldBe "First"
            result.middleName shouldBe "Middle"
            result.lastName shouldBe "Last"
            result.created shouldBe row.created
            result.updated shouldBe row.updated
            result.status shouldBe row.behovStatus
            result.reason shouldBe row.behovReason
            repository.findDetails(NarmestelederbehovId(UUID.randomUUID())).shouldBeNull()
            findStoredNarmestelederbehov(row.id) shouldBe row
        }

        test("saves only the employee name columns") {
            val before = insertNarmestelederbehov()
            val id = NarmestelederbehovId(before.id)

            repository.saveEmployeeName(
                id = id,
                name = BehovPersonName(firstName = "First", middleName = "Middle", lastName = "Last"),
            )

            val after = requireNotNull(findStoredNarmestelederbehov(before.id))
            after.fornavn shouldBe "First"
            after.mellomnavn shouldBe "Middle"
            after.etternavn shouldBe "Last"
            after.copy(fornavn = null, mellomnavn = null, etternavn = null, updated = before.updated) shouldBe before
        }

        test("finds a behov for fulfillment and returns null for missing id") {
            val row = insertNarmestelederbehov()
            val id = NarmestelederbehovId(row.id)

            repository.findForFulfillment(id) shouldBe Narmestelederbehov(
                id,
                Employee(PersonIdent(row.sykmeldtFnr), OrganizationNumber(row.orgnummer)),
            )
            repository.findForFulfillment(NarmestelederbehovId(UUID.randomUUID())).shouldBeNull()
        }

        test("marks a behov with dialog id without overwriting unrelated fields") {
            val dialogId = UUID.randomUUID()
            val before = insertNarmestelederbehov(
                dialogId = dialogId,
                fornavn = "Employee",
                mellomnavn = "Middle",
                etternavn = "Name",
                narmestelederFnr = "10987654321",
                behovStatus = BehovStatus.BEHOV_EXPIRED,
            )
            val id = NarmestelederbehovId(before.id)

            repository.markFulfilled(id) shouldBe MarkFulfilledResult.Marked(id, dialogId)

            val after = requireNotNull(findStoredNarmestelederbehov(before.id))
            after.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
            after.copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
        }

        test("marks a behov with no dialog and returns Missing for an absent row") {
            val id = NarmestelederbehovId(insertNarmestelederbehov().id)

            repository.markFulfilled(id) shouldBe MarkFulfilledResult.Marked(id, null)
            repository.markFulfilled(NarmestelederbehovId(UUID.randomUUID())) shouldBe MarkFulfilledResult.Missing
        }

        test("marks a completed dialog status without changing other fields") {
            val before = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED)
            val id = NarmestelederbehovId(before.id)

            repository.markDialogCompleted(id) shouldBe MarkDialogCompletedResult.Marked

            val after = requireNotNull(findStoredNarmestelederbehov(before.id))
            after.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
            after.copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
        }

        test("does not overwrite a status changed by another writer") {
            val before = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_EXPIRED)

            repository.markDialogCompleted(NarmestelederbehovId(before.id)) shouldBe MarkDialogCompletedResult.NotFulfilled

            findStoredNarmestelederbehov(before.id) shouldBe before
        }

        test("reports NotFulfilled for a missing row when persisting completed dialog status") {
            repository.markDialogCompleted(NarmestelederbehovId(UUID.randomUUID())) shouldBe MarkDialogCompletedResult.NotFulfilled
        }

        // An employee can have at most one open behov per organization (uq_nl_behov_active_employee_org).
        val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
        openStatuses.forEach { openStatus ->
            test("open behov for an employee match person, organization and status $openStatus") {
                val employee = Employee(PersonIdent("12345678901"), OrganizationNumber("910000001"))
                fun insert(
                    status: BehovStatus,
                    personIdent: String = employee.personIdent.value,
                    organizationNumber: String = employee.organizationNumber.value,
                ) = insertNarmestelederbehov(sykmeldtFnr = personIdent, orgnummer = organizationNumber, behovStatus = status).id
                val open = insert(openStatus)
                BehovStatus.entries
                    .filterNot { it in openStatuses }
                    .forEach { insert(it) }
                insert(BehovStatus.BEHOV_CREATED, personIdent = "10987654321")
                insert(BehovStatus.BEHOV_CREATED, organizationNumber = "910000002")

                repository.findOpenFor(employee).map { it.value } shouldBe listOf(open)
            }
        }
    })
