package no.nav.syfo.narmesteleder.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.postgresql.util.PSQLException

private fun countBehov(sykmeldtFnr: String, orgnummer: String, statuses: List<BehovStatus>): Int = TestDB.database.connection.use { connection ->
    connection.prepareStatement(
        "SELECT count(*) FROM nl_behov WHERE sykemeldt_fnr = ? AND orgnummer = ? AND behov_status::text = ANY (?)",
    ).use { statement ->
        statement.setString(1, sykmeldtFnr)
        statement.setString(2, orgnummer)
        statement.setArray(3, connection.createArrayOf("text", statuses.map { it.name }.toTypedArray()))
        statement.executeQuery().use { result ->
            result.next()
            result.getInt(1)
        }
    }
}

class UniqueActiveNarmestelederbehovTest :
    FunSpec({
        val db = PostgresNarmestelederDb(TestDB.database)
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
                val entity = nlBehovEntity().copy(behovStatus = status)
                insertNlBehov(entity)
                insertNlBehov(entity)
                insertNlBehov(entity.copy(behovStatus = BehovStatus.BEHOV_CREATED))
                insertNlBehov(entity)

                countBehov(entity.sykmeldtFnr, entity.orgnummer, listOf(status)) shouldBe 3
                countBehov(entity.sykmeldtFnr, entity.orgnummer, activeStatuses) shouldBe 1
            }
        }

        test("allows active rows for different employees and different organizations") {
            val entity = nlBehovEntity()
            insertNlBehov(entity)
            val otherEmployee = nlBehovEntity().sykmeldtFnr
            val otherOrg = nlBehovEntity().orgnummer
            insertNlBehov(entity.copy(sykmeldtFnr = otherEmployee))
            insertNlBehov(entity.copy(orgnummer = otherOrg))

            countBehov(entity.sykmeldtFnr, entity.orgnummer, activeStatuses) shouldBe 1
            countBehov(otherEmployee, entity.orgnummer, activeStatuses) shouldBe 1
            countBehov(entity.sykmeldtFnr, otherOrg, activeStatuses) shouldBe 1
        }

        test("allows the only active row to switch between both active statuses") {
            var entity = insertNlBehov(nlBehovEntity())
            listOf(BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION, BehovStatus.BEHOV_CREATED).forEach { status ->
                entity = entity.copy(behovStatus = status)
                db.updateNlBehov(entity)
                TestDB.database.findBehovById(requireNotNull(entity.id))?.behovStatus shouldBe status
            }
        }

        listOf(BehovStatus.BEHOV_FULFILLED, BehovStatus.BEHOV_EXPIRED).forEach { status ->
            activeStatuses.forEach { active ->
                test("frees the $active slot when the row becomes $status") {
                    val entity = insertNlBehov(nlBehovEntity().copy(behovStatus = active))
                    db.updateNlBehov(entity.copy(behovStatus = status))
                    insertNlBehov(entity.copy(behovStatus = active))

                    countBehov(entity.sykmeldtFnr, entity.orgnummer, activeStatuses) shouldBe 1
                    TestDB.database.findBehovById(requireNotNull(entity.id))?.behovStatus shouldBe status
                }
            }
        }

        activeStatuses.forEach { occupied ->
            activeStatuses.forEach { requested ->
                test("propagates an update violation to $requested when $occupied occupies the slot") {
                    val active = insertNlBehov(nlBehovEntity().copy(behovStatus = occupied))
                    val historical = insertNlBehov(active.copy(behovStatus = BehovStatus.BEHOV_FULFILLED))

                    val failure = shouldThrow<PSQLException> {
                        db.updateNlBehov(historical.copy(behovStatus = requested))
                    }
                    failure.sqlState shouldBe "23505"
                    failure.serverErrorMessage?.constraint shouldBe "uq_nl_behov_active_employee_org"
                    TestDB.database.findBehovById(requireNotNull(historical.id))?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
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
