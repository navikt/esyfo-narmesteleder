package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.TestDB
import no.nav.syfo.narmestelederbehov.application.DialogDispatchResult
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class ExposedDialogDispatchRepositoryTest :
    FunSpec({
        val repository = ExposedDialogDispatchRepository(TestDB.exposedDatabase)
        val now = Instant.parse("2030-01-01T10:00:00Z")
        beforeTest { TestDB.clearAllData() }

        test("committed behov changes enqueue once and concurrent workers claim different rows") {
            val first = insertNarmestelederbehov(created = now.minusSeconds(30))
            val second = insertNarmestelederbehov(created = now.minusSeconds(20))
            val claims = coroutineScope {
                listOf(async { repository.claim(now, 60.seconds) }, async { repository.claim(now, 60.seconds) })
                    .map { requireNotNull(it.await()) }
            }
            claims.map { it.id.value }.toSet() shouldBe setOf(first.id, second.id)
            repository.claim(now, 60.seconds).shouldBeNull()
        }

        test("an expired lease is reclaimed and an old token cannot acknowledge or start creation") {
            insertNarmestelederbehov(created = now.minusSeconds(30))
            val old = requireNotNull(repository.claim(now, 60.seconds))
            val replacement = requireNotNull(repository.claim(now.plusSeconds(61), 60.seconds))
            repository.beginCreation(old, now.plusSeconds(62)) shouldBe false
            repository.saveCreated(old, UUID.randomUUID(), null, now.plusSeconds(62)) shouldBe false
            repository.saveCompleted(old, now.plusSeconds(62)) shouldBe false
            repository.saveExpired(old, now, now.plusSeconds(62)) shouldBe false
            repository.finish(old, DialogDispatchResult.Done, now.plusSeconds(62)) shouldBe false
            repository.load(replacement, now.plusSeconds(62)).shouldBeInstanceOf<no.nav.syfo.narmestelederbehov.application.DialogDispatchState>()
        }

        listOf(
            DialogDispatchResult.Done,
            DialogDispatchResult.Retry(now.plusSeconds(600), "upstream"),
            DialogDispatchResult.Parked("invalid_request"),
        ).forEach { oldResult ->
            test("a domain change during a claim survives $oldResult of the older revision") {
                val row = insertNarmestelederbehov(created = now.minusSeconds(30))
                val claim = requireNotNull(repository.claim(now, 60.seconds))
                ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
                repository.finish(claim, oldResult, now.plusSeconds(1)) shouldBe true
                val newer = requireNotNull(repository.claim(now.plusSeconds(2), 60.seconds))
                requireNotNull(repository.load(newer, now.plusSeconds(2))).status shouldBe BehovStatus.BEHOV_FULFILLED
            }
        }

        test("rollback removes scheduled work and closed legacy rows without dialogs stay excluded") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30), behovStatus = BehovStatus.HOVEDENHET_NOT_FOUND)
            transaction(TestDB.exposedDatabase) {
                NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq row.id }) {
                    it[behovStatus] = BehovStatus.BEHOV_CREATED
                }
                rollback()
            }
            repository.claim(now, 60.seconds).shouldBeNull()
            ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
            // A closed legacy row without a dialog does not get automatic CREATE recovery.
            repository.claim(now, 60.seconds).shouldBeNull()
        }
    })
