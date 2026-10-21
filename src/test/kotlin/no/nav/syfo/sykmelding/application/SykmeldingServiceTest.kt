package no.nav.syfo.sykmelding.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.TestDB
import no.nav.syfo.defaultSendtSykmeldingMessage
import no.nav.syfo.findAll
import no.nav.syfo.findBySykmeldingId
import no.nav.syfo.sykmelding.infrastructure.ExposedSykmeldingDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SykmeldingServiceTest :
    FunSpec({
        val sykmeldingDb = ExposedSykmeldingDb(TestDB.exposedDatabase)
        val service = SykmeldingService(sykmeldingDb)

        beforeEach {
            TestDB.clearAllData()
        }

        test("an empty batch never opens a database transaction") {
            SykmeldingService(UnusedSykmeldingDb).processBatch(emptyList())
        }

        context("processBatch - tombstones (revokes)") {

            test("should revoke with the clock date when tombstone is received") {
                val fixedToday = LocalDate.parse("2026-03-01")
                val serviceWithFixedClock = SykmeldingService(
                    sykmeldingDb,
                    Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC),
                )
                val sykmeldingId = UUID.randomUUID()
                serviceWithFixedClock.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = sykmeldingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = sykmeldingId.toString(),
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = fixedToday.minusDays(10), tom = fixedToday.minusDays(5))
                                )
                            )
                        )
                    )
                )

                serviceWithFixedClock.processBatch(
                    listOf(SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = null))
                )

                sykmeldingDb.findBySykmeldingId(sykmeldingId)?.revokedDate shouldBe fixedToday
            }

            test("should handle mixed inserts and revokes for different sykmeldingIds") {
                val today = LocalDate.now()
                val revokeId = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                val record1 = SykmeldingRecord(
                    offset = 0,
                    sykmeldingId = revokeId,
                    message = defaultSendtSykmeldingMessage(
                        sykmeldingId = revokeId.toString(),
                        fnr = "11111111111",
                        sykmeldingsperioder = listOf(
                            SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                        )
                    )
                )
                val record2 = SykmeldingRecord(
                    offset = 1,
                    sykmeldingId = id2,
                    message = defaultSendtSykmeldingMessage(
                        sykmeldingId = id2.toString(),
                        fnr = "22222222222",
                        sykmeldingsperioder = listOf(
                            SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                        )
                    )
                )
                val record3 = SykmeldingRecord(
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

                // First insert all
                service.processBatch(
                    listOf(
                        record1,
                        record2,
                        record3
                    )
                )

                val unchangedId2Entity = sykmeldingDb.findBySykmeldingId(id2)
                // Now batch with: revoke id1, update id2, leave id3 alone
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 3, sykmeldingId = revokeId, message = null),
                        SykmeldingRecord(
                            offset = 4,
                            sykmeldingId = id2,
                            message = record2.message!!.copy()
                        )
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 3

                // id1 should be revoked
                sykmeldingDb.findBySykmeldingId(revokeId)!!.revokedDate shouldBe LocalDate.now()

                // id2 should be re-created
                val recreatedId2Entity = sykmeldingDb.findBySykmeldingId(id2)
                recreatedId2Entity!!.fnr shouldBe "22222222222"
                unchangedId2Entity!!.updated shouldNotBe recreatedId2Entity.updated
                // id3 should be unchanged
                sykmeldingDb.findBySykmeldingId(id3)!!.fnr shouldBe "33333333333"
                sykmeldingDb.findBySykmeldingId(id3)!!.revokedDate shouldBe null
                sykmeldingDb.findBySykmeldingId(id3)!!.orgnummer shouldBe record3.message?.event?.arbeidsgiver?.orgnummer
            }
        }
    })
