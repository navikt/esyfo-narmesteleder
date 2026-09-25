package no.nav.syfo.narmestelederbehov.infrastructure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.DialogportenCompletionAttempt
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.slf4j.LoggerFactory
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
            val db = mockk<NarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } returns behov

            DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id) shouldBe
                DialogportenCompletionAttempt.NotApplicable
            coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
        }

        test("lookup failure stays retryable") {
            val db = mockk<NarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } throws IllegalStateException("private-exception-canary")
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(DialogportenNarmestelederbehovDialog::class.java) as Logger
            val previousLevel = logger.level
            logger.level = Level.WARN
            logger.addAppender(appender)

            try {
                DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id) shouldBe
                    DialogportenCompletionAttempt.Failed
                coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
                val event = appender.list.single()
                val fields = event.keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "narmestelederbehov_dialogporten_completion_failed"
                fields["behov_id"] shouldBe id.value.toString()
                fields["failure_kind"] shouldBe "unknown"
                event.throwableProxy.message shouldBe "java.lang.IllegalStateException"
                (event.formattedMessage + fields.toString() + event.throwableProxy.message)
                    .contains("private-exception-canary") shouldBe false
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("missing behov is not applicable") {
            val db = mockk<NarmestelederDb>()
            val service = mockk<DialogportenService>()
            coEvery { db.findBehovById(id.value) } returns null

            DialogportenNarmestelederbehovDialog(db, service).attemptCompletion(id) shouldBe
                DialogportenCompletionAttempt.NotApplicable
            coVerify(exactly = 0) { service.completeFulfilledDialog(any()) }
        }

        test("lookup cancellation propagates") {
            val db = mockk<NarmestelederDb>()
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
                val db = mockk<NarmestelederDb>()
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
