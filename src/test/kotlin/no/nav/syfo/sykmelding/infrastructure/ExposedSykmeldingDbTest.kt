package no.nav.syfo.sykmelding.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.datafaker.Faker
import no.nav.syfo.TestDB
import no.nav.syfo.findBySykmeldingId
import no.nav.syfo.sykmelding.application.SendtSykmelding
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class ExposedSykmeldingDbTest :
    DescribeSpec({
        val db = ExposedSykmeldingDb(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearSendtSykmeldingData()
        }

        fun sendtSykmelding(
            sykmeldingId: UUID = UUID.randomUUID(),
            fnr: String = Faker().numerify("###########"),
            orgnummer: String = Faker().numerify("#########"),
            fom: LocalDate = LocalDate.of(2025, 1, 1),
            tom: LocalDate = LocalDate.of(2025, 1, 10),
            syketilfelleStartDato: LocalDate? = LocalDate.of(2025, 1, 1),
        ) = SendtSykmelding(
            sykmeldingId = sykmeldingId,
            fnr = fnr,
            orgnummer = orgnummer,
            fom = fom,
            tom = tom,
            syketilfelleStartDato = syketilfelleStartDato,
        )

        describe("transaction - upsertSykmeldingBatch") {
            it("should persist a single sykmelding and allow retrieval") {
                val sykmeldingId = UUID.randomUUID()
                val toInsert = sendtSykmelding(sykmeldingId = sykmeldingId)

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(listOf(toInsert))
                }

                val retrieved = db.findBySykmeldingId(sykmeldingId)
                retrieved shouldNotBe null
                val row = retrieved!!

                row.id shouldNotBe null
                row.sykmeldingId shouldBe sykmeldingId
                row.fnr shouldBe toInsert.fnr
                row.orgnummer shouldBe toInsert.orgnummer
                row.fom shouldBe toInsert.fom
                row.tom shouldBe toInsert.tom
                row.syketilfelleStartDato shouldBe toInsert.syketilfelleStartDato
                row.revokedDate shouldBe null
            }

            it("should persist multiple sykmeldinger in one batch") {
                val ids = (1..5).map { UUID.randomUUID() }
                val sykmeldinger = ids.mapIndexed { index, id ->
                    sendtSykmelding(
                        sykmeldingId = id,
                        fnr = "1234567891$index",
                        orgnummer = "99999999$index"
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(sykmeldinger)
                }

                ids.forEachIndexed { index, id ->
                    val retrieved = db.findBySykmeldingId(id)
                    retrieved shouldNotBe null
                    retrieved!!.fnr shouldBe "1234567891$index"
                    retrieved.orgnummer shouldBe "99999999$index"
                }
            }

            it("should update existing row when incoming tom is newer") {
                val fnr = "12345678901"
                val orgnummer = "999999999"
                val oldId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(sykmeldingId = oldId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 1, 10))
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(sykmeldingId = newId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 2, 10))
                        )
                    )
                }

                val row = db.findBySykmeldingId(newId)
                row shouldNotBe null
                row!!.tom shouldBe LocalDate.of(2025, 2, 10)

                db.findBySykmeldingId(oldId) shouldBe null
            }

            it("should not update existing row when incoming tom is older") {
                val fnr = "12345678901"
                val orgnummer = "999999999"
                val existingId = UUID.randomUUID()
                val incomingId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(sykmeldingId = existingId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 3, 10))
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(sykmeldingId = incomingId, fnr = fnr, orgnummer = orgnummer, tom = LocalDate.of(2025, 1, 10))
                        )
                    )
                }

                // Existing row should be unchanged
                val row = db.findBySykmeldingId(existingId)
                row shouldNotBe null
                row!!.tom shouldBe LocalDate.of(2025, 3, 10)

                // Incoming row should not exist
                db.findBySykmeldingId(incomingId) shouldBe null
            }

            it("should update existing row when incoming tom is equal") {
                val fnr = "12345678901"
                val orgnummer = "999999999"
                val existingId = UUID.randomUUID()
                val incomingId = UUID.randomUUID()
                val sameTom = LocalDate.of(2025, 2, 15)

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(sykmeldingId = existingId, fnr = fnr, orgnummer = orgnummer, tom = sameTom, fom = LocalDate.of(2025, 1, 1))
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(sykmeldingId = incomingId, fnr = fnr, orgnummer = orgnummer, tom = sameTom, fom = LocalDate.of(2025, 2, 1))
                        )
                    )
                }

                // Should have updated to the incoming sykmelding
                val row = db.findBySykmeldingId(incomingId)
                row shouldNotBe null
                row!!.fom shouldBe LocalDate.of(2025, 2, 1)

                db.findBySykmeldingId(existingId) shouldBe null
            }

            it("should do nothing when batch is empty") {
                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(emptyList())
                }
            }
        }

        describe("transaction - revokeSykmeldingBatch") {
            it("should return 0 when no matching sykmeldingIds exist") {
                val unknownIds = listOf(UUID.randomUUID(), UUID.randomUUID())
                var result = 0
                db.transaction {
                    result = batchRevokeSykmelding(unknownIds, LocalDate.of(2025, 1, 15))
                }
                result shouldBe 0
            }

            it("should update single row with revokedDate and return 1") {
                val sykmeldingId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(
                                sykmeldingId = sykmeldingId,
                                orgnummer = "111111111",
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                val revokedDate = LocalDate.of(2025, 1, 15)
                var affectedRows = 0
                db.transaction {
                    affectedRows = batchRevokeSykmelding(listOf(sykmeldingId), revokedDate)
                }

                affectedRows shouldBe 1

                val row = db.findBySykmeldingId(sykmeldingId)
                row shouldNotBe null
                row!!.revokedDate shouldBe revokedDate
            }

            it("should revoke multiple sykmeldinger in one batch") {
                val sykmelding1 = sendtSykmelding(sykmeldingId = UUID.randomUUID(), tom = LocalDate.of(2025, 1, 5))
                val sykmelding2 = sendtSykmelding(sykmeldingId = UUID.randomUUID(), tom = LocalDate.of(2025, 1, 10), orgnummer = "111111111")
                val sykmelding3 = sendtSykmelding(sykmeldingId = UUID.randomUUID(), tom = LocalDate.of(2025, 1, 15), orgnummer = "222222222")

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(sykmelding1, sykmelding2, sykmelding3)
                    )
                }

                val revokedDate = LocalDate.of(2025, 1, 12)
                var affectedRows = 0
                db.transaction {
                    affectedRows = batchRevokeSykmelding(listOf(sykmelding1.sykmeldingId, sykmelding2.sykmeldingId, sykmelding3.sykmeldingId), revokedDate)
                }

                affectedRows shouldBe 3

                db.findBySykmeldingId(sykmelding1.sykmeldingId)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(sykmelding2.sykmeldingId)!!.revokedDate shouldBe revokedDate
                db.findBySykmeldingId(sykmelding3.sykmeldingId)!!.revokedDate shouldBe revokedDate
            }

            it("should return 0 when batch is empty") {
                var result = 0
                db.transaction {
                    result = batchRevokeSykmelding(emptyList(), LocalDate.now())
                }
                result shouldBe 0
            }
        }

        describe("mapping correctness") {
            it("should persist all fields") {
                val sykmeldingId = UUID.randomUUID()
                val fom = LocalDate.of(2025, 3, 1)
                val tom = LocalDate.of(2025, 3, 15)
                val syketilfelleStart = LocalDate.of(2025, 2, 28)

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(
                                sykmeldingId = sykmeldingId,
                                fnr = "09876543210",
                                orgnummer = "123456789",
                                fom = fom,
                                tom = tom,
                                syketilfelleStartDato = syketilfelleStart,
                            )
                        )
                    )
                }

                // Revoke to test that field is also mapped
                db.transaction {
                    batchRevokeSykmelding(listOf(sykmeldingId), LocalDate.of(2025, 3, 20))
                }

                val row = db.findBySykmeldingId(sykmeldingId)
                row shouldNotBe null

                row!!.id shouldNotBe null
                row.sykmeldingId shouldBe sykmeldingId
                row.fnr shouldBe "09876543210"
                row.orgnummer shouldBe "123456789"
                row.fom shouldBe fom
                row.tom shouldBe tom
                row.syketilfelleStartDato shouldBe syketilfelleStart
                row.revokedDate shouldBe LocalDate.of(2025, 3, 20)
                row.created shouldNotBe null
                row.updated shouldNotBe null
            }
        }

        describe("transaction rollback behavior") {
            it("should rollback entire transaction when an error occurs") {
                val sykmeldingId = UUID.randomUUID()

                val exception = shouldThrow<SykmeldingDbException> {
                    db.transaction {
                        batchUpsertSykmeldingerIfMoreRecentTom(listOf(sendtSykmelding(sykmeldingId = sykmeldingId)))
                        throw RuntimeException("Simulated failure")
                    }
                }

                exception.message shouldContain "Transaction failed"
                exception.cause shouldNotBe null

                db.findBySykmeldingId(sykmeldingId) shouldBe null
            }

            it("should rollback both insert and revoke when transaction fails") {
                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(
                                sykmeldingId = existingId,
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                val exception = shouldThrow<SykmeldingDbException> {
                    db.transaction {
                        batchUpsertSykmeldingerIfMoreRecentTom(listOf(sendtSykmelding(sykmeldingId = newId)))
                        batchRevokeSykmelding(listOf(existingId), LocalDate.of(2025, 1, 15))
                        throw RuntimeException("Simulated failure")
                    }
                }

                exception.message shouldContain "Transaction failed"

                db.findBySykmeldingId(newId) shouldBe null

                val existingRow = db.findBySykmeldingId(existingId)
                existingRow shouldNotBe null
                existingRow!!.revokedDate shouldBe null
            }

            it("should not retry the transaction when a database error occurs") {
                var attempts = 0

                shouldThrow<SykmeldingDbException> {
                    db.transaction {
                        attempts++
                        throw SQLException("Simulated database failure")
                    }
                }

                attempts shouldBe 1
            }

            it("should rethrow cancellation unwrapped and roll back") {
                val sykmeldingId = UUID.randomUUID()

                shouldThrow<CancellationException> {
                    db.transaction {
                        batchUpsertSykmeldingerIfMoreRecentTom(listOf(sendtSykmelding(sykmeldingId = sykmeldingId)))
                        throw CancellationException("Simulated cancellation")
                    }
                }

                db.findBySykmeldingId(sykmeldingId) shouldBe null
            }

            it("should commit both upsert and revoke when transaction succeeds") {
                val revokeId = UUID.randomUUID()
                val newId = UUID.randomUUID()

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(
                        listOf(
                            sendtSykmelding(
                                sykmeldingId = revokeId,
                                orgnummer = "222222222",
                                tom = LocalDate.of(2025, 1, 10),
                            )
                        )
                    )
                }

                db.transaction {
                    batchUpsertSykmeldingerIfMoreRecentTom(listOf(sendtSykmelding(orgnummer = "111111111", sykmeldingId = newId)))
                    batchRevokeSykmelding(listOf(revokeId), LocalDate.of(2025, 1, 15))
                }

                val newSykmelding = db.findBySykmeldingId(newId)
                newSykmelding shouldNotBe null
                val revokedRow = db.findBySykmeldingId(revokeId)
                revokedRow shouldNotBe null
                revokedRow!!.revokedDate shouldBe LocalDate.of(2025, 1, 15)
            }
        }
    })
