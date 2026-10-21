package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogReference
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

class ExposedNarmestelederbehovDialogLifecycleRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovDialogLifecycleRepository(TestDB.exposedDatabase)
        val now = Instant.parse("2026-10-01T10:00:00Z")
        val cutoff = now.minusSeconds(10)

        beforeTest {
            TestDB.clearAllData()
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

            repository.findFulfilledDialogs(cutoff) shouldBe listOf(oldest, newer).map { it.dialogReference() }
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

            repository.findDialogsToExpire() shouldBe listOf(oldest, recent).map { it.dialogReference() }
            (excluded + oldest + recent).forEach { findStoredNarmestelederbehov(it.id) shouldBe it }
        }

        listOf(BehovStatus.BEHOV_FULFILLED, BehovStatus.BEHOV_EXPIRED).forEach { status ->
            test("$status query limits to the oldest 100 eligible dialogs") {
                val rows = (1..105).map { seconds ->
                    insertNarmestelederbehov(behovStatus = status, dialogId = UUID.randomUUID(), created = cutoff.minusSeconds(seconds.toLong()))
                }

                val result = if (status == BehovStatus.BEHOV_FULFILLED) repository.findFulfilledDialogs(cutoff) else repository.findDialogsToExpire()

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

            repository.markDialogExpired(NarmestelederbehovId(row.id), expirationTime)

            val stored = requireNotNull(findStoredNarmestelederbehov(row.id))
            stored shouldBe row.copy(expiredInDialogporten = expirationTime, updated = stored.updated)
            stored.updated.isAfter(row.updated) shouldBe true
            findStoredNarmestelederbehov(other.id) shouldBe other
            repository.findDialogsToExpire() shouldBe listOf(other.dialogReference())
        }

        test("queries return empty lists when no dialogs exist and marking a missing row is harmless") {
            repository.findFulfilledDialogs(cutoff) shouldBe emptyList()
            repository.findDialogsToExpire() shouldBe emptyList()
            repository.markDialogExpired(NarmestelederbehovId(UUID.randomUUID()), now)
            repository.findDialogsToExpire() shouldBe emptyList()
        }
    })

private fun StoredNarmestelederbehov.dialogReference() = NarmestelederbehovDialogReference(NarmestelederbehovId(id), requireNotNull(dialogId))
