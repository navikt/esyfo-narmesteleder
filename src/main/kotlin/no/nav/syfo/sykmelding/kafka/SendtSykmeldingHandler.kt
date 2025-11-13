package no.nav.syfo.sykmelding.kafka

import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.kafka.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.util.logger
import java.time.LocalDate
import kotlin.collections.any

class SendtSykmeldingHandler(
    private val narmesteLederService: NarmestelederService
) {
    private val logger = logger()

    suspend fun handleSendtSykmelding(message: SendtSykmeldingKafkaMessage) {
        logger.info("Handling sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}")
        message.event.brukerSvar?.let { brukerSvar ->
            if (brukerSvar.riktigNarmesteLeder == null) {
                logger.info("No riktigNarmesteLeder answer for sykmeldingId: ${message.event.sykmeldingId}. Creating NL behov...")
                val arbeidsgiver = message.event.arbeidsgiver
                    ?: throw IllegalStateException("Arbeidsgiver is required to create NL behov for sykmeldingId: ${message.event.sykmeldingId}")

                narmesteLederService.createNewNlBehov(
                    nlBehov = LinemanagerRequirementWrite(
                        employeeIdentificationNumber = message.kafkaMetadata.fnr,
                        orgNumber = arbeidsgiver.orgnummer,
                    ),
                    hovedenhetOrgnummer = arbeidsgiver.juridiskOrgnummer,
                    skipSykmeldingCheck = message.sykmelding.sykmeldingsperioder
                        .any { LocalDate.now() in it.fom ..it.tom },
                )
            }
        }
    }
}
