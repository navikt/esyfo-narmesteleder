package no.nav.syfo.sykmelding.service

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.sykmelding.exposed.ISendtSykmeldingNarmestelederBruddRepository
import java.util.UUID

class NarmestelederBruddServiceTest :
    DescribeSpec({
        val kafkaProducer = mockk<ISykmeldingNLKafkaProducer>()
        val narmestelederKafkaService = NarmestelederKafkaService(kafkaProducer)
        val bruddRepository = mockk<ISendtSykmeldingNarmestelederBruddRepository>()
        val service = NarmestelederBruddService(narmestelederKafkaService, bruddRepository)

        beforeEach {
            clearMocks(kafkaProducer, bruddRepository)
            coEvery { bruddRepository.findBySykmeldingId(any()) } returns null
            coEvery { bruddRepository.insert(any()) } just Runs
            every { kafkaProducer.sendSykmldingNLBrudd(any(), any()) } just Runs
        }

        it("publishes a revoke as the employee and stores the processed Kafka record") {
            val sykmeldingId = UUID.randomUUID()

            service.revokeFromSendtSykmelding(
                sykmeldingId = sykmeldingId,
                fnr = "12345678901",
                orgnummer = "123456789",
                kafkaPartition = 1,
                kafkaOffset = 123,
            )

            verify {
                kafkaProducer.sendSykmldingNLBrudd(
                    nlAvbrutt = match {
                        it.sykmeldtFnr == "12345678901" &&
                            it.orgnummer == "123456789"
                    },
                    source = NlResponseSource.ARBEIDSTAGER_SYKMELDING_REVOKE,
                )
            }
            coVerify {
                bruddRepository.insert(
                    match {
                        it.sykmeldingId == sykmeldingId &&
                            it.kafkaPartition == 1 &&
                            it.kafkaOffset == 123L &&
                            it.kilde == NlResponseSource.ARBEIDSTAGER_SYKMELDING_REVOKE.source
                    }
                )
            }
        }

        it("does not publish an already tracked revoke") {
            val sykmeldingId = UUID.randomUUID()
            coEvery { bruddRepository.findBySykmeldingId(sykmeldingId) } returns mockk()

            service.revokeFromSendtSykmelding(
                sykmeldingId = sykmeldingId,
                fnr = "12345678901",
                orgnummer = "123456789",
                kafkaPartition = 1,
                kafkaOffset = 123,
            )

            verify(exactly = 0) {
                kafkaProducer.sendSykmldingNLBrudd(any(), any())
            }
            coVerify(exactly = 0) { bruddRepository.insert(any()) }
        }
    })
