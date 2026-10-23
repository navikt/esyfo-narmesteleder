package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.CreateBehovResult
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkDialogCreatedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogReference
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.PendingNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

class ExposedNarmestelederbehovRepositoriesTest :
    FunSpec({
        val readRepository = ExposedNarmestelederbehovReadRepository(TestDB.exposedDatabase)
        val fulfillmentRepository = ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase)
        val lifecycleRepository = ExposedNarmestelederbehovLifecycleRepository(TestDB.exposedDatabase)
        val now = Instant.parse("2026-10-01T10:00:00Z")
        val cutoff = now.minusSeconds(10)

        beforeTest {
            TestDB.clearAllData()
        }

        test("selects only BEHOV_CREATED strictly older than ten seconds, oldest first, without changing rows") {
            val newer = insertNarmestelederbehov(created = now.minusSeconds(11))
            val oldest = insertNarmestelederbehov(created = now.minusSeconds(300), dialogId = UUID.randomUUID())
            val boundary = insertNarmestelederbehov(created = cutoff)
            val recent = insertNarmestelederbehov(created = now.minusSeconds(9))
            BehovStatus.entries.filterNot { it == BehovStatus.BEHOV_CREATED }.forEach { status ->
                insertNarmestelederbehov(behovStatus = status, created = now.minusSeconds(600))
            }

            lifecycleRepository.findPendingDialogs(cutoff, 100) shouldBe listOf(oldest, newer).map { row ->
                PendingNarmestelederbehovDialog(
                    id = NarmestelederbehovId(row.id),
                    employee = Employee(PersonalIdentificationNumber(row.sykmeldtFnr), OrganizationNumber(row.orgnummer)),
                    created = row.created,
                    dialogId = row.dialogId,
                )
            }
            listOf(oldest, newer, boundary, recent).forEach { row ->
                findStoredNarmestelederbehov(row.id) shouldBe row
            }
        }

        test("returns at most the oldest 100 eligible behov") {
            // Insert newest first to prove ordering takes place before limiting.
            val rows = (1..105).map { seconds ->
                insertNarmestelederbehov(created = cutoff.minusSeconds(seconds.toLong()))
            }

            lifecycleRepository.findPendingDialogs(cutoff, 100).map { it.id.value } shouldBe rows.reversed().take(100).map { it.id }
        }

        test("returns no pending dialogs when the table is empty") {
            lifecycleRepository.findPendingDialogs(cutoff, 100) shouldBe emptyList()
        }

        test("fulfilled query requires a dialog and strict age cutoff, orders oldest first and leaves rows unchanged") {
            val newer = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED, dialogId = UUID.randomUUID(), created = cutoff.minusSeconds(1))
            val oldest = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED, dialogId = UUID.randomUUID(), created = now.minusSeconds(300))
            val excluded = listOf(
                insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED, dialogId = UUID.randomUUID(), created = cutoff),
                insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED, dialogId = UUID.randomUUID(), created = now.minusSeconds(9)),
                insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED, created = now.minusSeconds(600)),
            ) + BehovStatus.entries.filterNot { it == BehovStatus.BEHOV_FULFILLED }.map { status ->
                insertNarmestelederbehov(behovStatus = status, dialogId = UUID.randomUUID(), created = now.minusSeconds(600))
            }

            fulfillmentRepository.findFulfilledDialogs(cutoff) shouldBe listOf(oldest, newer).map { it.dialogReference() }
            (excluded + oldest + newer).forEach { findStoredNarmestelederbehov(it.id) shouldBe it }
        }

        test("expiry query selects only unmarked expired and error dialogs with no age filter, oldest first") {
            val recent = insertNarmestelederbehov(behovStatus = BehovStatus.ERROR, dialogId = UUID.randomUUID(), created = now)
            val oldest = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_EXPIRED, dialogId = UUID.randomUUID(), created = now.minusSeconds(300))
            val excluded = listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR).flatMap { status ->
                listOf(
                    insertNarmestelederbehov(behovStatus = status, dialogId = UUID.randomUUID(), expiredInDialogporten = now, created = now.minusSeconds(600)),
                    insertNarmestelederbehov(behovStatus = status, created = now.minusSeconds(600)),
                )
            } + BehovStatus.entries.filterNot { it in listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR) }.map { status ->
                insertNarmestelederbehov(behovStatus = status, dialogId = UUID.randomUUID(), created = now.minusSeconds(600))
            }

            lifecycleRepository.findDialogsToExpire() shouldBe listOf(oldest, recent).map { it.dialogReference() }
            (excluded + oldest + recent).forEach { findStoredNarmestelederbehov(it.id) shouldBe it }
        }

        listOf(BehovStatus.BEHOV_FULFILLED, BehovStatus.BEHOV_EXPIRED).forEach { status ->
            test("$status query limits to the oldest 100 eligible dialogs") {
                val rows = (1..105).map { seconds ->
                    insertNarmestelederbehov(behovStatus = status, dialogId = UUID.randomUUID(), created = cutoff.minusSeconds(seconds.toLong()))
                }

                val result = if (status == BehovStatus.BEHOV_FULFILLED) {
                    fulfillmentRepository.findFulfilledDialogs(cutoff)
                } else {
                    lifecycleRepository.findDialogsToExpire()
                }

                result shouldBe rows.reversed().take(100).map { it.dialogReference() }
            }
        }

        test("marking expiry touches only the marker and trigger-managed updated timestamp, not other columns or rows") {
            val row = insertNarmestelederbehov(
                behovStatus = BehovStatus.ERROR,
                dialogId = UUID.randomUUID(),
                fornavn = "Synthetic",
                mellomnavn = "Middle",
                etternavn = "Employee",
                avbruttNarmestelederId = UUID.randomUUID(),
                created = now.minusSeconds(300),
            )
            val other = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_EXPIRED, dialogId = UUID.randomUUID())
            val expirationTime = now.plusSeconds(600)

            lifecycleRepository.markDialogExpired(NarmestelederbehovId(row.id), expirationTime)

            val stored = requireNotNull(findStoredNarmestelederbehov(row.id))
            stored shouldBe row.copy(expiredInDialogporten = expirationTime, updated = stored.updated)
            stored.updated.isAfter(row.updated) shouldBe true
            findStoredNarmestelederbehov(other.id) shouldBe other
            lifecycleRepository.findDialogsToExpire() shouldBe listOf(other.dialogReference())
        }

        test("queries return empty lists when no dialogs exist and marking a missing row is harmless") {
            fulfillmentRepository.findFulfilledDialogs(cutoff) shouldBe emptyList()
            lifecycleRepository.findDialogsToExpire() shouldBe emptyList()
            lifecycleRepository.markDialogExpired(NarmestelederbehovId(UUID.randomUUID()), now)
            lifecycleRepository.findDialogsToExpire() shouldBe emptyList()
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
            val result = requireNotNull(readRepository.findDetails(id))
            result.id shouldBe id
            result.employeeIdent shouldBe PersonalIdentificationNumber(row.sykmeldtFnr)
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
            readRepository.findDetails(NarmestelederbehovId(UUID.randomUUID())).shouldBeNull()
            findStoredNarmestelederbehov(row.id) shouldBe row
        }

        test("saves only the employee name columns") {
            val before = insertNarmestelederbehov()
            val id = NarmestelederbehovId(before.id)

            readRepository.saveEmployeeName(
                id = id,
                name = BehovPersonName(firstName = "First", middleName = "Middle", lastName = "Last"),
            )

            with(requireNotNull(findStoredNarmestelederbehov(before.id))) {
                fornavn shouldBe "First"
                mellomnavn shouldBe "Middle"
                etternavn shouldBe "Last"
                copy(fornavn = null, mellomnavn = null, etternavn = null, updated = before.updated) shouldBe before
            }
        }

        test("finds a behov for fulfillment and returns null for missing id") {
            val row = insertNarmestelederbehov()
            val id = NarmestelederbehovId(row.id)

            fulfillmentRepository.findForFulfillment(id) shouldBe Narmestelederbehov(
                id,
                Employee(PersonalIdentificationNumber(row.sykmeldtFnr), OrganizationNumber(row.orgnummer)),
            )
            fulfillmentRepository.findForFulfillment(NarmestelederbehovId(UUID.randomUUID())).shouldBeNull()
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

            fulfillmentRepository.markFulfilled(id) shouldBe MarkFulfilledResult.Marked(id, dialogId)

            with(requireNotNull(findStoredNarmestelederbehov(before.id))) {
                behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
            }
        }

        test("marks a behov with no dialog and returns Missing for an absent row") {
            val id = NarmestelederbehovId(insertNarmestelederbehov().id)

            fulfillmentRepository.markFulfilled(id) shouldBe MarkFulfilledResult.Marked(id, null)
            fulfillmentRepository.markFulfilled(NarmestelederbehovId(UUID.randomUUID())) shouldBe MarkFulfilledResult.Missing
        }

        test("marks a completed dialog status without changing other fields") {
            val before = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED)
            val id = NarmestelederbehovId(before.id)

            fulfillmentRepository.markDialogCompleted(id) shouldBe MarkDialogCompletedResult.Marked

            with(requireNotNull(findStoredNarmestelederbehov(before.id))) {
                behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
            }
        }

        test("does not overwrite a status changed by another writer") {
            val before = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_EXPIRED)

            fulfillmentRepository.markDialogCompleted(NarmestelederbehovId(before.id)) shouldBe MarkDialogCompletedResult.NotFulfilled

            findStoredNarmestelederbehov(before.id) shouldBe before
        }

        test("reports NotFulfilled for a missing row when persisting completed dialog status") {
            fulfillmentRepository.markDialogCompleted(NarmestelederbehovId(UUID.randomUUID())) shouldBe MarkDialogCompletedResult.NotFulfilled
        }

        test("marks a created dialog with dialog id and employee name without changing other fields") {
            val before = insertNarmestelederbehov()
            val id = NarmestelederbehovId(before.id)
            val createdDialogId = UUID.randomUUID()

            lifecycleRepository.markDialogCreated(id, createdDialogId, BehovPersonName("First", "Middle", "Last")) shouldBe
                MarkDialogCreatedResult.Marked

            with(requireNotNull(findStoredNarmestelederbehov(before.id))) {
                behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                dialogId shouldBe createdDialogId
                fornavn shouldBe "First"
                mellomnavn shouldBe "Middle"
                etternavn shouldBe "Last"
                copy(
                    behovStatus = before.behovStatus,
                    dialogId = null,
                    fornavn = null,
                    mellomnavn = null,
                    etternavn = null,
                    updated = before.updated,
                ) shouldBe before
            }
        }

        test("marks a created dialog without employee name") {
            val row = insertNarmestelederbehov()
            val id = NarmestelederbehovId(row.id)

            lifecycleRepository.markDialogCreated(id, UUID.randomUUID(), null) shouldBe MarkDialogCreatedResult.Marked

            with(requireNotNull(findStoredNarmestelederbehov(row.id))) {
                behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                fornavn.shouldBeNull()
                etternavn.shouldBeNull()
            }
        }

        test("does not mark a created dialog when the behov has left BEHOV_CREATED or is missing") {
            val before = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED)
            val id = NarmestelederbehovId(before.id)

            lifecycleRepository.markDialogCreated(id, UUID.randomUUID(), null) shouldBe MarkDialogCreatedResult.NotCreated
            lifecycleRepository.markDialogCreated(NarmestelederbehovId(UUID.randomUUID()), UUID.randomUUID(), null) shouldBe
                MarkDialogCreatedResult.NotCreated

            findStoredNarmestelederbehov(before.id) shouldBe before
        }

        // An employee can have at most one open behov per organization (uq_nl_behov_active_employee_org).
        val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
        openStatuses.forEach { openStatus ->
            test("open behov for an employee match person, organization and status $openStatus") {
                val employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("910000001"))
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

                lifecycleRepository.findOpenFor(employee).map { it.value } shouldBe listOf(open)
            }
        }
        val newBehov = NewNarmestelederbehov(
            employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("910000001")),
            mainOrganizationNumber = "910000009",
            manager = PersonalIdentificationNumber("10987654321"),
            reason = BehovReason.DEAKTIVERT_LEDER,
            status = BehovStatus.BEHOV_CREATED,
            revokedRelationId = UUID.fromString("00000000-0000-0000-0000-000000000044"),
        )

        test("creates a behov with every column") {
            val result = lifecycleRepository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()

            with(requireNotNull(findStoredNarmestelederbehov(result.id.value))) {
                sykmeldtFnr shouldBe "12345678901"
                orgnummer shouldBe "910000001"
                hovedenhetOrgnummer shouldBe "910000009"
                narmestelederFnr shouldBe "10987654321"
                behovReason shouldBe BehovReason.DEAKTIVERT_LEDER
                behovStatus shouldBe BehovStatus.BEHOV_CREATED
                avbruttNarmestelederId shouldBe newBehov.revokedRelationId
                dialogId.shouldBeNull()
                fornavn.shouldBeNull()
            }
        }

        test("creates a behov without manager or revoked relation") {
            val result = lifecycleRepository.create(newBehov.copy(manager = null, revokedRelationId = null))
                .shouldBeInstanceOf<CreateBehovResult.Created>()

            with(requireNotNull(findStoredNarmestelederbehov(result.id.value))) {
                narmestelederFnr.shouldBeNull()
                avbruttNarmestelederId.shouldBeNull()
            }
        }

        test("reports AlreadyExists without storing when the employee has an open behov in the organization") {
            val existing = lifecycleRepository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()

            lifecycleRepository.create(newBehov.copy(status = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)) shouldBe CreateBehovResult.AlreadyExists

            lifecycleRepository.findOpenFor(newBehov.employee) shouldBe listOf(existing.id)
        }

        test("stores exactly one open behov when concurrent creates race") {
            val results = coroutineScope {
                (1..2).map { async { lifecycleRepository.create(newBehov) } }.awaitAll()
            }

            results.count { it is CreateBehovResult.Created } shouldBe 1
            results.count { it == CreateBehovResult.AlreadyExists } shouldBe 1
            lifecycleRepository.findOpenFor(newBehov.employee).size shouldBe 1
        }

        test("creates a behov alongside closed and error behov for the same employee and organization") {
            lifecycleRepository.create(newBehov.copy(status = BehovStatus.ARBEIDSFORHOLD_NOT_FOUND)).shouldBeInstanceOf<CreateBehovResult.Created>()
            val closed = lifecycleRepository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()
            fulfillmentRepository.markFulfilled(closed.id)

            lifecycleRepository.create(newBehov).shouldBeInstanceOf<CreateBehovResult.Created>()
        }
    })

private fun StoredNarmestelederbehov.dialogReference() = NarmestelederbehovDialogReference(NarmestelederbehovId(id), requireNotNull(dialogId))
