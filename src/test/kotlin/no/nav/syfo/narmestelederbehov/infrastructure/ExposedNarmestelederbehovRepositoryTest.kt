package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.CreateBehovResult
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

class ExposedNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase)
        val setupDb = PostgresNarmestelederDb(TestDB.database)

        beforeTest {
            TestDB.clearAllData()
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

        // An employee can have at most one open behov per organization (uq_nl_behov_active_employee_org).
        val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
        openStatuses.forEach { openStatus ->
            test("open behov for an employee match person, organization and status $openStatus") {
                val employee = Employee(PersonIdent("12345678901"), OrganizationNumber("910000001"))
                suspend fun insert(
                    status: BehovStatus,
                    personIdent: String = employee.personIdent.value,
                    organizationNumber: String = employee.organizationNumber.value,
                ) = requireNotNull(
                    setupDb.insertNlBehov(
                        nlBehovEntity().copy(sykmeldtFnr = personIdent, orgnummer = organizationNumber, behovStatus = status),
                    ).id,
                )
                val open = insert(openStatus)
                BehovStatus.entries
                    .filterNot { it in openStatuses }
                    .forEach { insert(it) }
                insert(BehovStatus.BEHOV_CREATED, personIdent = "10987654321")
                insert(BehovStatus.BEHOV_CREATED, organizationNumber = "910000002")

                repository.findOpenFor(employee).map { it.value } shouldBe listOf(open)
            }
        }
        val newBehov = NewNarmestelederbehov(
            employee = Employee(PersonIdent("12345678901"), OrganizationNumber("910000001")),
            mainOrganizationNumber = "910000009",
            manager = PersonIdent("10987654321"),
            reason = BehovReason.DEAKTIVERT_LEDER,
            status = BehovStatus.BEHOV_CREATED,
            revokedRelationId = UUID.fromString("00000000-0000-0000-0000-000000000044"),
        )

        test("creates a behov with every column") {
            val result = repository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()

            val row = requireNotNull(setupDb.findBehovById(result.id.value))
            row.sykmeldtFnr shouldBe "12345678901"
            row.orgnummer shouldBe "910000001"
            row.hovedenhetOrgnummer shouldBe "910000009"
            row.narmestelederFnr shouldBe "10987654321"
            row.behovReason shouldBe BehovReason.DEAKTIVERT_LEDER
            row.behovStatus shouldBe BehovStatus.BEHOV_CREATED
            row.avbruttNarmesteLederId shouldBe newBehov.revokedRelationId
            row.dialogId.shouldBeNull()
            row.fornavn.shouldBeNull()
        }

        test("creates a behov without manager or revoked relation") {
            val result = repository.create(newBehov.copy(manager = null, revokedRelationId = null))
                .shouldBeInstanceOf<CreateBehovResult.Created>()

            val row = requireNotNull(setupDb.findBehovById(result.id.value))
            row.narmestelederFnr.shouldBeNull()
            row.avbruttNarmesteLederId.shouldBeNull()
        }

        test("reports AlreadyExists without storing when the employee has an open behov in the organization") {
            val existing = repository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()

            repository.create(newBehov.copy(status = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)) shouldBe CreateBehovResult.AlreadyExists

            repository.findOpenFor(newBehov.employee) shouldBe listOf(existing.id)
        }

        test("stores exactly one open behov when concurrent creates race") {
            val results = coroutineScope {
                (1..2).map { async { repository.create(newBehov) } }.awaitAll()
            }

            results.count { it is CreateBehovResult.Created } shouldBe 1
            results.count { it == CreateBehovResult.AlreadyExists } shouldBe 1
            repository.findOpenFor(newBehov.employee).size shouldBe 1
        }

        test("creates a behov alongside closed and error behov for the same employee and organization") {
            repository.create(newBehov.copy(status = BehovStatus.ARBEIDSFORHOLD_NOT_FOUND)).shouldBeInstanceOf<CreateBehovResult.Created>()
            val closed = repository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()
            repository.markFulfilled(closed.id)

            repository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()
        }
    })
