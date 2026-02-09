package no.nav.syfo.sykmelding.service

import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.sykmelding.db.FakeSykmeldingDb
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import java.time.LocalDate
import java.util.UUID

class SykmeldingServiceTest :
    DescribeSpec({

        val sykmeldingDb = FakeSykmeldingDb()
        val service = SykmeldingService(sykmeldingDb)

        beforeEach {
            sykmeldingDb.clear()
        }

        describe("processBatch - basic insert behavior") {

            it("should insert sykmelding when it has employer and valid period") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe message.kafkaMetadata.fnr
                stored.first().orgnummer shouldBe message.event.arbeidsgiver?.orgnummer
            }

            it("should NOT insert sykmelding when it has no employer") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                ).copy(
                    event = defaultSendtSykmeldingMessage().event.copy(arbeidsgiver = null)
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should NOT insert sykmelding when period is too old (1 year)") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(30),
                            tom = today.minusYears(2)
                        )
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should insert sykmelding when period ended within one year") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(20).minusYears(1),
                            tom = today.minusYears(1)
                        )
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                sykmeldingDb.findAll().size shouldBe 1
            }

            it("should use the latest period (max tom) for fom and tom") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val earlierPeriod = SykmeldingsperiodeAGDTO(
                    fom = today.minusDays(30),
                    tom = today.minusDays(20)
                )
                val latestPeriod = SykmeldingsperiodeAGDTO(
                    fom = today.minusDays(5),
                    tom = today.plusDays(5)
                )
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(earlierPeriod, latestPeriod)
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fom shouldBe latestPeriod.fom
                stored.first().tom shouldBe latestPeriod.tom
            }

            it("should insert sykmelding with syketilfelleStartDato from message") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().syketilfelleStartDato shouldNotBe null
            }

            it("should do nothing when batch is empty") {
                service.processBatch(emptyList())
                sykmeldingDb.findAll().size shouldBe 0
            }
        }

        describe("processBatch - multiple records") {

            it("should insert multiple sykmeldinger in one batch") {
                val today = LocalDate.now()
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                val records = listOf(
                    SykmeldingRecord(
                        offset = 0,
                        sykmeldingId = id1,
                        message = defaultSendtSykmeldingMessage(
                            sykmeldingId = id1.toString(),
                            fnr = "11111111111",
                            sykmeldingsperioder = listOf(
                                SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                            )
                        )
                    ),
                    SykmeldingRecord(
                        offset = 1,
                        sykmeldingId = id2,
                        message = defaultSendtSykmeldingMessage(
                            sykmeldingId = id2.toString(),
                            fnr = "22222222222",
                            sykmeldingsperioder = listOf(
                                SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                            )
                        )
                    ),
                    SykmeldingRecord(
                        offset = 2,
                        sykmeldingId = id3,
                        message = defaultSendtSykmeldingMessage(
                            sykmeldingId = id3.toString(),
                            fnr = "33333333333",
                            sykmeldingsperioder = listOf(
                                SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                            )
                        )
                    )
                )

                service.processBatch(records)

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 3
                stored.find { it.sykmeldingId == id1 }?.fnr shouldBe "11111111111"
                stored.find { it.sykmeldingId == id2 }?.fnr shouldBe "22222222222"
                stored.find { it.sykmeldingId == id3 }?.fnr shouldBe "33333333333"
            }
        }

        describe("processBatch - final state deduplication") {

            it("should use highest offset when same sykmeldingId appears multiple times") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val firstMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "11111111111",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                    )
                )

                val secondMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "22222222222",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = firstMessage),
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = secondMessage)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "22222222222"
                stored.first().tom shouldBe today.plusDays(10)
            }

            it("should use highest offset regardless of order in list") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val firstMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "11111111111",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                    )
                )

                val secondMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "22222222222",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                    )
                )

                // Insert in reverse order (higher offset first in list)
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = secondMessage),
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = firstMessage)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "22222222222"
            }
        }

        describe("processBatch - tombstones (revokes)") {

            it("should revoke sykmelding when tombstone is received") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                // First insert
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = sykmeldingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = sykmeldingId.toString(),
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                                )
                            )
                        )
                    )
                )

                // Then revoke in separate batch
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = null)
                    )
                )

                val stored = sykmeldingDb.findBySykmeldingId(sykmeldingId)
                stored shouldNotBe null
                stored!!.revokedDate shouldBe LocalDate.now()
            }

            it("should skip insert when tombstone is final state in same batch") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val insertMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                    )
                )

                // Insert and tombstone in same batch - tombstone has higher offset
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = insertMessage),
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = null)
                    )
                )

                // Tombstone is final state, so nothing should be inserted
                // (revoke on non-existent record does nothing)
                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should insert when insert is final state after tombstone") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val insertMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "12345678901",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                    )
                )

                // Tombstone then insert - insert has higher offset
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = null),
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = insertMessage)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "12345678901"
                stored.first().revokedDate shouldBe null
            }

            it("should handle mixed inserts and revokes for different sykmeldingIds") {
                val today = LocalDate.now()
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                // First insert all
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = id1,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = id1.toString(),
                                fnr = "11111111111",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                                )
                            )
                        ),
                        SykmeldingRecord(
                            offset = 1,
                            sykmeldingId = id2,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = id2.toString(),
                                fnr = "22222222222",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                                )
                            )
                        ),
                        SykmeldingRecord(
                            offset = 2,
                            sykmeldingId = id3,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = id3.toString(),
                                fnr = "33333333333",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                                )
                            )
                        )
                    )
                )

                // Now batch with: revoke id1, update id2, leave id3 alone
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 3, sykmeldingId = id1, message = null),
                        SykmeldingRecord(
                            offset = 4,
                            sykmeldingId = id2,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = id2.toString(),
                                fnr = "44444444444",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                                )
                            )
                        )
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 3

                // id1 should be revoked
                sykmeldingDb.findBySykmeldingId(id1)!!.revokedDate shouldBe LocalDate.now()

                // id2 should be updated
                sykmeldingDb.findBySykmeldingId(id2)!!.fnr shouldBe "44444444444"
                sykmeldingDb.findBySykmeldingId(id2)!!.revokedDate shouldBe null

                // id3 should be unchanged
                sykmeldingDb.findBySykmeldingId(id3)!!.fnr shouldBe "33333333333"
                sykmeldingDb.findBySykmeldingId(id3)!!.revokedDate shouldBe null
            }
        }
    })
