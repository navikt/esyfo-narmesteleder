package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val now = Instant.parse("2026-06-15T12:00:00Z")

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

        test("all four repository operations use READ COMMITTED even when the database defaults to REPEATABLE READ") {
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
                    observedIsolation.isNotEmpty() shouldBe true
                    observedIsolation.toSet() shouldBe setOf("read committed")
                }

                assertReadCommitted { observedRepository.seedMissing(1, now) shouldBe 1 }
                lateinit var claim: ClaimedEmploymentCheck
                assertReadCommitted { claim = observedRepository.claimDue(1, 5.minutes, now).single() }
                assertReadCommitted { observedRepository.isClaimStillValid(claim, now) shouldBe true }
                assertReadCommitted {
                    observedRepository.complete(claim, EmploymentCheckOutcome.GYLDIG, now.plusSeconds(600), now) shouldBe true
                }
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
            rows.getValue(oldId)[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe
                Instant.parse("2026-02-28T12:00:00Z")
            rows.getValue(newId)[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe
                Instant.parse("2026-07-15T12:00:00Z")
            rows.values.forEach {
                it[EmploymentReconciliationTable.status] shouldBe "KLAR"
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
                controlRow(id)[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe due
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
            row[EmploymentReconciliationTable.status] shouldBe "CLAIMED"
            row[EmploymentReconciliationTable.claimToken] shouldBe first.claimToken
            row[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe now.plusSeconds(300)

            repository.claimDue(10, 5.minutes, now).map { it.narmesteLederId } shouldBe listOf(newer)
            repository.claimDue(10, 5.minutes, now) shouldBe emptyList()
            controlRow(inactive)[EmploymentReconciliationTable.status] shouldBe "KLAR"
            controlRow(inactive)[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe now.minusSeconds(30)
            controlRow(future)[EmploymentReconciliationTable.status] shouldBe "KLAR"
        }

        test("claim does not extend a live lease and reclaims at expiry with a new token") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val first = repository.claimDue(1, 5.minutes, now).single()
            repository.claimDue(1, 5.minutes, now.plusSeconds(299)) shouldBe emptyList()
            controlRow(id)[EmploymentReconciliationTable.claimToken] shouldBe first.claimToken
            controlRow(id)[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe now.plusSeconds(300)

            val reclaimed = repository.claimDue(1, 5.minutes, now.plusSeconds(300)).single()
            reclaimed.narmesteLederId shouldBe id
            (reclaimed.claimToken != first.claimToken) shouldBe true
            reclaimed.claimedAt shouldBe now.plusSeconds(300)
            controlRow(id)[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe now.plusSeconds(600)
        }

        test("concurrent seeders insert each missing relation once") {
            repeat(20) { insertEmploymentRelation() }
            val counts = concurrently { repository.seedMissing(20, now) }
            counts.sum() shouldBe 20
            transaction(TestDB.exposedDatabase) { EmploymentReconciliationTable.selectAll().count() } shouldBe 20L
            repository.seedMissing(20, now) shouldBe 0
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

        test("four parallel reclaimers fence all stale tokens and reclaim each expired lease only once") {
            val dueIds = List(20) {
                insertEmploymentRelation().also { insertControlRow(it, now) }
            }.toSet()
            val staleClaims = repository.claimDue(20, 5.minutes, now).associateBy { it.narmesteLederId }
            val reclaimedAt = now.plusSeconds(300)
            val batches = concurrently(workerCount = 4) { repository.claimDue(5, 5.minutes, reclaimedAt) }
            batches.map { it.size } shouldBe listOf(5, 5, 5, 5)
            val reclaimed = batches.flatten()
            reclaimed.map { it.narmesteLederId }.toSet() shouldBe dueIds
            reclaimed.forEach { claim ->
                val stale = staleClaims.getValue(claim.narmesteLederId)
                (claim.claimToken != stale.claimToken) shouldBe true
                repository.complete(stale, EmploymentCheckOutcome.BRUTT, now, reclaimedAt) shouldBe false
                repository.isClaimStillValid(claim, reclaimedAt) shouldBe true
                controlRow(claim.narmesteLederId)[EmploymentReconciliationTable.sistUtfall] shouldBe null
            }
            repository.claimDue(20, 5.minutes, reclaimedAt) shouldBe emptyList()
        }

        test("claim skips a locked earliest control row without waiting for its transaction") {
            val lockedId = insertEmploymentRelation()
            val availableId = insertEmploymentRelation()
            insertControlRow(lockedId, now.minusSeconds(2))
            insertControlRow(availableId, now.minusSeconds(1))
            coroutineScope {
                val locked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder = async(Dispatchers.IO) {
                    suspendTransaction(db = TestDB.exposedDatabase) {
                        EmploymentReconciliationTable.selectAll().where {
                            EmploymentReconciliationTable.narmestelederId eq lockedId
                        }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).single()
                        locked.complete(Unit)
                        release.await()
                    }
                }
                try {
                    withTimeout(5.seconds) {
                        locked.await()
                        repository.claimDue(2, 5.minutes, now).map { it.narmesteLederId } shouldBe listOf(availableId)
                    }
                } finally {
                    release.complete(Unit)
                    holder.await()
                }
            }
            repository.claimDue(2, 5.minutes, now).map { it.narmesteLederId } shouldBe listOf(lockedId)
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

            repository.complete(claim, EmploymentCheckOutcome.GYLDIG, nextCheck, completedAt) shouldBe true
            val row = controlRow(id)
            row[EmploymentReconciliationTable.status] shouldBe "KLAR"
            row[EmploymentReconciliationTable.claimToken] shouldBe null
            row[EmploymentReconciliationTable.sistUtfall] shouldBe "GYLDIG"
            row[EmploymentReconciliationTable.sistKontrollert]?.toInstant() shouldBe completedAt
            row[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe nextCheck
            repository.complete(claim, EmploymentCheckOutcome.FEILET, now, completedAt) shouldBe false
            controlRow(id)[EmploymentReconciliationTable.sistUtfall] shouldBe "GYLDIG"
        }

        test("stale-token completion after reclaim cannot overwrite the current claim") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val stale = repository.claimDue(1, 5.minutes, now).single()
            val current = repository.claimDue(1, 5.minutes, now.plusSeconds(300)).single()

            repository.complete(stale, EmploymentCheckOutcome.VILLE_BRUTT, now, now.plusSeconds(301)) shouldBe false
            val row = controlRow(id)
            row[EmploymentReconciliationTable.status] shouldBe "CLAIMED"
            row[EmploymentReconciliationTable.claimToken] shouldBe current.claimToken
            row[EmploymentReconciliationTable.nesteKontroll].toInstant() shouldBe now.plusSeconds(600)
            row[EmploymentReconciliationTable.sistUtfall] shouldBe null
            row[EmploymentReconciliationTable.sistKontrollert] shouldBe null
            row[EmploymentReconciliationTable.skyggeVilleBrutt] shouldBe null
        }

        test("VILLE_BRUTT records the first shadow timestamp only, across subsequent claims and outcomes") {
            val id = insertEmploymentRelation()
            insertControlRow(id, now)
            val first = repository.claimDue(1, 5.minutes, now).single()
            val firstShadow = now.plusSeconds(10)
            repository.complete(first, EmploymentCheckOutcome.VILLE_BRUTT, now.plusSeconds(20), firstShadow) shouldBe true
            controlRow(id)[EmploymentReconciliationTable.skyggeVilleBrutt]?.toInstant() shouldBe firstShadow

            val second = repository.claimDue(1, 5.minutes, now.plusSeconds(20)).single()
            repository.complete(second, EmploymentCheckOutcome.VILLE_BRUTT, now.plusSeconds(30), now.plusSeconds(21)) shouldBe true
            controlRow(id)[EmploymentReconciliationTable.skyggeVilleBrutt]?.toInstant() shouldBe firstShadow

            listOf(EmploymentCheckOutcome.GYLDIG, EmploymentCheckOutcome.BRUTT, EmploymentCheckOutcome.FEILET).forEach { outcome ->
                val claim = repository.claimDue(1, 5.minutes, now.plusSeconds(30)).single()
                repository.complete(claim, outcome, now.plusSeconds(30), now.plusSeconds(31)) shouldBe true
                val row = controlRow(id)
                row[EmploymentReconciliationTable.skyggeVilleBrutt]?.toInstant() shouldBe firstShadow
                row[EmploymentReconciliationTable.sistUtfall] shouldBe outcome.name
                row[EmploymentReconciliationTable.claimToken] shouldBe null
            }
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
            repository.complete(second, EmploymentCheckOutcome.GYLDIG, now.plusSeconds(400), now.plusSeconds(302)) shouldBe true
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

        test("the migration enforces allowed status, outcome and claim-token consistency") {
            val invalidRows = listOf(
                Triple("UNKNOWN", null, null),
                Triple("CLAIMED", null, null),
                Triple("KLAR", UUID.randomUUID(), null),
                Triple("KLAR", null, "UNKNOWN"),
            )
            invalidRows.forEach { (state, token, outcome) ->
                val failure = shouldThrow<SQLException> {
                    transaction(TestDB.exposedDatabase) {
                        maxAttempts = 1
                        EmploymentReconciliationTable.insert {
                            it[narmestelederId] = UUID.randomUUID()
                            it[status] = state
                            it[claimToken] = token
                            it[sistUtfall] = outcome
                            it[nesteKontroll] = now.atOffset(ZoneOffset.UTC)
                        }
                    }
                }
                failure.sqlState shouldBe "23514"
            }
            transaction(TestDB.exposedDatabase) { EmploymentReconciliationTable.selectAll().count() } shouldBe 0L
        }

        test("Flyway creates the module table with matching columns, named primary key and due index, but no foreign key") {
            TestDB.database.connection.use { connection ->
                val metadata = connection.metaData
                val table = EmploymentReconciliationTable.tableName
                metadata.getColumns(connection.catalog, "public", table, null).use { columns ->
                    val columnTypes = buildMap {
                        while (columns.next()) {
                            put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"))
                        }
                    }
                    columnTypes shouldBe mapOf(
                        "narmeste_leder_id" to "uuid",
                        "status" to "text",
                        "neste_kontroll" to "timestamptz",
                        "claim_token" to "uuid",
                        "sist_kontrollert" to "timestamptz",
                        "sist_utfall" to "text",
                        "skygge_ville_brutt" to "timestamptz",
                        "observert_brudd_fra_kilde" to "timestamptz",
                        "opprettet" to "timestamptz",
                    )
                    columnTypes.keys shouldBe EmploymentReconciliationTable.columns.map { it.name }.toSet()
                }
                metadata.getPrimaryKeys(connection.catalog, "public", table).use { keys ->
                    keys.next() shouldBe true
                    keys.getString("COLUMN_NAME") shouldBe "narmeste_leder_id"
                    keys.getString("PK_NAME") shouldBe EmploymentReconciliationTable.primaryKey.name
                    keys.next() shouldBe false
                }
                metadata.getImportedKeys(connection.catalog, "public", table).use { keys ->
                    keys.next() shouldBe false
                }
                metadata.getIndexInfo(connection.catalog, "public", table, false, false).use { indexes ->
                    val indexedColumns = buildMap {
                        while (indexes.next()) {
                            put(indexes.getString("INDEX_NAME"), indexes.getString("COLUMN_NAME"))
                        }
                    }
                    indexedColumns["nlrel_arbeidsforhold_kontroll_neste_kontroll_idx"] shouldBe "neste_kontroll"
                }
            }
        }

        test("control rows can precede and outlive relation events without being claimed while orphaned") {
            val id = UUID.randomUUID()
            insertControlRow(id, now)
            repository.claimDue(10, 5.minutes, now) shouldBe emptyList()
            insertEmploymentRelation(id = id)
            repository.seedMissing(10, now) shouldBe 0
            val claim = repository.claimDue(1, 5.minutes, now).single()
            TestDB.clearNarmestelederData()
            repository.isClaimStillValid(claim, now) shouldBe false
            repository.claimDue(10, 5.minutes, now.plusSeconds(300)) shouldBe emptyList()
            controlRow(id)[EmploymentReconciliationTable.claimToken] shouldBe claim.claimToken
        }

        test("invalid batch limits and leases are rejected before writing") {
            shouldThrow<IllegalArgumentException> { repository.seedMissing(0, now) }
            shouldThrow<IllegalArgumentException> { repository.seedMissing(-1, now) }
            shouldThrow<IllegalArgumentException> { repository.claimDue(0, 5.minutes, now) }
            shouldThrow<IllegalArgumentException> { repository.claimDue(1, Duration.ZERO, now) }
            shouldThrow<IllegalArgumentException> { repository.claimDue(1, (-1).seconds, now) }
            shouldThrow<IllegalArgumentException> { repository.claimDue(1, Duration.INFINITE, now) }
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

private suspend fun <T> concurrently(workerCount: Int = 2, operation: suspend () -> T): List<T> = coroutineScope {
    val ready = Channel<Unit>(workerCount)
    val start = CompletableDeferred<Unit>()
    val workers = List(workerCount) {
        async(Dispatchers.IO) {
            ready.send(Unit)
            start.await()
            operation()
        }
    }
    repeat(workerCount) { ready.receive() }
    start.complete(Unit)
    withTimeout(10.seconds) { workers.awaitAll() }
}

private fun controlRow(id: UUID) = transaction(TestDB.exposedDatabase) {
    EmploymentReconciliationTable.selectAll().where {
        EmploymentReconciliationTable.narmestelederId eq id
    }.single()
}

private fun insertControlRow(id: UUID, due: Instant) {
    transaction(TestDB.exposedDatabase) {
        EmploymentReconciliationTable.insert {
            it[narmestelederId] = id
            it[status] = "KLAR"
            it[nesteKontroll] = due.atOffset(ZoneOffset.UTC)
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
