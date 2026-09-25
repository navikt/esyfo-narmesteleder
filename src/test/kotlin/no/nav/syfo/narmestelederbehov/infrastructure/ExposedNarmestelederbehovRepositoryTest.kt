package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

class ExposedNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovRepository(TestDB.exposedDatabase)
        val setupDb = PostgresNarmestelederDb(TestDB.database)

        beforeTest { TestDB.clearAllData() }

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
            val row = setupDb.insertNlBehov(nlBehovEntity())
            val id = NarmestelederbehovId(requireNotNull(row.id))
            val before = requireNotNull(setupDb.findBehovById(id.value))

            repository.markDialogCompleted(id)

            val after = requireNotNull(setupDb.findBehovById(id.value))
            after.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
            after.copy(behovStatus = before.behovStatus, updated = before.updated) shouldBe before
        }

        test("reports missing row when persisting completed dialog status") {
            shouldThrow<IllegalStateException> {
                repository.markDialogCompleted(NarmestelederbehovId(UUID.randomUUID()))
            }
        }
    })
