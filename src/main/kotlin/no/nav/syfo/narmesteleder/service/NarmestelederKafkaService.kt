package no.nav.syfo.narmesteleder.service

import io.ktor.server.plugins.BadRequestException
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import org.slf4j.LoggerFactory

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
    val pdlService: PdlService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    fun sendNarmesteLederRelation(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        narmestelederAktorer: NarmestelederAktorer,
        source: NlResponseSource,
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            NlResponse(
                sykmeldt = Sykmeldt.from(narmestelederAktorer.sykmeldt),
                leder = narmesteLederRelasjonerWrite.leder.updateFromPerson(narmestelederAktorer.leder),
                orgnummer = narmesteLederRelasjonerWrite.organisasjonsnummer
            ), source = source
        )
    }

    suspend fun avbrytNarmesteLederRelation(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft, source: NlResponseSource
    ) {
        val sykmeldt = try {
            pdlService.getPersonFor(narmestelederRelasjonAvkreft.sykmeldtFnr)
        } catch (e: PdlResourceNotFoundException) {
            throw BadRequestException("Could not find sykmeldt for provided fnr")
        } catch (e: PdlRequestException) {
            throw ApiErrorException.InternalServerErrorException("Error when validating fnr for sykmeldt")
        }
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                sykmeldt.fnr,
                narmestelederRelasjonAvkreft.organisasjonsnummer,
            ), source = source
        )
    }
}
