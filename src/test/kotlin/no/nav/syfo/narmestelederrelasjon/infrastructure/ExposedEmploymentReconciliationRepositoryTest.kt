package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.ClaimedEmploymentCheck
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckStats
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val now = Instant.parse("2026-06-15T12:00:00Z")
private const val WORKER_COUNT = 2

class ExposedEmploymentReconciliationRepositoryTest :
    FunSpec({
        val repository = ExposedEmploymentReconciliationRepository(TestDB.exposedDatabase)

        beforeTest {
            transaction(TestDB.exposedDatabase) { EmploymentReconciliationTable.deleteAll() }
            TestDB.clearNarmestelederData()
        }
        afterSpec {
            transaction(TestDB.exposedDatabase) { EmploymentReconciliationTable.deleteAll() }
            TestDB.clearNarmestelederData()
        }

        test("all repository operations use READ COMMITTED even when the database defaults to REPEATABLE READ") {
            insertEmploymentRelation()
            val observedIsolation = mutableListOf<String>()
            withObservedRepository(
                beforeStatement = { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT current_setting('transaction_isolation')").use { result ->
                            result.next() shouldBe true
                            observedIsolation += result.getString(1)
                        }
                    }
                },
            ) { observedRepository ->
                suspend fun assertReadCommitted(operation: suspend () -> Unit) {
                    observedIsolation.clear()
                    operation()
                    observedIsolation.shouldNotBeEmpty()
                    observedIsolation.toSet() shouldBe setOf("read committed")
                }

                assertReadCommitted { observedRepository.seedMissing(1, now) shouldBe 1 }
                lateinit var claim: ClaimedEmploymentCheck
                assertReadCommitted { claim = observedRepository.claimDue(1, 5.minutes, now).single() }
                assertReadCommitted { observedRepository.isClaimStillValid(claim, now) shouldBe true }
                assertReadCommitted {
                    observedRepository.complete(claim, EmploymentCheckOutcome.VALID, now.plusSeconds(600), now) shouldBe true
                }
                assertReadCommitted { observedRepository.recordSourceRevocation(claim.narmesteLederId, now) shouldBe true }
                assertReadCommitted { observedRepository.stats(now) }
            }
        }

        test("seed inserts only missing active relations, bounded and idempotent with calendar-month first due") {
            val oldId = insertEmploymentRelation(from = Instant.parse("2026-01-31T12:00:00Z"))
            val newId = insertEmploymentRelation(from = now)
            insertEmploymentRelation(from = now.minusSeconds(90 * 86_400), to = now)

            repository.seedMissing(1, now) shouldBe 1
            repository.seedMissing(1, now) shouldBe 1
            repository.seedMissing(10, now) shouldBe 0

            val rows = transaction(TestDB.exposedDatabase) {
                EmploymentReconciliationTable.selectAll().associateBy { it[EmploymentReconciliationTable.narmestelederId] }
            }
            rows.keys shouldBe setOf(oldId, newId)
            rows.getValue(oldId)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe
                Instant.parse("2026-02-28T12:00:00Z")
            rows.getValue(newId)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe
                Instant.parse("2026-07-15T12:00:00Z")
            rows.values.forEach {
                it[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY
                it[EmploymentReconciliationTable.claimToken] shouldBe null
            }
        }

        test("first due uses a UTC calendar month at month-end even when the transaction TimeZone is Europe Oslo") {
            val cases = listOf(
                "2026-01-31T12:00:00Z" to "2026-02-28T12:00:00Z",
                // January 31 in Oslo but January 30 UTC: clamping must use the UTC date.
                "2026-01-30T23:30:00Z" to "2026-02-28T23:30:00Z",
                // Crosses both a local date boundary and Oslo's change to daylight saving time.
                "2026-02-28T23:30:00Z" to "2026-03-28T23:30:00Z",
            ).associate { (from, due) ->
                insertEmploymentRelation(from = Instant.parse(from)) to Instant.parse(due)
            }
            var observedTimeZone = false
            withObservedRepository(
                beforeStatement = { connection ->
                    connection.createStatement().use { statement ->
                        // LOCAL keeps this setting confined to the repository transaction.
                        statement.execute("SET LOCAL TIME ZONE 'Europe/Oslo'")
                        statement.executeQuery("SELECT current_setting('TimeZone')").use { result ->
                            result.next() shouldBe true
                            result.getString(1) shouldBe "Europe/Oslo"
                            observedTimeZone = true
                        }
                    }
                },
            ) { osloRepository ->
                osloRepository.seedMissing(cases.size, now) shouldBe cases.size
            }
            observedTimeZone shouldBe true
            cases.forEach { (id, due) ->
                controlRow(id)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe due
            }
        }

        test("claim selects due active relations in due order with a bounded batch and committed token lease") {
            val older = insertEmploymentRelation()
            val newer = insertEmploymentRelation()
            val inactive = insertEmploymentRelation(to = now)
            val future = insertEmploymentRelation()
            insertControlRow(older, now.minusSeconds(20))
            insertControlRow(newer, now.minusSeconds(10))
            insertControlRow(inactive, now.minusSeconds(30))
            insertControlRow(future, now.plusSeconds(1))

            val first = repository.claimDue(1, 5.minutes, now).single()
            first.narmesteLederId shouldBe older
            first.organizationNumber shouldBe OrganizationNumber("123456789")
            first.employeeIdent shouldBe PersonIdent("12345678901")
            first.claimedAt shouldBe now
            val row = controlRow(older)
            row[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.CLAIMED
            row[EmploymentReconciliationTable.claimToken] shouldBe first.claimToken
            row[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(300)

            repository.claimDue(10, 5.minutes, now).map { it.narmesteLederId } shouldBe listOf(newer)
            repository.claimDue(10, 5.minutes, now) shouldBe emptyList()
            controlRow(inactive)[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY
            controlRow(inactive)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.minusSeconds(30)
            controlRow(future)[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY
        }

        test("claim does not extend a live lease and reclaims at expiry with a new token") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val first = repository.claimDue(1, 5.minutes, now).single()
            repository.claimDue(1, 5.minutes, now.plusSeconds(299)) shouldBe emptyList()
            controlRow(id)[EmploymentReconciliationTable.claimToken] shouldBe first.claimToken
            controlRow(id)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(300)

            val reclaimed = repository.claimDue(1, 5.minutes, now.plusSeconds(300)).single()
            reclaimed.narmesteLederId shouldBe id
            reclaimed.claimToken shouldNotBe first.claimToken
            reclaimed.claimedAt shouldBe now.plusSeconds(300)
            controlRow(id)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(600)
        }

        test("live cutover claims READY WOULD_REVOKE immediately even when the next check is in the future") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now.plusSeconds(86_400), EmploymentCheckOutcome.WOULD_REVOKE)

            val claim = repository.claimDue(1, 5.minutes, now, revokeEnabled = true).single()

            claim.narmesteLederId shouldBe id
            claim.sourceRevokedAt shouldBe null
            controlRow(id)[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.CLAIMED
            controlRow(id)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(300)
        }

        test("live mode does not claim ended relations with a pending comparison") {
            val id = insertEmploymentRelation(to = now)
            insertControlRow(id, now, EmploymentCheckOutcome.WOULD_REVOKE)
            repository.recordSourceRevocation(id, now)

            repository.claimDue(10, 5.minutes, now, revokeEnabled = true) shouldBe emptyList()
            controlRow(id)[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY

            repository.claimDue(10, 5.minutes, now).single().narmesteLederId shouldBe id
        }

        test("live cutover never steals an unexpired claim with last outcome WOULD_REVOKE") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now, EmploymentCheckOutcome.WOULD_REVOKE)
            val first = repository.claimDue(1, 5.minutes, now).single()

            repository.claimDue(1, 5.minutes, now.plusSeconds(299), revokeEnabled = true) shouldBe emptyList()
            controlRow(id)[EmploymentReconciliationTable.claimToken] shouldBe first.claimToken
            controlRow(id)[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(300)

            val reclaimed = repository.claimDue(1, 5.minutes, now.plusSeconds(300), revokeEnabled = true).single()
            reclaimed.narmesteLederId shouldBe id
            reclaimed.claimToken shouldNotBe first.claimToken
        }

        test("shadow still waits for future WOULD_REVOKE checks and live cutover uses only the latest outcome") {
            val wouldRevoke = insertEmploymentRelation()
            insertControlRow(wouldRevoke, now.plusSeconds(86_400), EmploymentCheckOutcome.WOULD_REVOKE)
            val historicShadow = insertEmploymentRelation()
            insertControlRow(historicShadow, now.plusSeconds(86_400), EmploymentCheckOutcome.FAILED, wouldRevokeDaysAgo = 10)

            repository.claimDue(10, 5.minutes, now) shouldBe emptyList()
            repository.claimDue(10, 5.minutes, now, revokeEnabled = true).map { it.narmesteLederId } shouldBe listOf(wouldRevoke)
            controlRow(historicShadow)[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY
        }

        test("live cutover keeps deterministic due and id ordering with a bounded batch") {
            val overdue = insertEmploymentRelation()
            insertControlRow(overdue, now.minusSeconds(1))
            val futureIds = listOf(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
            )
            futureIds.reversed().forEach {
                insertEmploymentRelation(id = it)
                insertControlRow(it, now.plusSeconds(86_400), EmploymentCheckOutcome.WOULD_REVOKE)
            }

            repository.claimDue(3, 5.minutes, now, revokeEnabled = true).map { it.narmesteLederId } shouldBe
                listOf(overdue, futureIds[0], futureIds[1])
            repository.claimDue(3, 5.minutes, now, revokeEnabled = true).map { it.narmesteLederId } shouldBe listOf(futureIds[2])
        }

        test("parallel live cutover claimers receive disjoint future WOULD_REVOKE batches") {
            val ids = List(20) {
                insertEmploymentRelation().also { insertControlRow(it, now.plusSeconds(86_400), EmploymentCheckOutcome.WOULD_REVOKE) }
            }.toSet()

            val batches = concurrently { repository.claimDue(10, 5.minutes, now, revokeEnabled = true) }
                .map { batch -> batch.map { it.narmesteLederId }.toSet() }

            batches.forEach { it.size shouldBe 10 }
            batches[0].intersect(batches[1]) shouldBe emptySet()
            (batches[0] + batches[1]) shouldBe ids
            repository.claimDue(20, 5.minutes, now, revokeEnabled = true) shouldBe emptyList()
        }

        test("two parallel claimers receive disjoint batches covering every due row exactly once") {
            val dueIds = List(20) {
                insertEmploymentRelation().also { insertControlRow(it, now.minusSeconds(1)) }
            }.toSet()
            val batches = concurrently { repository.claimDue(10, 5.minutes, now) }
            val firstIds = batches[0].map { it.narmesteLederId }.toSet()
            val secondIds = batches[1].map { it.narmesteLederId }.toSet()

            firstIds.size shouldBe 10
            secondIds.size shouldBe 10
            firstIds.intersect(secondIds) shouldBe emptySet()
            (firstIds + secondIds) shouldBe dueIds
            repository.claimDue(20, 5.minutes, now) shouldBe emptyList()
        }

        test("claim locks only the control table and does not skip a row whose relation is locked") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            coroutineScope {
                val locked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder = async(Dispatchers.IO) {
                    suspendTransaction(db = TestDB.exposedDatabase) {
                        NarmestelederTable.selectAll().where {
                            NarmestelederTable.narmestelederId eq id
                        }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).single()
                        locked.complete(Unit)
                        release.await()
                    }
                }
                try {
                    withTimeout(5.seconds) {
                        locked.await()
                        repository.claimDue(1, 5.minutes, now).single().narmesteLederId shouldBe id
                    }
                } finally {
                    release.complete(Unit)
                    holder.await()
                }
            }
        }

        test("completion with the current token resets the claim and records the outcome and next check") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val claim = repository.claimDue(1, 5.minutes, now).single()
            val completedAt = now.plusSeconds(10)
            val nextCheck = now.plusSeconds(30 * 86_400)

            repository.complete(claim, EmploymentCheckOutcome.VALID, nextCheck, completedAt) shouldBe true
            val row = controlRow(id)
            row[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY
            row[EmploymentReconciliationTable.claimToken] shouldBe null
            row[EmploymentReconciliationTable.lastOutcome] shouldBe EmploymentCheckOutcome.VALID
            row[EmploymentReconciliationTable.lastCheckedAt]?.toInstant() shouldBe completedAt
            row[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe nextCheck
            repository.complete(claim, EmploymentCheckOutcome.FAILED, now, completedAt) shouldBe false
            controlRow(id)[EmploymentReconciliationTable.lastOutcome] shouldBe EmploymentCheckOutcome.VALID
        }

        test("stale-token completion after reclaim cannot overwrite the current claim") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val stale = repository.claimDue(1, 5.minutes, now).single()
            val current = repository.claimDue(1, 5.minutes, now.plusSeconds(300)).single()

            repository.complete(stale, EmploymentCheckOutcome.WOULD_REVOKE, now, now.plusSeconds(301)) shouldBe false
            val row = controlRow(id)
            row[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.CLAIMED
            row[EmploymentReconciliationTable.claimToken] shouldBe current.claimToken
            row[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(600)
            row[EmploymentReconciliationTable.lastOutcome] shouldBe null
            row[EmploymentReconciliationTable.lastCheckedAt] shouldBe null
            row[EmploymentReconciliationTable.shadowWouldRevokeAt] shouldBe null
        }

        test("WOULD_REVOKE keeps the timestamp of the first decision in a run, and VALID ends the run") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val first = repository.claimDue(1, 5.minutes, now).single()
            val firstShadow = now.plusSeconds(10)
            repository.complete(first, EmploymentCheckOutcome.WOULD_REVOKE, now.plusSeconds(20), firstShadow) shouldBe true
            controlRow(id)[EmploymentReconciliationTable.shadowWouldRevokeAt]?.toInstant() shouldBe firstShadow

            listOf(EmploymentCheckOutcome.WOULD_REVOKE, EmploymentCheckOutcome.REVOKED, EmploymentCheckOutcome.FAILED).forEach { outcome ->
                val claim = repository.claimDue(1, 5.minutes, now.plusSeconds(30)).single()
                repository.complete(claim, outcome, now.plusSeconds(30), now.plusSeconds(31)) shouldBe true
                val row = controlRow(id)
                row[EmploymentReconciliationTable.shadowWouldRevokeAt]?.toInstant() shouldBe firstShadow
                row[EmploymentReconciliationTable.lastOutcome] shouldBe outcome
                row[EmploymentReconciliationTable.claimToken] shouldBe null
            }

            val kept = repository.claimDue(1, 5.minutes, now.plusSeconds(30)).single()
            repository.complete(kept, EmploymentCheckOutcome.VALID, now.plusSeconds(40), now.plusSeconds(32)) shouldBe true
            controlRow(id)[EmploymentReconciliationTable.shadowWouldRevokeAt] shouldBe null
        }

        test("claim validity requires a current unexpired token and that exact relation to remain active") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val first = repository.claimDue(1, 5.minutes, now).single()
            repository.isClaimStillValid(first, now) shouldBe true
            repository.isClaimStillValid(first, now.plusSeconds(299)) shouldBe true
            repository.isClaimStillValid(first, now.plusSeconds(300)) shouldBe false

            val second = repository.claimDue(1, 5.minutes, now.plusSeconds(300)).single()
            repository.isClaimStillValid(first, now.plusSeconds(301)) shouldBe false
            repository.isClaimStillValid(second, now.plusSeconds(301)) shouldBe true
            repository.complete(second, EmploymentCheckOutcome.VALID, now.plusSeconds(400), now.plusSeconds(302)) shouldBe true
            repository.isClaimStillValid(second, now.plusSeconds(303)) shouldBe false

            val third = repository.claimDue(1, 5.minutes, now.plusSeconds(400)).single()
            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.update({ NarmestelederTable.narmestelederId eq id }) {
                    it[aktivTom] = now.plusSeconds(401).atOffset(ZoneOffset.UTC)
                }
            }
            insertEmploymentRelation() // Same employee and organization, but a different relation id.
            repository.isClaimStillValid(third, now.plusSeconds(402)) shouldBe false
            repository.claimDue(10, 5.minutes, now.plusSeconds(700)) shouldBe emptyList()
            controlRow(id)[EmploymentReconciliationTable.claimToken] shouldBe third.claimToken
        }

        test("claim validity rejects changed organization or employee on the same active relation id") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val claim = repository.claimDue(1, 5.minutes, now).single()
            repository.isClaimStillValid(claim, now) shouldBe true

            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.update({ NarmestelederTable.narmestelederId eq id }) {
                    it[orgnummer] = "987654321"
                }
            }
            repository.isClaimStillValid(claim, now) shouldBe false
            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.update({ NarmestelederTable.narmestelederId eq id }) {
                    it[orgnummer] = claim.organizationNumber.value
                }
            }
            repository.isClaimStillValid(claim, now) shouldBe true
            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.update({ NarmestelederTable.narmestelederId eq id }) {
                    it[sykmeldtFnr] = "12345678902"
                }
            }
            repository.isClaimStillValid(claim, now) shouldBe false
            controlRow(id)[EmploymentReconciliationTable.claimToken] shouldBe claim.claimToken
        }

        test("notes the first source revocation, also for a relation that was never seeded, and makes it due first") {
            val seeded = insertEmploymentRelation(to = now)
            insertControlRow(seeded, now.plusSeconds(86_400))
            val unseeded = insertEmploymentRelation(to = now)

            listOf(seeded, unseeded).forEach { id ->
                repository.recordSourceRevocation(id, now) shouldBe true
                repository.recordSourceRevocation(id, now.plusSeconds(60)) shouldBe false
                controlRow(id).also {
                    it[EmploymentReconciliationTable.status] shouldBe EmploymentCheckStatus.READY
                    it[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe Instant.EPOCH
                    it[EmploymentReconciliationTable.sourceRevocationObservedAt]?.toInstant() shouldBe now
                }
            }
        }

        test("concurrent redeliveries note the source revocation once and leave a claimed lease alone") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val claim = repository.claimDue(1, 5.minutes, now).single()

            concurrently { repository.recordSourceRevocation(id, now) }.sorted() shouldBe listOf(false, true)

            controlRow(id).also {
                it[EmploymentReconciliationTable.claimToken] shouldBe claim.claimToken
                it[EmploymentReconciliationTable.nextCheckAt].toInstant() shouldBe now.plusSeconds(300)
            }
        }

        test("a revoked relation is claimed for comparison ahead of a backlog, and not again once compared") {
            val backlog = insertEmploymentRelation()
            insertControlRow(backlog, now.minusSeconds(365 * 86_400))
            val revoked = insertEmploymentRelation(to = now)
            insertControlRow(revoked, now.plusSeconds(86_400))
            repository.recordSourceRevocation(revoked, now)

            val comparison = repository.claimDue(1, 5.minutes, now).single()
            comparison.narmesteLederId shouldBe revoked
            comparison.sourceRevokedAt shouldBe now
            repository.claimDue(1, 5.minutes, now).single().sourceRevokedAt shouldBe null
            repository.complete(comparison, EmploymentCheckOutcome.WOULD_REVOKE, now.plusSeconds(86_400), now.plusSeconds(1)) shouldBe true

            repository.claimDue(10, 5.minutes, now.plusSeconds(2 * 86_400)).map { it.narmesteLederId } shouldBe listOf(backlog)
        }

        test("a failed comparison is retried within seven days of the source's revocation, but not after") {
            val revoked = insertEmploymentRelation(to = now)
            repository.recordSourceRevocation(revoked, now)
            val first = repository.claimDue(1, 5.minutes, now).single()
            repository.complete(first, EmploymentCheckOutcome.FAILED, now.plusSeconds(86_400), now) shouldBe true

            val retry = repository.claimDue(1, 5.minutes, now.plusSeconds(86_400)).single()
            retry.sourceRevokedAt shouldBe now
            repository.complete(retry, EmploymentCheckOutcome.FAILED, now.plusSeconds(7 * 86_400), now.plusSeconds(86_400)) shouldBe true

            repository.claimDue(1, 5.minutes, now.plusSeconds(7 * 86_400)) shouldBe emptyList()
        }

        test("stats count due and would-revoke relations by age, for active relations only") {
            insertControlRow(insertEmploymentRelation(), now, EmploymentCheckOutcome.WOULD_REVOKE, wouldRevokeDaysAgo = 30) // Due.
            insertControlRow(insertEmploymentRelation(), now.plusSeconds(1), EmploymentCheckOutcome.WOULD_REVOKE, wouldRevokeDaysAgo = 31)
            insertControlRow(insertEmploymentRelation(), now.plusSeconds(1), EmploymentCheckOutcome.VALID)
            insertControlRow(insertEmploymentRelation(), now) // Due, never checked.
            insertControlRow(insertEmploymentRelation(to = now), now, EmploymentCheckOutcome.WOULD_REVOKE, wouldRevokeDaysAgo = 40) // Ended.
            insertControlRow(UUID.randomUUID(), now) // No relation.
            insertControlRow(insertEmploymentRelation(), now.minusSeconds(300))
            repository.claimDue(1, 5.minutes, now.minusSeconds(300)) // Due, because the lease expires at now.

            repository.stats(now) shouldBe EmploymentCheckStats(onlyShadowUnder31Days = 1, onlyShadow31DaysOrMore = 1, due = 3)
        }
    })

/** Observes each statement on its real transaction connection without adding a production hook. */
private suspend fun <T> withObservedRepository(
    beforeStatement: (Connection) -> Unit,
    operation: suspend (ExposedEmploymentReconciliationRepository) -> T,
): T {
    val database = Database.connect(
        getNewConnection = {
            val connection = TestDB.database.connection
            object : Connection by connection {
                override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement {
                    beforeStatement(connection)
                    return connection.prepareStatement(sql, autoGeneratedKeys)
                }

                override fun prepareStatement(sql: String, columnNames: Array<out String>): PreparedStatement {
                    beforeStatement(connection)
                    return connection.prepareStatement(sql, columnNames)
                }
            }
        },
        databaseConfig = DatabaseConfig {
            defaultIsolationLevel = Connection.TRANSACTION_REPEATABLE_READ
            defaultMaxAttempts = 1
        },
    )
    return try {
        operation(ExposedEmploymentReconciliationRepository(database))
    } finally {
        TransactionManager.closeAndUnregister(database)
    }
}

private suspend fun <T> concurrently(operation: suspend () -> T): List<T> = coroutineScope {
    val ready = Channel<Unit>(WORKER_COUNT)
    val start = CompletableDeferred<Unit>()
    val workers = List(WORKER_COUNT) {
        async(Dispatchers.IO) {
            ready.send(Unit)
            start.await()
            operation()
        }
    }
    repeat(WORKER_COUNT) { ready.receive() }
    start.complete(Unit)
    withTimeout(10.seconds) { workers.awaitAll() }
}

private fun controlRow(id: UUID) = transaction(TestDB.exposedDatabase) {
    EmploymentReconciliationTable.selectAll().where {
        EmploymentReconciliationTable.narmestelederId eq id
    }.single()
}

private fun insertControlRow(
    id: UUID,
    due: Instant,
    outcome: EmploymentCheckOutcome? = null,
    wouldRevokeDaysAgo: Long? = null,
) {
    transaction(TestDB.exposedDatabase) {
        EmploymentReconciliationTable.insert {
            it[narmestelederId] = id
            it[status] = EmploymentCheckStatus.READY
            it[nextCheckAt] = due.atOffset(ZoneOffset.UTC)
            it[lastOutcome] = outcome
            it[shadowWouldRevokeAt] = wouldRevokeDaysAgo?.let { days -> now.minusSeconds(days * 86_400).atOffset(ZoneOffset.UTC) }
        }
    }
}

private fun insertEmploymentRelation(
    id: UUID = UUID.randomUUID(),
    from: Instant = now.minusSeconds(60 * 86_400),
    to: Instant? = null,
): UUID {
    transaction(TestDB.exposedDatabase) {
        NarmestelederTable.insert {
            it[narmestelederId] = id
            it[orgnummer] = "123456789"
            it[sykmeldtFnr] = "12345678901"
            it[narmestelederFnr] = "10987654321"
            it[narmestelederTelefonnummer] = "99887766"
            it[narmestelederEpost] = "leder@example.com"
            it[aktivFom] = from.atOffset(ZoneOffset.UTC)
            it[aktivTom] = to?.atOffset(ZoneOffset.UTC)
        }
    }
    return id
}
