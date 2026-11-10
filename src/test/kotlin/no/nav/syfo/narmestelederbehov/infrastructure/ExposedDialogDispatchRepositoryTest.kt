package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.CreateBehovResult
import no.nav.syfo.narmestelederbehov.application.DialogDispatchFailure
import no.nav.syfo.narmestelederbehov.application.DialogDispatchResult
import no.nav.syfo.narmestelederbehov.application.DialogDispatchStats
import no.nav.syfo.narmestelederbehov.application.MarkDialogCreatedResult
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ExposedDialogDispatchRepositoryTest :
    FunSpec({
        val repository = ExposedDialogDispatchRepository(TestDB.exposedDatabase)
        val now = Instant.parse("2030-01-01T10:00:00Z")
        beforeTest { TestDB.clearAllData() }

        fun newBehov(status: BehovStatus = BehovStatus.BEHOV_CREATED) = NewNarmestelederbehov(
            employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("123456789")),
            mainOrganizationNumber = "123456789",
            manager = null,
            reason = BehovReason.DEAKTIVERT_NY_LEDER,
            status = status,
            revokedRelationId = null,
        )

        test("repository creation delays dispatch ten seconds and a duplicate does not wake acknowledged work") {
            val lifecycle = ExposedNarmestelederbehovLifecycleRepository(TestDB.exposedDatabase)
            val created = lifecycle.create(newBehov()).shouldBeInstanceOf<CreateBehovResult.Created>()
            val stored = requireNotNull(findStoredNarmestelederbehov(created.id.value))
            repository.claim(stored.created.plusSeconds(9), 60.seconds).shouldBeNull()
            val claim = requireNotNull(repository.claim(now, 60.seconds))
            claim.id shouldBe created.id
            repository.finish(claim, DialogDispatchResult.Done, now) shouldBe true

            lifecycle.create(newBehov()) shouldBe CreateBehovResult.AlreadyExists
            lifecycle.create(newBehov(BehovStatus.HOVEDENHET_NOT_FOUND)).shouldBeInstanceOf<CreateBehovResult.Created>()
            repository.claim(now, 60.seconds).shouldBeNull()
        }

        test("a dispatch scheduling failure rolls back repository creation") {
            val failingLifecycle = ExposedNarmestelederbehovLifecycleRepository(TestDB.exposedDatabase) { id ->
                scheduleDialogDispatch(id)
                error("dispatch scheduling failed")
            }
            shouldThrow<IllegalStateException> { failingLifecycle.create(newBehov()) }
            transaction(TestDB.exposedDatabase) {
                assertSoftly {
                    NarmestelederbehovTable.selectAll().count() shouldBe 0
                    DialogDispatchTable.selectAll().count() shouldBe 0
                }
            }
        }

        listOf(BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION, BehovStatus.BEHOV_FULFILLED).forEach { status ->
            test("a writer setting $status preserves a concurrently committed claim without transaction retries") {
                val row = insertNarmestelederbehov(created = now.minusSeconds(30))
                val worker = Executors.newSingleThreadExecutor()
                val writerDatabase = Database.connect(
                    getNewConnection = { TestDB.database.connection },
                    databaseConfig = DatabaseConfig {
                        defaultIsolationLevel = Connection.TRANSACTION_REPEATABLE_READ
                        defaultMaxAttempts = 1
                    },
                )
                try {
                    val schedule: JdbcTransaction.(UUID) -> Unit = { id ->
                        worker.submit<Boolean> {
                            runBlocking { requireNotNull(repository.claim(now, 60.seconds)).id.value == id }
                        }.get(10, TimeUnit.SECONDS) shouldBe true
                        scheduleDialogDispatch(id)
                    }
                    val id = NarmestelederbehovId(row.id)
                    if (status == BehovStatus.BEHOV_FULFILLED) {
                        ExposedNarmestelederbehovFulfillmentRepository(writerDatabase, schedule).markFulfilled(id)
                    } else {
                        ExposedNarmestelederbehovLifecycleRepository(writerDatabase, schedule)
                            .markDialogCreated(id, UUID.randomUUID(), null) shouldBe MarkDialogCreatedResult.Marked
                    }
                    repository.claim(now, 60.seconds).shouldBeNull()
                    val reclaimed = requireNotNull(repository.claim(now.plusSeconds(61), 60.seconds))
                    reclaimed.revision shouldBe 2L
                    requireNotNull(repository.load(reclaimed, now.plusSeconds(61))).status shouldBe status
                } finally {
                    worker.shutdownNow()
                    TransactionManager.closeAndUnregister(writerDatabase)
                }
            }
        }

        test("saving completion acknowledges the current revision without scheduling another claim") {
            val row = insertNarmestelederbehov(
                created = now.minusSeconds(30),
                behovStatus = BehovStatus.BEHOV_FULFILLED,
                dialogId = UUID.randomUUID(),
            )
            val claim = requireNotNull(repository.claim(now, 60.seconds))
            repository.saveCompleted(claim, now) shouldBe true
            repository.finish(claim, DialogDispatchResult.Done, now) shouldBe true
            assertSoftly {
                findStoredNarmestelederbehov(row.id)?.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                repository.claim(now, 60.seconds).shouldBeNull()
            }
        }

        test("stats count ready, claimed and parked work and report the oldest pending change") {
            val parked = insertNarmestelederbehov(created = now.minusSeconds(40))
            val idle = insertNarmestelederbehov(created = now.minusSeconds(30))
            val claimed = insertNarmestelederbehov(created = now.minusSeconds(20))
            val ready = insertNarmestelederbehov(created = now.minusSeconds(10))
            suspend fun claimNext(expected: UUID) = requireNotNull(repository.claim(now, 60.seconds)).also { it.id.value shouldBe expected }
            repository.finish(claimNext(parked.id), DialogDispatchResult.Parked(DialogDispatchFailure.INVALID_REQUEST), now) shouldBe true
            repository.finish(claimNext(idle.id), DialogDispatchResult.Done, now) shouldBe true
            claimNext(claimed.id)
            val oldestPending = Instant.parse("2029-03-01T00:00:00Z")
            transaction(TestDB.exposedDatabase) {
                mapOf(
                    parked.id to Instant.parse("2020-01-01T00:00:00Z"),
                    claimed.id to oldestPending,
                    ready.id to Instant.parse("2029-06-01T00:00:00Z"),
                ).forEach { (id, pendingSince) ->
                    DialogDispatchTable.update({ DialogDispatchTable.behovId eq id }) { it[DialogDispatchTable.pendingSince] = pendingSince.atOffset(ZoneOffset.UTC) }
                }
            }

            repository.stats() shouldBe DialogDispatchStats(pending = 2, parked = 1, oldestPending = oldestPending)
        }

        test("committed behov changes enqueue once and concurrent workers claim different rows") {
            val first = insertNarmestelederbehov(created = now.minusSeconds(30))
            val second = insertNarmestelederbehov(created = now.minusSeconds(20))
            val claims = coroutineScope {
                listOf(async { repository.claim(now, 60.seconds) }, async { repository.claim(now, 60.seconds) })
                    .map { requireNotNull(it.await()) }
            }
            assertSoftly {
                claims.map { it.id.value }.toSet() shouldBe setOf(first.id, second.id)
                repository.claim(now, 60.seconds).shouldBeNull()
            }
        }

        test("an expired lease is reclaimed and an old token cannot acknowledge or start creation") {
            insertNarmestelederbehov(created = now.minusSeconds(30))
            val old = requireNotNull(repository.claim(now, 60.seconds))
            val replacement = requireNotNull(repository.claim(now.plusSeconds(61), 60.seconds))
            assertSoftly {
                repository.beginCreation(old, now.plusSeconds(62)) shouldBe false
                repository.saveCreated(old, UUID.randomUUID(), null, now.plusSeconds(62)) shouldBe false
                repository.saveCompleted(old, now.plusSeconds(62)) shouldBe false
                repository.saveExpired(old, now, now.plusSeconds(62)) shouldBe false
                repository.finish(old, DialogDispatchResult.Done, now.plusSeconds(62)) shouldBe false
                repository.load(replacement, now.plusSeconds(62)).shouldBeInstanceOf<no.nav.syfo.narmestelederbehov.application.DialogDispatchState>()
            }
        }

        test("a domain change during a claim stops the older revision from loading, starting creation or preparing expiry") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30))
            val claim = requireNotNull(repository.claim(now, 60.seconds))
            ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
            assertSoftly {
                repository.load(claim, now.plusSeconds(1)).shouldBeNull()
                repository.beginCreation(claim, now.plusSeconds(1)) shouldBe false
                repository.prepareExpiry(claim, now.plusSeconds(3600), now.plusSeconds(1)) shouldBe false
                // The lease is still owned, so the refusals above come from the revision guard.
                repository.finish(claim, DialogDispatchResult.Done, now.plusSeconds(1)) shouldBe true
            }
        }

        listOf(
            DialogDispatchResult.Done,
            DialogDispatchResult.Retry(now.plusSeconds(600), DialogDispatchFailure.UPSTREAM),
            DialogDispatchResult.Parked(DialogDispatchFailure.INVALID_REQUEST),
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

        test("repeated fulfillment does not supersede a claim for an unchanged behov") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30))
            val fulfillment = ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase)
            fulfillment.markFulfilled(NarmestelederbehovId(row.id))
            val claim = requireNotNull(repository.claim(now, 60.seconds))
            fulfillment.markFulfilled(NarmestelederbehovId(row.id))
            repository.finish(claim, DialogDispatchResult.Done, now) shouldBe true
            repository.claim(now, 60.seconds).shouldBeNull()
        }

        test("rollback removes scheduled work and closed legacy rows without dialogs stay excluded") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30), behovStatus = BehovStatus.HOVEDENHET_NOT_FOUND)
            transaction(TestDB.exposedDatabase) {
                NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq row.id }) {
                    it[behovStatus] = BehovStatus.BEHOV_CREATED
                }
                scheduleDialogDispatch(row.id)
                DialogDispatchTable.selectAll().count() shouldBe 1
                rollback()
            }
            assertSoftly {
                findStoredNarmestelederbehov(row.id)?.behovStatus shouldBe BehovStatus.HOVEDENHET_NOT_FOUND
                repository.claim(now, 60.seconds).shouldBeNull()
            }
            ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
            // A closed legacy row without a dialog does not get automatic CREATE recovery.
            repository.claim(now, 60.seconds).shouldBeNull()
        }
    })
