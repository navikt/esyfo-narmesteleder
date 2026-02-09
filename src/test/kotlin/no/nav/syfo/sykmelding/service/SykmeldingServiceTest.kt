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

        describe("insertOrUpdateSykmelding") {

            it("should insert sykmelding when it has employer and valid period") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                service.insertOrUpdateSykmelding(message)

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe message.kafkaMetadata.fnr
                stored.first().orgnummer shouldBe message.event.arbeidsgiver?.orgnummer
            }

            it("should NOT insert sykmelding when it has no employer") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                ).copy(
                    event = defaultSendtSykmeldingMessage().event.copy(arbeidsgiver = null)
                )

                service.insertOrUpdateSykmelding(message)

                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should NOT insert sykmelding when period is too old (1 year)") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(30),
                            tom = today.minusYears(2)
                        )
                    )
                )

                service.insertOrUpdateSykmelding(message)

                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should insert sykmelding when period ended within one year") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(20).minusYears(1),
                            tom = today.minusYears(1)
                        )
                    )
                )

                service.insertOrUpdateSykmelding(message)

                sykmeldingDb.findAll().size shouldBe 1
            }

            it("should use the latest period (max tom) for fom and tom") {
                val today = LocalDate.now()
                val earlierPeriod = SykmeldingsperiodeAGDTO(
                    fom = today.minusDays(30),
                    tom = today.minusDays(20)
                )
                val latestPeriod = SykmeldingsperiodeAGDTO(
                    fom = today.minusDays(5),
                    tom = today.plusDays(5)
                )
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(earlierPeriod, latestPeriod)
                )

                service.insertOrUpdateSykmelding(message)

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fom shouldBe latestPeriod.fom
                stored.first().tom shouldBe latestPeriod.tom
            }

            it("should update existing sykmelding when inserting with same sykmeldingId") {
                val today = LocalDate.now()

                val firstMessage = defaultSendtSykmeldingMessage(
                    fnr = "11111111111",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                    )
                )

                val secondMessage = defaultSendtSykmeldingMessage(
                    fnr = "22222222222",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                    )
                )

                service.insertOrUpdateSykmelding(firstMessage)
                service.insertOrUpdateSykmelding(secondMessage)

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "22222222222"
                stored.first().tom shouldBe today.plusDays(10)
            }

            it("should insert sykmelding with syketilfelleStartDato from message") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                service.insertOrUpdateSykmelding(message)

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                // syketilfelleStartDato comes from the first period's fom in defaultSendtSykmeldingMessage
                stored.first().syketilfelleStartDato shouldNotBe null
            }

            it("should insert sykmelding when period ends exactly on 1 year limit") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(26).minusYears(1),
                            tom = today.minusYears(1)
                        )
                    )
                )

                service.insertOrUpdateSykmelding(message)

                sykmeldingDb.findAll().size shouldBe 1
            }

            it("should NOT insert sykmelding when period ends one day beyond padding limit") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(27).minusYears(1),
                            tom = today.minusDays(1).minusYears(1)
                        )
                    )
                )

                service.insertOrUpdateSykmelding(message)

                sykmeldingDb.findAll().size shouldBe 0
            }
        }
    })
