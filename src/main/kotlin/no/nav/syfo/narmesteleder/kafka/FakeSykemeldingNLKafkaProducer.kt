package no.nav.syfo.narmesteleder.kafka

import java.security.MessageDigest
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.util.logger

class FakeSykemeldingNLKafkaProducer : ISykemeldingNLKafkaProducer {
    val logger = logger()
    override fun sendSykemeldingNLRelasjon(sykemeldingNL: NlResponse, source: NlResponseSource) {
        logger.info("FakeSykemeldingNLKafkaProducer sendSykemeldingNLRelasjon to orgnumber: ${sykemeldingNL.orgnummer}")
        logger.info(sykemeldingNL.hashFnrFields().toString())
    }

    override fun sendSykemeldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        logger.info("FakeSykemeldingNLKafkaProducer sendSykemeldingNLBrudd to orgnumber: ${nlAvbrutt.orgnummer} on behalf of source: ${source.name}")
        logger.info(nlAvbrutt.copy(sykmeldtFnr = nlAvbrutt.sykmeldtFnr.sha256()).toString())
    }

    fun NlResponse.hashFnrFields(): NlResponse {
        return this.copy(
            leder = this.leder.copy(fnr = this.leder.fnr.sha256()),
            sykmeldt = this.sykmeldt.copy(fnr = this.sykmeldt.fnr.sha256())
        )
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
