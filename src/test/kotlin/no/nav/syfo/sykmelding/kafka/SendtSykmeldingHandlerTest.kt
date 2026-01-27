package no.nav.syfo.sykmelding.kafka

import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.model.RiktigNarmesteLeder
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.sykmelding.service.SykmeldingService

class SendtSykmeldingHandlerTest :
    DescribeSpec({

        val narmesteLederService = mockk<NarmestelederService>()
        val sykmeldingService = mockk<SykmeldingService>()
        val handler = SendtSykmeldingHandler(narmesteLederService, sykmeldingService)

        beforeEach {
            clearAllMocks()
            coEvery { sykmeldingService.insertOrUpdateSykmelding(any()) } just Runs
            coEvery { narmesteLederService.createNewNlBehov(any(), any(), any()) } returns null
        }

    describe("skipSykmeldingCheck parameter tests") {

        it("should set skipSykmeldingCheck to true when period includes today") {
            val today = LocalDate.now()
            val message = defaultSendtSykmeldingMessage(
                sykmeldingsperioder = listOf(
                    SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                )
            )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

        it("should set skipSykmeldingCheck to true when today is the first day of period") {
            val today = LocalDate.now()
            val message = defaultSendtSykmeldingMessage(
                sykmeldingsperioder = listOf(
                    SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                )
            )

            handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to true when today is the last day of period") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when all periods are in the past") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(20), tom = today.minusDays(10))
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    val createNewNlBehov = narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when all periods are in the future") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(10), tom = today.plusDays(20))
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when period ended yesterday") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(1))
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when period starts tomorrow") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(1), tom = today.plusDays(10))
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to true when at least one of multiple periods includes today") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20)),
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5)),
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(10), tom = today.plusDays(20))
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = true,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }

            it("should set skipSykmeldingCheck to false when multiple periods exist but none include today") {
                val today = LocalDate.now()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20)),
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(15), tom = today.minusDays(10)),
                        SykmeldingsperiodeAGDTO(fom = today.plusDays(10), tom = today.plusDays(20))
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = any(),
                        skipSykmeldingCheck = false,
                        behovSource = any(),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }
            }
        }

        describe("handleSendtSykmelding general behavior") {

            it("should not create NL behov when riktigNarmesteLeder is answered") {
                val message = defaultSendtSykmeldingMessage(
                    riktigNarmesteLeder = RiktigNarmesteLeder(
                        sporsmaltekst = "Er dette riktig leder?",
                        svar = "JA"
                    )
                )

                handler.handleSendtSykmelding(message)

                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any())
                }
            }

            it("should create NL behov with correct parameters when riktigNarmesteLeder is null") {
                val today = LocalDate.now()
                val fnr = "12345678901"
                val orgnummer = "999888777"
                val juridiskOrgnummer = "111222333"

                val message = defaultSendtSykmeldingMessage(
                    fnr = fnr,
                    orgnummer = orgnummer,
                    juridiskOrgnummer = juridiskOrgnummer,
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    ),
                    riktigNarmesteLeder = null,
                )

                val nlBehovSlot = slot<LinemanagerRequirementWrite>()
                val skipCheckSlot = slot<Boolean>()

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        nlBehov = capture(nlBehovSlot),
                        skipSykmeldingCheck = capture(skipCheckSlot),
                        behovSource = BehovSource(message.kafkaMetadata.sykmeldingId, source = SENDT_SYKMELDING_TOPIC),
                        arbeidsgiver = message.event.arbeidsgiver,
                    )
                }

                assert(nlBehovSlot.captured.employeeIdentificationNumber == fnr)
                assert(nlBehovSlot.captured.orgNumber == orgnummer)
                assert(skipCheckSlot.captured)
            }

            it("should not create NL behov when arbeidsgiver is null") {
                val message = defaultSendtSykmeldingMessage()
                    .copy(event = defaultSendtSykmeldingMessage().event.copy(arbeidsgiver = null))

                handler.handleSendtSykmelding(message)

                coVerify(exactly = 0) {
                    narmesteLederService.createNewNlBehov(any(), any(), any())
                }
            }
        }
    })
