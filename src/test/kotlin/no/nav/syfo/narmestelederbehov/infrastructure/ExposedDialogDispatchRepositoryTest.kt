package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.CreateBehovResult
import no.nav.syfo.narmestelederbehov.application.DialogCompletion
import no.nav.syfo.narmestelederbehov.application.DialogDispatchFailure
import no.nav.syfo.narmestelederbehov.application.DialogDispatchMetrics
import no.nav.syfo.narmestelederbehov.application.DialogDispatchOutcome
import no.nav.syfo.narmestelederbehov.application.DialogDispatchResult
import no.nav.syfo.narmestelederbehov.application.DialogDispatchSettings
import no.nav.syfo.narmestelederbehov.application.DialogDispatchState
import no.nav.syfo.narmestelederbehov.application.DialogDispatchStats
import no.nav.syfo.narmestelederbehov.application.DialogExpiry
import no.nav.syfo.narmestelederbehov.application.DialogExpiryInspection
import no.nav.syfo.narmestelederbehov.application.DialogExpiryObservation
import no.nav.syfo.narmestelederbehov.application.DispatchNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookup
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreationResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogExpiry
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DIALOGPORTEN
import no.nav.syfo.narmestelederrelasjon.infrastructure.insertRelation
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

class ExposedDialogDispatchRepositoryTest :
    FunSpec({
        val repository = ExposedDialogDispatchRepository(TestDB.exposedDatabase)
        val now = Instant.parse("2030-01-01T10:00:00Z")
        val fixedClock = object : Clock {
            override fun now() = now.toKotlinInstant()
        }
        beforeTest { TestDB.clearAllData() }

        fun newBehov(status: BehovStatus = BehovStatus.BEHOV_CREATED) = NewNarmestelederbehov(
            employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("123456789")),
            mainOrganizationNumber = "123456789",
            manager = null,
            reason = BehovReason.DEAKTIVERT_NY_LEDER,
            status = status,
            revokedRelationId = null,
        )

        fun dispatcher(
            dialogCreation: NarmestelederbehovDialogCreation = NarmestelederbehovDialogCreation { _, _, _ -> error("must not create") },
            dialogCompletion: NarmestelederbehovDialog = NarmestelederbehovDialog { error("must not complete") },
            dialogExpiry: NarmestelederbehovDialogExpiry = NarmestelederbehovDialogExpiry { _, _ -> error("must not expire") },
            dialogExpiryInspection: DialogExpiryInspection = DialogExpiryInspection { error("must not inspect") },
            employeePersonLookup: EmployeePersonLookup = EmployeePersonLookup { EmployeePersonLookupResult.NotFound },
            clock: Clock = fixedClock,
            dispatchMetrics: DialogDispatchMetrics = DialogDispatchMetrics {},
            settings: DialogDispatchSettings = DialogDispatchSettings(),
        ) = DispatchNarmestelederbehovDialogUseCase(
            dispatchQueue = repository,
            dispatchWork = repository,
            dialogCreation = dialogCreation,
            dialogCompletion = dialogCompletion,
            dialogExpiry = dialogExpiry,
            dialogExpiryInspection = dialogExpiryInspection,
            employeePersonLookup = employeePersonLookup,
            clock = clock,
            dispatchMetrics = dispatchMetrics,
            settings = settings,
        )

        test("repository creation delays dispatch ten seconds and a duplicate does not wake acknowledged work") {
            val lifecycle = ExposedNarmestelederbehovLifecycleRepository(TestDB.exposedDatabase)
            val created = lifecycle.create(newBehov()).shouldBeInstanceOf<CreateBehovResult.Created>()
            val stored = requireNotNull(findStoredNarmestelederbehov(created.id.value))
            repository.claim(stored.created.plusSeconds(9), 60.seconds).shouldBeNull()
            val claim = requireNotNull(repository.claim(stored.created.plusSeconds(10), 60.seconds))
            claim.id shouldBe created.id
            repository.finish(claim, DialogDispatchResult.Done, stored.created.plusSeconds(10)) shouldBe true

            assertSoftly {
                lifecycle.create(newBehov()) shouldBe CreateBehovResult.AlreadyExists
                lifecycle.create(newBehov(BehovStatus.HOVEDENHET_NOT_FOUND)).shouldBeInstanceOf<CreateBehovResult.Created>()
                repository.claim(now, 60.seconds).shouldBeNull()
            }
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

        test("a fulfillment writer preserves a concurrently committed claim without transaction retries") {
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
                ExposedNarmestelederbehovFulfillmentRepository(writerDatabase, schedule).markFulfilled(NarmestelederbehovId(row.id))
                repository.claim(now, 60.seconds).shouldBeNull()
                val reclaimed = requireNotNull(repository.claim(now.plusSeconds(61), 60.seconds))
                assertSoftly {
                    reclaimed.revision shouldBe 2L
                    requireNotNull(repository.load(reclaimed, now.plusSeconds(61))).status shouldBe BehovStatus.BEHOV_FULFILLED
                }
            } finally {
                worker.shutdownNow()
                TransactionManager.closeAndUnregister(writerDatabase)
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
                repository.load(replacement, now.plusSeconds(62)).shouldBeInstanceOf<DialogDispatchState>()
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
                val acknowledged = repository.finish(claim, oldResult, now.plusSeconds(1))
                val newer = requireNotNull(repository.claim(now.plusSeconds(2), 60.seconds))
                assertSoftly {
                    acknowledged shouldBe true
                    requireNotNull(repository.load(newer, now.plusSeconds(2))).status shouldBe BehovStatus.BEHOV_FULFILLED
                }
            }
        }

        test("a closed behov without a started CREATE is acknowledged without external delivery") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30))
            ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
            dispatcher(
                dialogCreation = { _, _, _ -> error("must not create a closed unattempted behov") },
                employeePersonLookup = { error("must not look up a person") },
            ).execute()
            assertSoftly {
                repository.stats().pending shouldBe 0
                findStoredNarmestelederbehov(row.id)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
            }
        }

        test("a timed-out creation retains uncertainty and backs off without starving another due behov") {
            val first = insertNarmestelederbehov(created = now.minusSeconds(30))
            val second = insertNarmestelederbehov(created = now.minusSeconds(20))
            val calls = mutableListOf<UUID>()
            val dialogId = UUID.randomUUID()
            dispatcher(
                dialogCreation = { id, _, _ ->
                    calls += id.value
                    if (id.value == first.id) awaitCancellation()
                    NarmestelederbehovDialogCreationResult.Created(dialogId)
                },
                settings = DialogDispatchSettings(
                    acknowledgementBudget = 100.milliseconds,
                    attemptBudget = 200.milliseconds,
                    runBudget = 3.seconds,
                ),
            ).execute()
            val timedOut = requireNotNull(repository.claim(now.plusSeconds(6), 60.seconds))
            val state = requireNotNull(repository.load(timedOut, now.plusSeconds(6)))
            assertSoftly {
                calls shouldBe listOf(first.id, second.id)
                findStoredNarmestelederbehov(second.id)?.dialogId shouldBe dialogId
                timedOut.id.value shouldBe first.id
                state.createStarted shouldBe true
            }
        }

        test("a late CREATE is saved after fulfillment and the next revision completes the recovered dialog") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30))
            val dialogId = UUID.randomUUID()
            val completed = mutableListOf<UUID>()
            dispatcher(
                dialogCreation = { _, _, _ ->
                    ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
                    NarmestelederbehovDialogCreationResult.Created(dialogId)
                },
                dialogCompletion = { id ->
                    completed.add(id)
                    DialogCompletion.Completed
                },
                dialogExpiry = { _, _ -> error("must not expire a fulfilled dialog") },
            ).execute()
            assertSoftly {
                completed shouldBe listOf(dialogId)
                findStoredNarmestelederbehov(row.id)?.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                repository.claim(now, 60.seconds).shouldBeNull()
            }
        }

        test("an expired behov without an expiry intent is patched once without inspection and recorded as expired") {
            val dialogId = UUID.randomUUID()
            val row = insertNarmestelederbehov(
                created = now.minusSeconds(300),
                behovStatus = BehovStatus.BEHOV_EXPIRED,
                dialogId = dialogId,
            )
            val patches = mutableListOf<Pair<UUID, Instant>>()
            val outcomes = mutableListOf<DialogDispatchOutcome>()
            dispatcher(
                dialogExpiry = { id, target ->
                    patches += id to target
                    DialogExpiry.Expired
                },
                dispatchMetrics = { outcomes += it },
            ).execute()
            val expiry = Instant.parse("2030-01-01T10:10:00Z")
            assertSoftly {
                patches shouldBe listOf(dialogId to expiry)
                findStoredNarmestelederbehov(row.id)?.expiredInDialogporten shouldBe expiry
                outcomes shouldBe listOf(DialogDispatchOutcome.DONE)
                repository.stats().pending shouldBe 0
            }
        }

        test("a persisted expiry that passed is recognized without postponing it") {
            val target = now.minusSeconds(60)
            val row = insertNarmestelederbehov(
                created = now.minusSeconds(300),
                behovStatus = BehovStatus.BEHOV_EXPIRED,
                dialogId = UUID.randomUUID(),
            )
            val claim = requireNotNull(repository.claim(now, 60.seconds))
            repository.prepareExpiry(claim, target, now) shouldBe true
            repository.finish(claim, DialogDispatchResult.Retry(now, DialogDispatchFailure.TIMEOUT), now) shouldBe true
            dispatcher(
                dialogExpiry = { _, _ -> error("must not postpone an applied expiry") },
                dialogExpiryInspection = { DialogExpiryObservation.Found(target.toKotlinInstant()) },
                settings = DialogDispatchSettings(maxAttemptsPerRun = 1),
            ).execute()
            findStoredNarmestelederbehov(row.id)?.expiredInDialogporten shouldBe target
        }

        listOf(-60L, 1L).forEach { secondsUntilExpiry ->
            test("an unapplied expiry $secondsUntilExpiry seconds from now gets a stable valid intent") {
                val row = insertNarmestelederbehov(
                    created = now.minusSeconds(300),
                    behovStatus = BehovStatus.BEHOV_EXPIRED,
                    dialogId = UUID.randomUUID(),
                )
                val claim = requireNotNull(repository.claim(now, 60.seconds))
                repository.prepareExpiry(claim, now.plusSeconds(secondsUntilExpiry), now) shouldBe true
                repository.finish(claim, DialogDispatchResult.Retry(now, DialogDispatchFailure.TIMEOUT), now) shouldBe true
                val targets = mutableListOf<Instant>()
                dispatcher(
                    dialogExpiry = { _, target ->
                        targets += target
                        DialogExpiry.Unavailable(
                            UpstreamFailure(
                                DIALOGPORTEN,
                                UpstreamFailureStage.RESPONSE,
                                503,
                                errors = listOf(UpstreamError("server_error")),
                            ),
                        )
                    },
                    dialogExpiryInspection = { DialogExpiryObservation.Found(null) },
                    clock = object : Clock {
                        override fun now() = now.plusNanos(123456789).toKotlinInstant()
                    },
                    settings = DialogDispatchSettings(maxAttemptsPerRun = 1),
                ).execute()
                val retry = requireNotNull(repository.claim(now.plusSeconds(11), 60.seconds))
                val state = requireNotNull(repository.load(retry, now.plusSeconds(11)))
                assertSoftly {
                    targets shouldBe listOf(Instant.parse("2030-01-01T10:10:00Z"))
                    state.intendedExpiry shouldBe Instant.parse("2030-01-01T10:10:00Z")
                    transaction(TestDB.exposedDatabase) {
                        DialogDispatchTable.selectAll()
                            .where { DialogDispatchTable.behovId eq row.id }
                            .single()[DialogDispatchTable.lastFailure] shouldBe DialogDispatchFailure.UPSTREAM.value
                    }
                }
            }
        }

        test("repeated fulfillment does not supersede a claim for an unchanged behov") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30))
            val fulfillment = ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase)
            fulfillment.markFulfilled(NarmestelederbehovId(row.id))
            val claim = requireNotNull(repository.claim(now, 60.seconds))
            fulfillment.markFulfilled(NarmestelederbehovId(row.id))
            assertSoftly {
                repository.finish(claim, DialogDispatchResult.Done, now) shouldBe true
                repository.claim(now, 60.seconds).shouldBeNull()
            }
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

        test("an uncertain CREATE is recovered with the same behov identity after closure") {
            val row = insertNarmestelederbehov(created = now.minusSeconds(30))
            val first = requireNotNull(repository.claim(now, 60.seconds))
            repository.beginCreation(first, now) shouldBe true
            ExposedNarmestelederbehovFulfillmentRepository(TestDB.exposedDatabase).markFulfilled(NarmestelederbehovId(row.id))
            val ids = mutableListOf<NarmestelederbehovId>()
            val dialogId = UUID.randomUUID()
            val later = now.plusSeconds(61)
            dispatcher(
                dialogCreation = { id, _, _ ->
                    ids.add(id)
                    NarmestelederbehovDialogCreationResult.Created(dialogId)
                },
                dialogCompletion = { DialogCompletion.Completed },
                clock = object : Clock {
                    override fun now() = later.toKotlinInstant()
                },
            ).execute()
            assertSoftly {
                ids shouldBe listOf(NarmestelederbehovId(row.id))
                findStoredNarmestelederbehov(row.id)?.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
            }
        }

        fun runDialogDispatchBackfill() {
            val sql = File("src/main/resources/db/migration/V35__backfill_dialog_dispatch.sql").readText()
            TestDB.database.connection.use { connection ->
                connection.createStatement().use { it.execute(sql) }
                connection.commit()
            }
        }

        suspend fun claimAllCreateStarted(): Map<UUID, Boolean?> = buildMap {
            while (true) {
                val claim = repository.claim(now, 60.seconds) ?: break
                put(claim.id.value, repository.load(claim, now)?.createStarted)
            }
        }

        test("backfill tracks a possibly attempted CREATE when it fulfils an open behov without a dialog") {
            val legacy = insertNarmestelederbehov(sykmeldtFnr = "12345678905", orgnummer = "123456789")
            insertRelation(employeeIdent = legacy.sykmeldtFnr, organizationNumber = legacy.orgnummer)
            transaction(TestDB.exposedDatabase) { DialogDispatchTable.deleteAll() }
            val scheduled = insertNarmestelederbehov(sykmeldtFnr = "12345678908", orgnummer = "123456789")
            insertRelation(employeeIdent = scheduled.sykmeldtFnr, organizationNumber = scheduled.orgnummer)
            runDialogDispatchBackfill()
            val createStarted = claimAllCreateStarted()
            assertSoftly {
                listOf(legacy, scheduled).forEach {
                    findStoredNarmestelederbehov(it.id)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                }
                createStarted shouldBe mapOf(legacy.id to true, scheduled.id to true)
            }
        }

        test("backfill is repeatable, fulfils only behov with an active relation and seeds only unfinished legacy work") {
            val matching = insertNarmestelederbehov(sykmeldtFnr = "12345678901", orgnummer = "123456789", dialogId = UUID.randomUUID())
            val attention = insertNarmestelederbehov(
                sykmeldtFnr = "12345678906",
                orgnummer = "123456789",
                behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                dialogId = UUID.randomUUID(),
            )
            val revokedId = UUID.randomUUID()
            val revoked = insertNarmestelederbehov(sykmeldtFnr = "12345678902", orgnummer = "123456789", avbruttNarmestelederId = revokedId)
            val future = insertNarmestelederbehov(sykmeldtFnr = "12345678903", orgnummer = "123456789")
            val otherOrg = insertNarmestelederbehov(sykmeldtFnr = "12345678904", orgnummer = "123456789")
            val ended = insertNarmestelederbehov(sykmeldtFnr = "12345678907", orgnummer = "123456789")
            val fulfilledWithDialog = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED, dialogId = UUID.randomUUID())
            val expiryPending = insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_EXPIRED, dialogId = UUID.randomUUID())
            insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_EXPIRED, dialogId = UUID.randomUUID(), expiredInDialogporten = now)
            insertNarmestelederbehov(behovStatus = BehovStatus.BEHOV_FULFILLED)
            insertRelation(employeeIdent = matching.sykmeldtFnr, organizationNumber = matching.orgnummer)
            insertRelation(employeeIdent = attention.sykmeldtFnr, organizationNumber = attention.orgnummer)
            insertRelation(narmestelederId = revokedId, employeeIdent = revoked.sykmeldtFnr, organizationNumber = revoked.orgnummer)
            insertRelation(employeeIdent = future.sykmeldtFnr, organizationNumber = future.orgnummer, activeFrom = OffsetDateTime.now().plusDays(1))
            insertRelation(employeeIdent = otherOrg.sykmeldtFnr, organizationNumber = "999999999")
            insertRelation(employeeIdent = ended.sykmeldtFnr, organizationNumber = ended.orgnummer, activeTo = OffsetDateTime.now().minusHours(1))
            transaction(TestDB.exposedDatabase) { DialogDispatchTable.deleteAll() }
            transaction(TestDB.exposedDatabase) { scheduleDialogDispatch(matching.id) }
            val acknowledged = requireNotNull(repository.claim(now, 60.seconds))
            repository.finish(acknowledged, DialogDispatchResult.Done, now) shouldBe true
            val duringCutover = insertNarmestelederbehov()
            repeat(2) { runDialogDispatchBackfill() }
            val createStarted = claimAllCreateStarted()
            assertSoftly {
                listOf(matching, attention).forEach {
                    findStoredNarmestelederbehov(it.id)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                }
                listOf(revoked, future, otherOrg, ended).forEach {
                    findStoredNarmestelederbehov(it.id)?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
                }
                createStarted shouldBe mapOf(
                    matching.id to false,
                    attention.id to false,
                    revoked.id to true,
                    future.id to true,
                    otherOrg.id to true,
                    ended.id to true,
                    fulfilledWithDialog.id to false,
                    expiryPending.id to false,
                    duringCutover.id to true,
                )
            }
        }

        listOf(400, 422, 403, 404, 503).forEach { status ->
            test("status $status is classified without a tight retry loop and exports observable queue state") {
                insertNarmestelederbehov(created = now.minusSeconds(30))
                val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                try {
                    val metrics = MicrometerDialogDispatchMetrics(repository, registry, java.time.Clock.fixed(now, ZoneOffset.UTC))
                    var calls = 0
                    dispatcher(
                        dialogCreation = { _, _, _ ->
                            calls++
                            NarmestelederbehovDialogCreationResult.Unavailable(
                                UpstreamFailure(DIALOGPORTEN, UpstreamFailureStage.RESPONSE, status, errors = listOf(UpstreamError("failure"))),
                            )
                        },
                        dispatchMetrics = metrics,
                    ).execute()
                    metrics.refresh()
                    val parked = status in listOf(400, 422)
                    val scrape = registry.scrape()
                    assertSoftly {
                        calls shouldBe 1
                        repository.claim(now, 60.seconds).shouldBeNull()
                        scrape shouldContain "syfo_narmesteleder_dialog_dispatch_parked ${if (parked) "1.0" else "0.0"}"
                        scrape shouldContain "syfo_narmesteleder_dialog_dispatch_pending ${if (parked) "0.0" else "1.0"}"
                        scrape shouldContain """syfo_narmesteleder_dialog_dispatch_attempts_total{outcome="${if (parked) "parked" else "retry"}"} 1.0"""
                    }
                } finally {
                    registry.close()
                }
            }
        }
    })
