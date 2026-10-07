package no.nav.syfo.narmesteleder.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.postgresql.util.PSQLException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

private class TransactionObserver(private val database: DatabaseInterface) : DatabaseInterface {
    var rollbacks = 0
    var commits = 0
    var insertFailure: Throwable? = null

    override val connection: Connection
        get() {
            val realConnection = database.connection
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                try {
                    when (method.name) {
                        "rollback" -> rollbacks++
                        "commit" -> commits++
                    }
                    val result = method.invoke(realConnection, *args.orEmpty())
                    if (method.name == "prepareStatement") {
                        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, statementMethod, statementArgs ->
                            try {
                                statementMethod.invoke(result, *statementArgs.orEmpty())
                            } catch (failure: InvocationTargetException) {
                                insertFailure = failure.targetException
                                throw failure.targetException
                            }
                        }
                    } else {
                        result
                    }
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            } as Connection
        }
}

class UniqueActiveNarmestelederbehovTest :
    DescribeSpec({
        val observedDatabase = TransactionObserver(TestDB.database)
        val db = PostgresNarmestelederDb(observedDatabase)
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

        describe("active narmestelederbehov uniqueness") {
            activeStatuses.forEach { first ->
                activeStatuses.forEach { second ->
                    it("rejects $second when $first occupies the employee and organization slot") {
                        val entity = nlBehovEntity().copy(behovStatus = first)
                        db.insertNlBehov(entity)
                        val rollbacks = observedDatabase.rollbacks
                        val commits = observedDatabase.commits

                        shouldThrow<ActiveNarmestelederbehovAlreadyExistsException> {
                            db.insertNlBehov(entity.copy(behovStatus = second))
                        }
                        observedDatabase.rollbacks shouldBe rollbacks + 1
                        observedDatabase.commits shouldBe commits

                        db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, activeStatuses).size shouldBe 1
                    }
                }
            }

            inactiveStatuses.forEach { status ->
                it("allows repeated $status rows alongside one active row") {
                    val entity = nlBehovEntity().copy(behovStatus = status)
                    db.insertNlBehov(entity)
                    db.insertNlBehov(entity)
                    db.insertNlBehov(entity.copy(behovStatus = BehovStatus.BEHOV_CREATED))
                    db.insertNlBehov(entity)

                    db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, listOf(status)).size shouldBe 3
                    db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, activeStatuses).size shouldBe 1
                }
            }

            it("allows active rows for different employees and different organizations") {
                val entity = nlBehovEntity()
                db.insertNlBehov(entity)
                val otherEmployee = nlBehovEntity().sykmeldtFnr
                val otherOrg = nlBehovEntity().orgnummer
                db.insertNlBehov(entity.copy(sykmeldtFnr = otherEmployee))
                db.insertNlBehov(entity.copy(orgnummer = otherOrg))

                db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, activeStatuses).size shouldBe 1
                db.findBehovByParameters(otherEmployee, entity.orgnummer, activeStatuses).size shouldBe 1
                db.findBehovByParameters(entity.sykmeldtFnr, otherOrg, activeStatuses).size shouldBe 1
            }

            it("allows the only active row to switch between both active statuses") {
                var entity = db.insertNlBehov(nlBehovEntity())
                listOf(BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION, BehovStatus.BEHOV_CREATED).forEach { status ->
                    entity = entity.copy(behovStatus = status)
                    db.updateNlBehov(entity)
                    db.findBehovById(requireNotNull(entity.id))?.behovStatus shouldBe status
                }
            }

            listOf(BehovStatus.BEHOV_FULFILLED, BehovStatus.BEHOV_EXPIRED).forEach { status ->
                activeStatuses.forEach { active ->
                    it("frees the $active slot when the row becomes $status") {
                        val entity = db.insertNlBehov(nlBehovEntity().copy(behovStatus = active))
                        db.updateNlBehov(entity.copy(behovStatus = status))
                        db.insertNlBehov(entity.copy(behovStatus = active))

                        db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, activeStatuses).size shouldBe 1
                        db.findBehovById(requireNotNull(entity.id))?.behovStatus shouldBe status
                    }
                }
            }

            activeStatuses.forEach { occupied ->
                activeStatuses.forEach { requested ->
                    it("propagates an update violation to $requested when $occupied occupies the slot") {
                        val active = db.insertNlBehov(nlBehovEntity().copy(behovStatus = occupied))
                        val historical = db.insertNlBehov(active.copy(behovStatus = BehovStatus.BEHOV_FULFILLED))

                        val failure = shouldThrow<PSQLException> {
                            db.updateNlBehov(historical.copy(behovStatus = requested))
                        }
                        failure.sqlState shouldBe "23505"
                        failure.serverErrorMessage?.constraint shouldBe "uq_nl_behov_active_employee_org"
                        db.findBehovById(requireNotNull(historical.id))?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                        db.findBehovByParameters(active.sykmeldtFnr, active.orgnummer, activeStatuses).size shouldBe 1
                    }
                }
            }

            it("propagates a different unique violation rather than treating it as an active conflict") {
                TestDB.database.connection.use { connection ->
                    connection.createStatement().use {
                        it.execute("CREATE UNIQUE INDEX test_other_unique_constraint ON nl_behov (avbrutt_narmesteleder_id)")
                    }
                    connection.commit()
                }
                try {
                    val entity = nlBehovEntity().copy(behovStatus = BehovStatus.BEHOV_FULFILLED)
                    db.insertNlBehov(entity)
                    val rollbacks = observedDatabase.rollbacks
                    val commits = observedDatabase.commits
                    val failure = shouldThrow<PSQLException> { db.insertNlBehov(entity) }
                    (failure === observedDatabase.insertFailure) shouldBe true
                    observedDatabase.rollbacks shouldBe rollbacks + 1
                    observedDatabase.commits shouldBe commits
                    failure.sqlState shouldBe "23505"
                    failure.serverErrorMessage?.constraint shouldBe "test_other_unique_constraint"
                    db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, listOf(BehovStatus.BEHOV_FULFILLED)).size shouldBe 1
                } finally {
                    TestDB.database.connection.use { connection ->
                        connection.createStatement().use { it.execute("DROP INDEX test_other_unique_constraint") }
                        connection.commit()
                    }
                }
            }

            it("rolls back and propagates other SQLExceptions unchanged when execute itself throws") {
                val rollbacks = observedDatabase.rollbacks
                val commits = observedDatabase.commits
                val failure = shouldThrow<SQLException> {
                    db.insertNlBehov(nlBehovEntity().copy(orgnummer = "too-long-for-column"))
                }
                failure.sqlState shouldBe "22001"
                (failure === observedDatabase.insertFailure) shouldBe true
                observedDatabase.rollbacks shouldBe rollbacks + 1
                observedDatabase.commits shouldBe commits
                // The one-connection pool must remain usable after the failed transaction.
                db.insertNlBehov(nlBehovEntity()).id?.let { db.findBehovById(it) != null } shouldBe true
            }

            it("has a unique valid index with exactly the two active statuses as its predicate") {
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
        }
    })
