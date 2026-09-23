package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.DialogportenCompletionAttempt
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

class DialogportenNarmestelederbehovDialogTest :
    FunSpec({
        val id = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val behov = NarmestelederBehovEntity(
            id = id.value,
            orgnummer = "123456789",
            hovedenhetOrgnummer = "123456789",
            sykmeldtFnr = "12345678901",
            behovReason = BehovReason.DEAKTIVERT_LEDER,
            behovStatus = BehovStatus.BEHOV_FULFILLED,
        )

        test("does nothing when no dialog exists") {
            val db = mockk<INarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } returns behov

            DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id) shouldBe
                DialogportenCompletionAttempt.NotApplicable
            coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
        }

        test("lookup failure stays retryable") {
            val db = mockk<INarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } throws IllegalStateException("lookup failed")

            DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id) shouldBe
                DialogportenCompletionAttempt.Failed
            coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
        }

        test("missing behov is not applicable") {
            val db = mockk<INarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } returns null

            DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id) shouldBe
                DialogportenCompletionAttempt.NotApplicable
            coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
        }

        test("lookup cancellation propagates") {
            val db = mockk<INarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> {
                DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id)
            }
            coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
        }

        listOf(
            IllegalStateException("failed") to DialogportenCompletionAttempt.Failed,
            CancellationException("cancelled") to null,
        ).forEach { (failure, result) ->
            test("completion ${failure::class.simpleName} ${if (result == null) "propagates" else "stays retryable"}") {
                val db = mockk<INarmestelederDb>()
                val service = mockk<DialogportenService>()
                val withDialog = behov.copy(dialogId = UUID.fromString("00000000-0000-0000-0000-000000000002"))
                coEvery { db.findBehovById(id.value) } returns withDialog
                coEvery { service.completeFulfilledDialog(withDialog) } throws failure

                val adapter = DialogportenNarmestelederbehovDialog(db, service)
                if (result == null) {
                    shouldThrow<CancellationException> { adapter.attemptCompletion(id) }
                } else {
                    adapter.attemptCompletion(id) shouldBe result
                }
                coVerify(exactly = 0) { db.updateNlBehov(any()) }
            }
        }
    })
