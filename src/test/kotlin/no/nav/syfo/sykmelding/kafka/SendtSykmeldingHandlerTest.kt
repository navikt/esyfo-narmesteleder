package no.nav.syfo.sykmelding.kafka

import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.kafka.model.*
import java.time.LocalDate

class SendtSykmeldingHandlerTest :
    DescribeSpec({

        val narmesteLederService = mockk<NarmestelederService>()
        val handler = SendtSykmeldingHandler(narmesteLederService)

        beforeEach {
            clearAllMocks()
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
                        any(),
                        any(),
                        skipSykmeldingCheck = true
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
                        any(),
                        any(),
                        skipSykmeldingCheck = true
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
                        any(),
                        any(),
                        skipSykmeldingCheck = true
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
                    narmesteLederService.createNewNlBehov(
                        any(),
                        any(),
                        skipSykmeldingCheck = false
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
                        any(),
                        any(),
                        skipSykmeldingCheck = false
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
                        any(),
                        any(),
                        skipSykmeldingCheck = false
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
                        any(),
                        any(),
                        skipSykmeldingCheck = false
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
                        any(),
                        any(),
                        skipSykmeldingCheck = true
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
                        any(),
                        any(),
                        skipSykmeldingCheck = false
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
                    riktigNarmesteLeder = null
                )

                val nlBehovSlot = slot<LinemanagerRequirementWrite>()
                val skipCheckSlot = slot<Boolean>()

                handler.handleSendtSykmelding(message)

                coVerify {
                    narmesteLederService.createNewNlBehov(
                        capture(nlBehovSlot),
                        juridiskOrgnummer,
                        capture(skipCheckSlot)
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
