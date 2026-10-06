package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.TestDB.Companion.updateCreated
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

class ExposedNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase)
        val setupDb = PostgresNarmestelederDb(TestDB.database)

        beforeTest { TestDB.clearAllData() }

        test("open queries filter status and organization with a strict created boundary, ordering, limit and count") {
            val organizationNumber = OrganizationNumber("910000001")
            val boundary = Instant.parse("2025-01-01T00:00:00Z")
            val later = setupDb.insertNlBehov(
                nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
            )
            val earlier = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED))
            updateCreated(requireNotNull(later.id), boundary.plusSeconds(2))
            updateCreated(requireNotNull(earlier.id), boundary.plusSeconds(1))
            for (status in BehovStatus.entries) {
                if (status !in listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)) {
                    val closed = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = status))
                    updateCreated(requireNotNull(closed.id), boundary.plusSeconds(1))
                }
            }
            val otherOrganization = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = "910000002", behovStatus = BehovStatus.BEHOV_CREATED))
            val atBoundary = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED))
            val beforeBoundary = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED))
            updateCreated(requireNotNull(otherOrganization.id), boundary.plusSeconds(1))
            updateCreated(requireNotNull(atBoundary.id), boundary)
            updateCreated(requireNotNull(beforeBoundary.id), boundary.minusSeconds(1))

            repository.findOpen(organizationNumber, boundary, limit = 50).map { it.id.value } shouldBe listOf(earlier.id, later.id)
            repository.findOpen(organizationNumber, boundary, limit = 1).map { it.id.value } shouldBe listOf(earlier.id)
            repository.countOpen(organizationNumber, boundary) shouldBe 2L
            repository.countOpen(organizationNumber, boundary.plusSeconds(2)) shouldBe 0L
            repository.findOpen(organizationNumber, boundary.plusSeconds(2), limit = 50) shouldBe emptyList()
        }

        test("reads every response field without changing the row") {
            val row = setupDb.insertNlBehov(
                nlBehovEntity().copy(
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    fornavn = "First",
                    mellomnavn = "Middle",
                    etternavn = "Last",
                    narmestelederFnr = null,
                ),
            )
            val id = NarmestelederbehovId(requireNotNull(row.id))
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
            setupDb.findBehovById(id.value) shouldBe row
        }

        test("saves only the employee name columns") {
            val row = setupDb.insertNlBehov(nlBehovEntity().copy(fornavn = null, mellomnavn = null, etternavn = null))
            val id = NarmestelederbehovId(requireNotNull(row.id))
            val before = requireNotNull(setupDb.findBehovById(id.value))

            repository.saveEmployeeName(
                id = id,
                name = BehovPersonName(firstName = "First", middleName = "Middle", lastName = "Last"),
            )

            val after = requireNotNull(setupDb.findBehovById(id.value))
            after.fornavn shouldBe "First"
            after.mellomnavn shouldBe "Middle"
            after.etternavn shouldBe "Last"
            after.copy(fornavn = null, mellomnavn = null, etternavn = null, updated = before.updated) shouldBe before
        }

        test("finds a behov for fulfillment and returns null for missing id") {
            val row = setupDb.insertNlBehov(nlBehovEntity())
            val id = NarmestelederbehovId(requireNotNull(row.id))

            repository.findForFulfillment(id) shouldBe Narmestelederbehov(
                id,
                Employee(PersonIdent(row.sykmeldtFnr), OrganizationNumber(row.orgnummer)),
            )
            repository.findForFulfillment(NarmestelederbehovId(UUID.randomUUID())).shouldBeNull()
        }

        test("marks a behov with dialog id without overwriting unrelated fields") {
            val dialogId = UUID.randomUUID()
            val row = setupDb.insertNlBehov(
                nlBehovEntity().copy(
                    dialogId = dialogId,
                    fornavn = "Employee",
                    mellomnavn = "Middle",
                    etternavn = "Name",
                    narmestelederFnr = "10987654321",
                    behovStatus = BehovStatus.BEHOV_EXPIRED,
                ),
            )
            val id = NarmestelederbehovId(requireNotNull(row.id))
            setupDb.updateNlBehov(row.copy(dialogId = dialogId))
            val before = requireNotNull(setupDb.findBehovById(id.value))

            repository.markFulfilled(id) shouldBe MarkFulfilledResult.Marked(id, dialogId)

            val after = requireNotNull(setupDb.findBehovById(id.value))
            after.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
            after.copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
        }

        test("marks a behov with no dialog and returns Missing for an absent row") {
            val row = setupDb.insertNlBehov(nlBehovEntity())
            val id = NarmestelederbehovId(requireNotNull(row.id))

            repository.markFulfilled(id) shouldBe MarkFulfilledResult.Marked(id, null)
            repository.markFulfilled(NarmestelederbehovId(UUID.randomUUID())) shouldBe MarkFulfilledResult.Missing
        }

        test("marks a completed dialog status without changing other fields") {
            val row = setupDb.insertNlBehov(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_FULFILLED))
            val id = NarmestelederbehovId(requireNotNull(row.id))
            val before = requireNotNull(setupDb.findBehovById(id.value))

            repository.markDialogCompleted(id) shouldBe MarkDialogCompletedResult.Marked

            val after = requireNotNull(setupDb.findBehovById(id.value))
            after.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
            after.copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
        }

        test("does not overwrite a status changed by another writer") {
            val row = setupDb.insertNlBehov(nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_EXPIRED))
            val id = NarmestelederbehovId(requireNotNull(row.id))
            val before = requireNotNull(setupDb.findBehovById(id.value))

            repository.markDialogCompleted(id) shouldBe MarkDialogCompletedResult.NotFulfilled

            requireNotNull(setupDb.findBehovById(id.value)) shouldBe before
        }

        test("reports NotFulfilled for a missing row when persisting completed dialog status") {
            repository.markDialogCompleted(NarmestelederbehovId(UUID.randomUUID())) shouldBe MarkDialogCompletedResult.NotFulfilled
        }
    })
