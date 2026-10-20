package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.PendingNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

class ExposedPendingNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedPendingNarmestelederbehovRepository(TestDB.exposedDatabase)
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

            repository.findPendingDialogs(cutoff, 100) shouldBe listOf(oldest, newer).map { row ->
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

            repository.findPendingDialogs(cutoff, 100).map { it.id.value } shouldBe rows.reversed().take(100).map { it.id }
        }

        test("returns no pending dialogs when the table is empty") {
            repository.findPendingDialogs(cutoff, 100) shouldBe emptyList()
        }
    })
