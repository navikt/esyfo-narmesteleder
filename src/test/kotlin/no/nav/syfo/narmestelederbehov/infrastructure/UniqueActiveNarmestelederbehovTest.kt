package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.infrastructure.NarmestelederbehovTable
import no.nav.syfo.narmestelederbehov.infrastructure.StoredNarmestelederbehov
import no.nav.syfo.narmestelederbehov.infrastructure.findStoredNarmestelederbehov
import no.nav.syfo.narmestelederbehov.infrastructure.insertNarmestelederbehov
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.postgresql.util.PSQLException
import java.util.UUID

private fun countBehov(sykmeldtFnr: String, orgnummer: String, statuses: List<BehovStatus>): Int = transaction(TestDB.exposedDatabase) {
    NarmestelederbehovTable.select(NarmestelederbehovTable.id)
        .where {
            (NarmestelederbehovTable.sykmeldtFnr eq sykmeldtFnr) and
                (NarmestelederbehovTable.orgnummer eq orgnummer) and
                (NarmestelederbehovTable.behovStatus inList statuses)
        }
        .count().toInt()
}

private fun updateStatus(id: UUID, status: BehovStatus) = transaction(TestDB.exposedDatabase) {
    NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id }) {
        it[behovStatus] = status
    }
}

private fun insertForSameEmployee(row: StoredNarmestelederbehov, status: BehovStatus = row.behovStatus) = insertNarmestelederbehov(
    sykmeldtFnr = row.sykmeldtFnr,
    orgnummer = row.orgnummer,
    behovStatus = status,
)

class UniqueActiveNarmestelederbehovTest :
    FunSpec({
        val activeStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
        val inactiveStatuses = listOf(
            BehovStatus.BEHOV_FULFILLED,
            BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED,
            BehovStatus.BEHOV_EXPIRED,
            BehovStatus.ERROR,
            BehovStatus.ARBEIDSFORHOLD_NOT_FOUND,
            BehovStatus.HOVEDENHET_NOT_FOUND,
        )

        beforeTest {
            TestDB.clearAllData()
        }

        inactiveStatuses.forEach { status ->
            test("allows repeated $status rows alongside one active row") {
                val entity = insertNarmestelederbehov(behovStatus = status)
                insertForSameEmployee(entity)
                insertForSameEmployee(entity, BehovStatus.BEHOV_CREATED)
                insertForSameEmployee(entity)

                countBehov(entity.sykmeldtFnr, entity.orgnummer, listOf(status)) shouldBe 3
                countBehov(entity.sykmeldtFnr, entity.orgnummer, activeStatuses) shouldBe 1
            }
        }

        test("allows active rows for different employees and different organizations") {
            val entity = insertNarmestelederbehov(sykmeldtFnr = "00000000002", orgnummer = "000000002")
            val otherEmployee = "00000000001"
            val otherOrg = "000000001"
            insertNarmestelederbehov(sykmeldtFnr = otherEmployee, orgnummer = entity.orgnummer)
            insertNarmestelederbehov(sykmeldtFnr = entity.sykmeldtFnr, orgnummer = otherOrg)

            countBehov(entity.sykmeldtFnr, entity.orgnummer, activeStatuses) shouldBe 1
            countBehov(otherEmployee, entity.orgnummer, activeStatuses) shouldBe 1
            countBehov(entity.sykmeldtFnr, otherOrg, activeStatuses) shouldBe 1
        }

        test("allows the only active row to switch between both active statuses") {
            val entity = insertNarmestelederbehov()
            listOf(BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION, BehovStatus.BEHOV_CREATED).forEach { status ->
                updateStatus(entity.id, status)
                findStoredNarmestelederbehov(entity.id)?.behovStatus shouldBe status
            }
        }

        listOf(BehovStatus.BEHOV_FULFILLED, BehovStatus.BEHOV_EXPIRED).forEach { status ->
            activeStatuses.forEach { active ->
                test("frees the $active slot when the row becomes $status") {
                    val entity = insertNarmestelederbehov(behovStatus = active)
                    updateStatus(entity.id, status)
                    insertForSameEmployee(entity, active)

                    countBehov(entity.sykmeldtFnr, entity.orgnummer, activeStatuses) shouldBe 1
                    findStoredNarmestelederbehov(entity.id)?.behovStatus shouldBe status
                }
            }
        }

        activeStatuses.forEach { occupied ->
            activeStatuses.forEach { requested ->
                test("propagates an update violation to $requested when $occupied occupies the slot") {
                    val active = insertNarmestelederbehov(behovStatus = occupied)
                    val historical = insertForSameEmployee(active, BehovStatus.BEHOV_FULFILLED)

                    val failure = shouldThrow<Exception> {
                        updateStatus(historical.id, requested)
                    }
                    val postgresFailure = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<PSQLException>().single()
                    postgresFailure.sqlState shouldBe "23505"
                    postgresFailure.serverErrorMessage?.constraint shouldBe "uq_nl_behov_active_employee_org"
                    findStoredNarmestelederbehov(historical.id)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                    countBehov(active.sykmeldtFnr, active.orgnummer, activeStatuses) shouldBe 1
                }
            }
        }

        test("has a unique valid index with exactly the two active statuses as its predicate") {
            TestDB.database.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT indisunique, indisvalid, pg_get_expr(indpred, indrelid) AS predicate,
                           pg_get_indexdef(indexrelid, 1, true) AS first_key,
                           pg_get_indexdef(indexrelid, 2, true) AS second_key,
                           indnkeyatts
                    FROM pg_index
                    WHERE indexrelid = 'uq_nl_behov_active_employee_org'::regclass
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        result.next() shouldBe true
                        result.getBoolean("indisunique") shouldBe true
                        result.getBoolean("indisvalid") shouldBe true
                        result.getString("first_key") shouldBe "sykemeldt_fnr"
                        result.getString("second_key") shouldBe "orgnummer"
                        result.getInt("indnkeyatts") shouldBe 2
                        result.getString("predicate") shouldBe
                            "(behov_status = ANY (ARRAY['BEHOV_CREATED'::behov_status, 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION'::behov_status]))"
                        result.next() shouldBe false
                    }
                }
            }
        }
    })
